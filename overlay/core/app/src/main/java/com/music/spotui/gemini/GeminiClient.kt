// Myspoti AI patch set, built on Myspoti (https://github.com/kota-zo/myspoti), GPL-3.0.
// New file written for this patch set; see NOTICE.md at the root of the patch repository.
package com.music.spotui.gemini

import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Why a Gemini request failed, told apart so the screen can say what to do about it. */
class GeminiException(val kind: Kind, message: String? = null) : IOException(message) {
    enum class Kind { NO_KEY, KEY_INVALID, QUOTA, NETWORK, MODEL, OTHER }
}

/**
 * A small client for Google's Gemini REST API (an AI Studio key, free tier is enough).
 * One request carries a batch of lyric lines and the answer is forced into a JSON array of
 * `{ "i": line index, "v": result }`, so nothing has to be scraped out of free text.
 */
object GeminiClient {
    private const val TAG = "GeminiClient"
    private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/"

    /**
     * Tried in this order. The next one is used when Google says a model is gone or overloaded, or
     * when its free quota is used up (quotas are counted per model). Edit here when Google retires one.
     */
    private val MODELS = listOf(
        "gemini-3-flash-preview",
        "gemini-3.1-flash-lite",
        "gemini-flash-lite-latest",
        "gemini-flash-latest",
    )

    /**
     * A model that just failed (overloaded, out of quota, refusing the request) goes to the back of the line for a
     * while, so the next requests do not wait on it again. It is still tried last, when nothing else answers.
     */
    private val coolUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val COOL_OVERLOADED_MS = 3 * 60_000L
    private const val COOL_QUOTA_MS = 60_000L
    private const val COOL_REFUSED_MS = 10 * 60_000L

    private fun modelsInOrder(): List<String> {
        val now = android.os.SystemClock.elapsedRealtime()
        val (ready, cooling) = MODELS.partition { (coolUntil[it] ?: 0L) <= now }
        return ready + cooling
    }

    /** The request itself was refused (HTTP 400) by this model: the next model may take it. */
    private fun refused(e: GeminiException) = e.kind == GeminiException.Kind.OTHER && e.message?.startsWith("HTTP 400") == true

    /** Asks [system] about [lines] (index to text) and returns index to answer. Throws [GeminiException]. */
    fun ask(apiKey: String, system: String, lines: List<Pair<Int, String>>, thinking: Int = 0): Map<Int, String> {
        try {
            com.music.spotui.data.DataSaver.check()
        } catch (e: Exception) {
            throw GeminiException(GeminiException.Kind.NETWORK, "data saver")
        }
        val input = JSONArray()
        for ((index, text) in lines) input.put(JSONObject().put("i", index).put("t", text))
        val user = "Lines of one song, as a JSON array of {\"i\": line index, \"t\": lyric line}:\n$input"
        val wanted = lines.map { it.first }.toSet()
        var last: GeminiException? = null
        for (model in modelsInOrder()) {
            val began = android.os.SystemClock.elapsedRealtime()
            try {
                val answer = parse(callWithThinking(model, apiKey, system, user, thinking), wanted)
                AiLog.log("model $model answered in ${android.os.SystemClock.elapsedRealtime() - began} ms")
                coolUntil.remove(model)
                return answer
            } catch (e: GeminiException) {
                AiLog.log("model $model failed after ${android.os.SystemClock.elapsedRealtime() - began} ms: ${e.kind} ${e.message.orEmpty()}")
                if (e.kind != GeminiException.Kind.MODEL && e.kind != GeminiException.Kind.QUOTA && !refused(e)) throw e
                val pause = when {
                    e.kind == GeminiException.Kind.QUOTA -> COOL_QUOTA_MS
                    refused(e) -> COOL_REFUSED_MS
                    else -> COOL_OVERLOADED_MS
                }
                coolUntil[model] = android.os.SystemClock.elapsedRealtime() + pause
                last = e
            }
        }
        throw last ?: GeminiException(GeminiException.Kind.OTHER, "no model answered")
    }

    /**
     * Lyrics need little or no "thinking", and the thinking models spend a long time on it by default.
     * [thinking] is the token budget (0 = off). A model that refuses the setting is asked again without it.
     */
    private fun callWithThinking(model: String, apiKey: String, system: String, user: String, thinking: Int): String =
        try {
            call(model, apiKey, system, user, thinking)
        } catch (e: GeminiException) {
            if (refused(e)) {
                // Some models do not take the thinking setting (or not this budget): once more without it.
                call(model, apiKey, system, user, null)
            } else {
                throw e
            }
        }

    private fun call(model: String, apiKey: String, system: String, user: String, thinking: Int?): String {
        val schema = JSONObject()
            .put("type", "ARRAY")
            .put(
                "items",
                JSONObject()
                    .put("type", "OBJECT")
                    .put(
                        "properties",
                        JSONObject()
                            .put("i", JSONObject().put("type", "INTEGER"))
                            .put("v", JSONObject().put("type", "STRING")),
                    )
                    .put("required", JSONArray().put("i").put("v")),
            )
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put(
                "contents",
                JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", user)))),
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.4)
                    .put("responseMimeType", "application/json")
                    .put("responseSchema", schema)
                    .apply { if (thinking != null) put("thinkingConfig", JSONObject().put("thinkingBudget", thinking)) },
            )
            .toString()

        val conn = (URL("$ENDPOINT$model:generateContent").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-goog-api-key", apiKey)
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val detail = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty() }.getOrDefault("")
                Log.d(TAG, "HTTP $code from $model")
                val kind = when {
                    code == 401 || code == 403 ||
                        (code == 400 && (detail.contains("API_KEY_INVALID") || detail.contains("API key not valid"))) ->
                        GeminiException.Kind.KEY_INVALID
                    code == 429 || detail.contains("RESOURCE_EXHAUSTED") -> GeminiException.Kind.QUOTA
                    code == 404 || code in 500..599 -> GeminiException.Kind.MODEL
                    else -> GeminiException.Kind.OTHER
                }
                // What Google says about a refused request goes in the note (the log shows why).
                val why = if (code == 400) " " + detail.replace(Regex("\\s+"), " ").take(160) else ""
                throw GeminiException(kind, "HTTP $code$why")
            }
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return answerText(text)
        } catch (e: GeminiException) {
            throw e
        } catch (e: JSONException) {
            throw GeminiException(GeminiException.Kind.OTHER, "unreadable response")
        } catch (e: IOException) {
            throw GeminiException(GeminiException.Kind.NETWORK, e.javaClass.simpleName)
        } finally {
            conn.disconnect()
        }
    }

    /** The text of the first candidate, thinking parts left out. */
    private fun answerText(response: String): String {
        val parts = JSONObject(response).optJSONArray("candidates")
            ?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            ?: throw GeminiException(GeminiException.Kind.OTHER, "no answer")
        val text = StringBuilder()
        for (k in 0 until parts.length()) {
            val part = parts.optJSONObject(k) ?: continue
            if (part.optBoolean("thought", false)) continue
            text.append(part.optString("text", ""))
        }
        return text.toString()
    }

    /** The rows of the answer that belong to [wanted] lines and say something, by line index. */
    private fun parse(answer: String, wanted: Set<Int>): Map<Int, String> {
        val cleaned = answer.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val rows: JSONArray = try {
            if (cleaned.startsWith("[")) {
                JSONArray(cleaned)
            } else {
                // An object holding the array (some models wrap it), the first array found.
                val holder = JSONObject(cleaned)
                var found: JSONArray? = null
                val names = holder.keys()
                while (found == null && names.hasNext()) found = holder.optJSONArray(names.next())
                found ?: throw GeminiException(GeminiException.Kind.OTHER, "no array in the answer")
            }
        } catch (e: JSONException) {
            throw GeminiException(GeminiException.Kind.OTHER, "unreadable answer")
        }
        val out = LinkedHashMap<Int, String>()
        for (k in 0 until rows.length()) {
            val row = rows.optJSONObject(k) ?: continue
            val index = row.optInt("i", -1)
            val value = row.optString("v", "").trim()
            if (index in wanted && value.isNotEmpty() && index !in out) out[index] = value
        }
        return out
    }
}
