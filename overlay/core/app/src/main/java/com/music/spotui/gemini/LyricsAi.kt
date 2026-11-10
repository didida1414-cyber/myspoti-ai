// Myspoti AI patch set, built on Myspoti (https://github.com/kota-zo/myspoti), GPL-3.0.
// New file written for this patch set; see NOTICE.md at the root of the patch repository.
package com.music.spotui.gemini

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import app.myspoti.engine.R
import com.music.spotui.data.api.LyricTranslate
import com.music.spotui.di.Graph
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock

/** How freely a translation follows the original wording. */
enum class TranslationStyle { LITERAL, BALANCED, FREE }

/**
 * Lyrics translation and Hangul pronunciation made by Gemini, for the lyrics screens.
 *
 * Results are kept per song on this phone (one small JSON file each, the oldest are dropped past
 * [MAX_FILES]), so a song is only sent to Google once per language.
 */
object LyricsAi {
    /** What a batch gave: the lines that were made (by index) and, if it stopped early, why. */
    class Outcome(val values: Map<Int, String>, val error: GeminiException?)

    private const val FIELD_PRONUNCIATION = "pronunciation"
    private const val MAX_FILES = 400
    /** Lines per request: a long song goes in several, one that fails keeps what the others made. */
    private const val CHUNK = 90
    private val lock = ReentrantLock()

    // ---------------------------------------------------------------- settings

    /** The song whose pronunciation was switched on by hand (the switch is per song, nothing starts by itself). */
    @Volatile private var pronunciationKey: String? = null

    /** What the app's romanization line was before the pronunciation took it over (null: not taken over). */
    @Volatile var romanizationBefore: Boolean? = null

    private fun currentKey(): String? = runCatching { com.music.spotui.di.SongPlayer.currentLyricsTrack()?.key }.getOrNull()

    /** The pronunciation switch of the lyrics menus: on for the song that is playing, when it was switched on for it. */
    fun pronunciationEnabled(): Boolean = pronunciationKey?.let { it == currentKey() } == true

    fun setPronunciationEnabled(on: Boolean) {
        pronunciationKey = if (on) currentKey() else null
    }

    // ---------------------------------------------------------------- which lines

    private fun isHangul(c: Char): Boolean = c in '가'..'힣' || c in 'ᄀ'..'ᇿ' || c in '㄰'..'㆏'

    /** True when [text] has Hangul in it (a pronunciation line is made of it). */
    fun hasHangul(text: String?): Boolean = text != null && text.any { isHangul(it) }

    /** A line worth translating into [target]: it has letters, and for Korean some of them are not Hangul. */
    fun translatable(text: String, target: String): Boolean {
        val line = text.trim()
        if (line.isEmpty() || line == "♪" || line.none { it.isLetter() }) return false
        return if (target.substringBefore('-').equals("ko", ignoreCase = true)) {
            line.any { it.isLetter() && !isHangul(it) }
        } else {
            true
        }
    }

    /** A line to be read out in Hangul: letters of another script, and no Hangul already. */
    fun pronounceable(text: String): Boolean {
        val line = text.trim()
        if (line.isEmpty() || line == "♪" || line.none { it.isLetter() }) return false
        return line.none { isHangul(it) }
    }

    // ---------------------------------------------------------------- language of the lyrics, from their letters

    private fun isKorean(lang: String?) = lang != null && lang.substringBefore('-').substringBefore('_').equals("ko", ignoreCase = true)

    /** Lines with letters that are not Hangul needed before a Korean song counts as foreign (a lone "yeah" does not). */
    private const val MIN_FOREIGN_LINES = 2

    /** The language of the letters that are not Hangul in [lines]: kana ja, Han zh, Cyrillic ru, Arabic ar, Thai th, Latin en. */
    private fun foreignLanguage(lines: List<String>): String {
        var kana = 0; var han = 0; var cyrillic = 0; var arabic = 0; var thai = 0; var latin = 0; var other = 0
        for (line in lines) for (ch in line) {
            if (!ch.isLetter() || isHangul(ch)) continue
            when (Character.UnicodeScript.of(ch.code)) {
                Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> kana++
                Character.UnicodeScript.HAN -> han++
                Character.UnicodeScript.CYRILLIC -> cyrillic++
                Character.UnicodeScript.ARABIC -> arabic++
                Character.UnicodeScript.THAI -> thai++
                Character.UnicodeScript.LATIN -> latin++
                else -> other++
            }
        }
        val counts = listOf(
            "ja" to (if (kana > 0) kana + han else 0),
            "zh" to (if (kana == 0) han else 0),
            "ru" to cyrillic, "ar" to arabic, "th" to thai, "en" to latin, "und" to other,
        )
        return counts.maxByOrNull { it.second }?.takeIf { it.second > 0 }?.first ?: "und"
    }

    /**
     * The language the lyrics screen goes by (instead of the one the source labels them with): a song the source
     * calls Korean that has foreign lines in it, with Korean as the language to read in, is one to translate.
     * Everything else stays as the source says.
     */
    fun sourceLanguage(language: String?, lines: List<String>): String? {
        if (!GeminiKeyStore.has() || !isKorean(translateTarget())) return language
        if (language != null && !isKorean(language)) return language
        if (lines.count { translatable(it, "ko") } < MIN_FOREIGN_LINES) return language
        return foreignLanguage(lines)
    }

    private fun translateTarget(): String =
        com.music.spotui.data.preferences.getLyricTranslateLang(Graph.app).ifBlank { Locale.getDefault().language.ifBlank { "en" } }

    /**
     * The usual translator asks Google for the language of the first lines. With a Gemini key and Korean to read in,
     * the letters tell it with no network. Null (not handled, the usual way goes on) otherwise.
     */
    fun detectHook(lines: List<String>, target: String): String? {
        if (!GeminiKeyStore.has() || !isKorean(target)) return null
        if (lines.count { translatable(it, "ko") } < MIN_FOREIGN_LINES) return target
        return foreignLanguage(lines)
    }

    // ---------------------------------------------------------------- the hook in LyricTranslate.translateLines

    /** Style asked for by "translate this song again", used by the next translation only (expires, see [FRESH_MS]). */
    @Volatile private var nextStyle: TranslationStyle? = null
    @Volatile private var nextAt = 0L
    private const val FRESH_MS = 45_000L
    private val inHook = ThreadLocal<Boolean>()

    /** "Translate again" in [style]: the next translation ignores what was kept and uses this style. */
    fun requestRegenerate(style: TranslationStyle) {
        nextStyle = style
        nextAt = System.currentTimeMillis()
    }

    /** True while a "translate again" is waiting for its translation (the lyrics code skips the source's own one then). */
    fun regenerating(): Boolean = nextStyle != null && System.currentTimeMillis() - nextAt < FRESH_MS

    /**
     * First line of the usual translator: Gemini's translation when a key is saved, null otherwise (or when
     * Gemini gave nothing), and the usual translator goes on. The usual translator is also Gemini's own
     * fallback for lines it left out, so a call from inside this hook is let through ([inHook]).
     */
    fun translateHook(lines: List<String>, target: String): LyricTranslate.Result? {
        if (inHook.get() == true) return null
        if (!GeminiKeyStore.has()) return null
        var style = TranslationStyle.BALANCED
        var force = false
        synchronized(this) {
            val asked = nextStyle
            if (asked != null && System.currentTimeMillis() - nextAt < FRESH_MS) {
                style = asked
                force = true
            }
            nextStyle = null
        }
        inHook.set(true)
        AiLog.log("translate: ${lines.size} lines to $target, style $style${if (force) ", again" else ""}")
        return try {
            translateForLyrics("", lines, target, null, style, force)
        } catch (e: Exception) {
            null
        } finally {
            inHook.set(false)
        }
    }

    // ---------------------------------------------------------------- translation

    /**
     * The lyrics view model's entry: [lines] translated by Gemini in the shape of the usual translator's
     * answer, or null when Gemini cannot be used (no key, an error, nothing came back), where the usual
     * translator takes over. Lines Gemini left out are filled by the usual translator.
     * [force]: make it again, whatever was kept.
     */
    fun translateForLyrics(
        trackKey: String?,
        lines: List<String>,
        target: String,
        sourceLanguage: String?,
        style: TranslationStyle,
        force: Boolean,
    ): LyricTranslate.Result? {
        if (trackKey == null || !GeminiKeyStore.has()) return null
        val wanted = lines.indices.filter { translatable(lines[it], target) }
        if (wanted.isEmpty()) return null
        // Thinking: none for the literal and balanced styles, a little for the free one (it recasts the lines).
        val thinking = if (style == TranslationStyle.FREE) 512 else 0
        val outcome = generate("tr.${target.lowercase(Locale.ROOT)}", trackKey, lines, wanted, translationPrompt(target, style), force, thinking)
        outcome.error?.let { toast(it) }
        if (outcome.values.isEmpty()) return null
        val missing = wanted.filter { it !in outcome.values }
        val usual: Map<Int, String> =
            if (missing.isEmpty()) {
                emptyMap()
            } else {
                LyricTranslate.translateLines(missing.map { lines[it] }, target)?.lines?.let { missing.zip(it).toMap() } ?: emptyMap()
            }
        return LyricTranslate.Result(lines.indices.map { outcome.values[it] ?: usual[it].orEmpty() }, sourceLanguage)
    }

    private fun translationPrompt(target: String, style: TranslationStyle): String {
        val name = Locale.forLanguageTag(target.replace('_', '-')).getDisplayLanguage(Locale.ENGLISH).ifBlank { target }
        val korean = target.substringBefore('-').equals("ko", ignoreCase = true)
        val manner = when (style) {
            TranslationStyle.LITERAL ->
                "Style: literal. Stay as close to the original words and sentence structure as $name grammar allows. " +
                    "Render idioms by their words. Do not add, drop or smooth anything; some stiffness is fine." +
                    (if (korean) " Use plain written Korean that follows the source structure." else "")
            TranslationStyle.BALANCED ->
                "Style: balanced. Keep the meaning and the imagery faithfully, and phrase them as natural spoken $name. " +
                    "Rephrase only where a literal rendering would sound wrong (idioms, slang, wordplay). Add nothing that is not in the original." +
                    (if (korean) " Use everyday spoken Korean." else "")
            TranslationStyle.FREE ->
                "Style: free. Carry the feeling and the intent rather than the words, as a native lyricist would write the line. " +
                    "Recast and reorder freely, swap idioms and metaphors for equivalents with the same feeling, and make implied " +
                    "subjects clear. Stay true to the story; invent nothing." +
                    (if (korean) " Use lyrical Korean that could be sung." else "")
        }
        return """
            You translate song lyrics into $name.
            You get the lines of ONE song as a JSON array of {"i": line index, "t": lyric line} and answer with the same lines translated.

            What to translate:
            - Every line that has a language other than $name in it, mixed lines too. The result is one line written entirely in $name.
            - A line that is already all $name comes back unchanged.
            - Pure vocalizations ("yeah", "oh", "ooh", "la la", "na na", "hey") come back as they are. In a mixed line, translate the words and keep the vocalizations.
            - Keep the punctuation habits of the line. Names of people and places may keep their usual spelling.

            Read the whole song before you start: who is speaking to whom, the mood, the idioms and slang. Keep one consistent voice from the first line to the last.

            $manner

            Answer with a JSON array of {"i": the same line index, "v": the translated line}, one entry for every input line, in the same order. Never merge, split, drop or reorder lines.
        """.trimIndent()
    }

    // ---------------------------------------------------------------- pronunciation

    /** Hangul readings of [wanted] lines of [lines], by line index (kept per song). */
    fun pronounce(trackKey: String, lines: List<String>, wanted: List<Int>): Outcome =
        generate(FIELD_PRONUNCIATION, trackKey, lines, wanted, PRONUNCIATION_PROMPT, force = false, thinking = 0)

    private val PRONUNCIATION_PROMPT = """
        You write how song lyrics sound when they are sung, spelled in Hangul, for Korean readers who sing along.
        You get the lines of ONE song as a JSON array of {"i": line index, "t": lyric line} and answer with the Hangul sound of each line.

        - Write the sound, never the meaning. Do not translate.
        - English as it is pronounced: "I love you" gives "아이 러브 유".
        - Japanese the way Korean lyric sites write it: "ありがとう" gives "아리가토". Leave out long-vowel marks, write a small っ as a ㅅ final and ん as a ㄴ final.
        - Chinese as Mandarin. Spanish, French and the rest as they are pronounced in the language.
        - Use only Hangul, spaces and the punctuation the line already has. Numbers as they are sung.

        Answer with a JSON array of {"i": the same line index, "v": the Hangul reading}, one entry for every input line, in the same order.
    """.trimIndent()

    // ---------------------------------------------------------------- batches and the per-song cache

    /**
     * Makes [wanted] lines under [field] of the song's cache file. What is already kept is used (unless
     * [force]); the rest is asked in chunks, a second time for lines the model skipped.
     * The lock is held only while the cache file is read or written, never while Gemini is asked, so a
     * translation and a pronunciation of the same song do not wait for each other.
     */
    private fun generate(
        field: String,
        trackKey: String,
        lines: List<String>,
        wanted: List<Int>,
        system: String,
        force: Boolean,
        thinking: Int,
    ): Outcome {
        val result = LinkedHashMap<Int, String>()
        if (wanted.isEmpty()) return Outcome(result, null)
        val file = cacheFile(trackKey, lines)
        if (!force) {
            locked {
                val kept = readJson(file).optJSONObject(field) ?: JSONObject()
                for (i in wanted) {
                    val value = kept.optString(i.toString(), "")
                    if (value.isNotBlank()) result[i] = value
                }
            }
        }
        var todo = wanted.filter { it !in result }
        if (todo.isEmpty()) {
            locked { file.setLastModified(System.currentTimeMillis()) }
            return Outcome(result, null)
        }
        val apiKey = GeminiKeyStore.get() ?: return Outcome(result, GeminiException(GeminiException.Kind.NO_KEY))
        val made = LinkedHashMap<Int, String>()
        var error: GeminiException? = null
        for (pass in 0 until 2) {
            if (todo.isEmpty()) break
            try {
                for (chunk in todo.chunked(CHUNK)) {
                    val began = android.os.SystemClock.elapsedRealtime()
                    AiLog.log("[$field] asking Gemini for ${chunk.size} lines")
                    val answer = GeminiClient.ask(apiKey, system, chunk.map { it to lines[it].trim() }, thinking)
                    AiLog.log("[$field] answer of ${answer.size} lines in ${android.os.SystemClock.elapsedRealtime() - began} ms")
                    for ((i, value) in answer) {
                        result[i] = value
                        made[i] = value
                    }
                }
            } catch (e: GeminiException) {
                error = e
                break
            }
            todo = wanted.filter { it !in result }
        }
        if (made.isNotEmpty()) {
            // Read again here: another request may have saved its own lines of this song meanwhile.
            locked {
                val root = readJson(file)
                val kept = root.optJSONObject(field) ?: JSONObject()
                for ((i, value) in made) kept.put(i.toString(), value)
                root.put(field, kept)
                writeJson(file, root)
                trim(file.parentFile)
            }
        }
        return Outcome(result, error)
    }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    private fun cacheDir(): File = File(Graph.app.filesDir, "gemini_lyrics").also { it.mkdirs() }

    /** One file per song and per exact text of its lyrics (another source's version is another file). */
    private fun cacheFile(trackKey: String, lines: List<String>): File {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(trackKey.toByteArray(Charsets.UTF_8))
        digest.update(0)
        for (line in lines) {
            digest.update(line.toByteArray(Charsets.UTF_8))
            digest.update(10)
        }
        val name = digest.digest().joinToString("") { "%02x".format(it) }.take(40)
        return File(cacheDir(), "$name.json")
    }

    private fun readJson(file: File): JSONObject =
        try {
            if (file.isFile) JSONObject(file.readText(Charsets.UTF_8)) else JSONObject()
        } catch (e: Exception) {
            JSONObject()
        }

    private fun writeJson(file: File, json: JSONObject) {
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(json.toString(), Charsets.UTF_8)
        if (!temp.renameTo(file)) {
            file.writeText(json.toString(), Charsets.UTF_8)
            temp.delete()
        }
    }

    private fun trim(dir: File?) {
        val files = dir?.listFiles { f -> f.name.endsWith(".json") } ?: return
        if (files.size <= MAX_FILES) return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_FILES).forEach { it.delete() }
    }

    // ---------------------------------------------------------------- messages

    fun toast(error: GeminiException) {
        toast(
            when (error.kind) {
                GeminiException.Kind.NO_KEY -> R.string.gemini_error_no_key
                GeminiException.Kind.KEY_INVALID -> R.string.gemini_error_key
                GeminiException.Kind.QUOTA -> R.string.gemini_error_quota
                GeminiException.Kind.NETWORK -> R.string.gemini_error_network
                GeminiException.Kind.MODEL, GeminiException.Kind.OTHER -> R.string.gemini_error_other
            },
        )
    }

    fun toast(resId: Int) {
        Handler(Looper.getMainLooper()).post { Toast.makeText(Graph.app, resId, Toast.LENGTH_LONG).show() }
    }
}
