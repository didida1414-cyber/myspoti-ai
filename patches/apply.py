#!/usr/bin/env python3
"""Applies the Myspoti AI patch set to a checkout of kota-zo/myspoti. Reads no network.

    python3 patches/apply.py <checkout> [--report report.md] [--without volume]

The work is split in FEATURES. A feature is applied all together or not at all:

  core features   (Gemini translation, AI pronunciation, the lyrics menus, Korean start, app id)
                  if one cannot be placed, nothing is released: exit code 2.
  soft features   (fine volume, Korean text for the missing UI strings, the Immersive menu rows, the app name)
                  if one cannot be placed it is left out, with its new files, and the report says so.

Every edit says where it goes by what the code IS (a function, a call), not by a line number, and has
several ways to find that place: the exact text first, then the same text ignoring spaces and line breaks,
then a looser pattern. An anchor that matches more than once or not at all is never guessed.

--without volume leaves the fine volume out on purpose (the workflow does this to retry a build that did not
compile with it).

Myspoti AI patch set, built on Myspoti (https://github.com/kota-zo/myspoti), GPL-3.0.
"""
import argparse
import json
import re
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CFG = json.loads((ROOT / "ai.json").read_text(encoding="utf-8"))
J = "app/src/main/java/com/music/spotui/"
KT = "app/src/main/java/"


# ------------------------------------------------------------------ edits

class Option:
    """One way to find the place. how: before | after | replace.
    before/after work on whole lines: the text goes above the line the anchor starts on, or below the line it ends on.
    pattern: a regex (multi-line mode). text: a string, or a function of the match returning the string."""

    def __init__(self, how, pattern, text):
        self.how, self.pattern, self.text = how, pattern, text


class Edit:
    def __init__(self, path, options, optional=False, name=""):
        self.path, self.options, self.optional, self.name = path, options, optional, name


def exact(anchor):
    return re.escape(anchor)


def loose(anchor):
    """The anchor with any run of spaces and line breaks allowed where it has one."""
    return r"\s+".join(re.escape(part) for part in anchor.split())


def opts(how, anchor, text):
    """The exact text first, then the same text ignoring layout."""
    return [Option(how, exact(anchor), text), Option(how, loose(anchor), text)]


def run_option(text, option):
    rx = re.compile(option.pattern, re.M)
    found = list(rx.finditer(text))
    if len(found) != 1:
        return None
    m = found[0]
    new = option.text(m) if callable(option.text) else option.text
    if option.how == "replace":
        return text[: m.start()] + new + text[m.end():]
    if option.how == "before":
        pos = text.rfind("\n", 0, m.start()) + 1
        return text[:pos] + new + text[pos:]
    if option.how == "after":
        if m.end() > 0 and text[m.end() - 1] == "\n":
            pos = m.end()
        else:
            nl = text.find("\n", m.end())
            pos = len(text) if nl < 0 else nl + 1
        return text[:pos] + new + text[pos:]
    raise ValueError(option.how)


def run_edit(text, edit):
    """(new text, number of the option that worked) or (None, None)."""
    for i, option in enumerate(edit.options):
        out = run_option(text, option)
        if out is not None and out != text:
            return out, i
    return None, None


FEATURES = []


class Feature:
    def __init__(self, id, desc, core, edits, overlay=None, special=None):
        self.id, self.desc, self.core, self.edits = id, desc, core, edits
        self.overlay, self.special = overlay, special


def feature(*a, **k):
    FEATURES.append(Feature(*a, **k))


# ---------------------------------------------------------------- 1. Gemini translation (the usual translator's first line)
feature(
    "translate", "Gemini translation (LyricTranslate.translateLines)", True,
    [Edit(J + "data/api/LyricTranslate.kt", [Option(
        "after",
        r"^[ \t]*fun translateLines\(\s*(\w+): List<String>,\s*(\w+): String\s*\)[^{\n]*\{[ \t]*\n",
        lambda m: f"        com.music.spotui.gemini.LyricsAi.translateHook({m.group(1)}, {m.group(2)})?.let {{ return it }}\n",
    )])],
    overlay="core",
)

# ---------------------------------------------------------------- 2. translate again, AI pronunciation switch (LyricsViewModel)
VM_METHODS = '''    // Myspoti AI: Gemini translation styles and pronunciation.

    /** The "AI pronunciation" switch of the lyrics menus: this song only, and only when asked for by hand. */
    fun setAiPronunciation(on: Boolean) {
        val ctx = com.music.spotui.di.Graph.app
        if (!on) {
            com.music.spotui.gemini.LyricsAi.setPronunciationEnabled(false)
            // The romanization line goes back to what it was before the readings took it over.
            com.music.spotui.gemini.LyricsAi.romanizationBefore?.let { com.music.spotui.data.preferences.LyricsRomanizationPref.set(ctx, it) }
            com.music.spotui.gemini.LyricsAi.romanizationBefore = null
            return
        }
        if (!com.music.spotui.gemini.GeminiKeyStore.has()) {
            com.music.spotui.gemini.LyricsAi.toast(app.myspoti.engine.R.string.gemini_error_no_key)
            return
        }
        com.music.spotui.gemini.LyricsAi.setPronunciationEnabled(true)
        // The readings are shown in the romanization line: turn that on too.
        if (com.music.spotui.gemini.LyricsAi.romanizationBefore == null) {
            com.music.spotui.gemini.LyricsAi.romanizationBefore = com.music.spotui.data.preferences.LyricsRomanizationPref.on
        }
        com.music.spotui.data.preferences.LyricsRomanizationPref.set(ctx, true)
        com.music.spotui.di.SongPlayer.currentLyricsTrack()?.let { LyricsApi.pronounceAgain(it) }
    }

    /** Translates this song again with Gemini in [style], whatever translation it had. */
    fun regenerateAi(targetLang: String, style: com.music.spotui.gemini.TranslationStyle) {
        val cur = _state.value as? State.Loaded ?: return
        if (cur.translating) return
        if (!com.music.spotui.gemini.GeminiKeyStore.has()) {
            com.music.spotui.gemini.LyricsAi.toast(app.myspoti.engine.R.string.gemini_error_no_key)
            return
        }
        com.music.spotui.gemini.LyricsAi.requestRegenerate(style)
        val reset = cur.copy(
            translations = null, translationLanguage = null, translating = false,
            translationRequested = false, showTranslation = false, translationBySource = false,
        )
        _state.value = reset
        com.music.spotui.gemini.LyricsAi.toast(app.myspoti.engine.R.string.gemini_regenerate_started)
        translate(reset, targetLang, showWhenDone = true)
    }

'''
feature(
    "regenerate", "Translate again + AI pronunciation switch (LyricsViewModel)", True,
    [
        Edit(J + "ui/viewmodel/LyricsViewModel.kt", [
            Option("before", exact("    fun toggleTranslation(targetLang: String) {\n"), VM_METHODS),
            Option("before", r"^[ \t]*fun toggleTranslation\(", VM_METHODS),
            Option("before", r"^[ \t]*private fun translate\(", VM_METHODS),
        ], name="methods"),
        # A source's own translation would otherwise answer "translate again" before Gemini is asked.
        Edit(J + "ui/viewmodel/LyricsViewModel.kt", [
            Option(
                "replace", r"cur\.lyrics\.translationsFor\((\w+)\)\?\.let \{ (\w+) ->",
                lambda m: f"(if (com.music.spotui.gemini.LyricsAi.regenerating()) null else cur.lyrics.translationsFor({m.group(1)}))?.let {{ {m.group(2)} ->",
            ),
        ], optional=True, name="skip the source's own translation"),
    ],
)

# ---------------------------------------------------------------- 3. AI pronunciation (LyricsApi)
API_METHODS = '''    // Myspoti AI: Hangul pronunciation by Gemini, put in the romanization line of the lyrics.

    /**
     * Asks Gemini, off the caller's path, for the Hangul reading of the lines of [lyrics] that have none
     * yet, and publishes the lyrics with them through [romanized] (like the romanizing extensions do).
     */
    private fun pronounceLater(track: Track, lyrics: Lyrics) {
        val lines = lyrics.lines.map { it.text }
        val wanted = lyrics.lines.indices.filter { i ->
            com.music.spotui.gemini.LyricsAi.pronounceable(lines[i]) &&
                !com.music.spotui.gemini.LyricsAi.hasHangul(lyrics.lines[i].romanization)
        }
        if (wanted.isEmpty()) return
        scope.launch {
            val outcome = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.music.spotui.gemini.LyricsAi.pronounce(track.key, lines, wanted)
                }
            }.getOrNull() ?: return@launch
            outcome.error?.let { com.music.spotui.gemini.LyricsAi.toast(it) }
            if (outcome.values.isEmpty()) return@launch
            val updated = lyrics.copy(lines = lyrics.lines.mapIndexed { i, line ->
                outcome.values[i]?.let { line.copy(romanization = it) } ?: line
            })
            if (!cache.replace(track.key, lyrics, updated)) return@launch
            LyricsDisk.write(track.key, updated)
            romanized.value = Romanized(track.key, lyrics, updated)
        }
    }

    /** Pronunciation switched on while the song is open: its lyrics, as kept, get their readings now. */
    fun pronounceAgain(track: Track) {
        (cache[track.key] as? Lyrics)?.let { pronounceLater(track, it) }
    }

'''


feature(
    "pronounce", "AI pronunciation, started by the menu switch (LyricsApi)", True,
    [
        Edit(J + "data/api/LyricsApi.kt", [
            Option("after", r"^(?:internal\s+)?object LyricsApi\b[^{\n]*\{[ \t]*\n", API_METHODS),
        ], name="methods"),
    ],
)

# ---------------------------------------------------------------- 4. the lyrics menus
feature(
    "menu", "Gemini entries in the lyrics menu (Classic)", True,
    [
        Edit(J + "ui/screens/LyricsScreen.kt", [
            # The AI entries (AI lyrics translation, AI pronunciation, AI lyrics regeneration, key) go first in the menu.
            Option("before", exact("        // Word by word (karaoke) or line by line: the app then picks the source by itself.\n"),
                   "        com.music.spotui.gemini.GeminiLyricsMenuEntries(canTranslate = canTranslate, translating = translating, onTranslate = onTranslate, onDismiss = onDismiss)\n"),
            Option("after", r"^[ \t]*DropdownMenu\(expanded = expanded, onDismissRequest = onDismiss\) \{[ \t]*\n",
                   "        com.music.spotui.gemini.GeminiLyricsMenuEntries(canTranslate = canTranslate, translating = translating, onTranslate = onTranslate, onDismiss = onDismiss)\n"),
        ], name="entries"),
        # The app's own "Translate lyrics" item is replaced by the AI one above (its label flipped to "Stop translating").
        Edit(J + "ui/screens/LyricsScreen.kt", opts("replace",
            "        if (canTranslate) DropdownMenuItem(\n"
            "            text = { Text(stringResource(if (translating) R.string.lyrics_fullscreen_translate_button_enabled_readout_value else R.string.lyrics_fullscreen_context_menu_main_page_translate_lyrics_action)) },\n"
            "            onClick = { onDismiss(); onTranslate() },\n"
            "        )\n", ""),
            optional=True, name="hide the app's own translate item"),
        Edit(J + "ui/screens/LyricsScreen.kt", [
            Option("after", exact("        if (sourcesOpen) com.music.spotui.ui.components.LyricsSourceSheet(onDismiss = { sourcesOpen = false }, vm = vm)\n"),
                   "        com.music.spotui.gemini.GeminiDialogHost(vm = vm, targetLanguage = targetLanguage)\n"),
            Option("after", r"^[ \t]*if \(sourcesOpen\)[^\n]*\n",
                   "        com.music.spotui.gemini.GeminiDialogHost(vm = vm, targetLanguage = targetLanguage)\n"),
        ], name="dialogs"),
    ],
)

feature(
    "menu-immersive", "Gemini rows in the Immersive song options", False,
    [Edit(J + "ui/immersive/sheets/ImmersiveSongOptionsSheet.kt", [
        Option("before", exact("            // Ask another provider for the lyrics (also when none were found).\n"),
               "            GeminiImmersiveRows(vm = lyricsVm, targetLanguage = target, canTranslate = lyrics != null, onDismiss = onDismiss)\n"),
        Option("before", r"^[ \t]*//[^\n]*another provider for the lyrics[^\n]*\n",
               "            GeminiImmersiveRows(vm = lyricsVm, targetLanguage = target, canTranslate = lyrics != null, onDismiss = onDismiss)\n"),
    ])],
)

# ---------------------------------------------------------------- 5. the app starts in Korean
INIT = (
    "        // Myspoti AI: the app is in Korean whatever the phone's language, until another language is picked in the settings.\n"
    "        runCatching {\n"
    "            if (com.music.spotui.data.AppLanguage.current(this) == com.music.spotui.data.AppLanguage.SYSTEM) {\n"
    '                com.music.spotui.data.AppLanguage.apply(this, "ko")\n'
    "            }\n"
    "        }\n"
)
feature(
    "init", "Korean at start (MyspotiApplication.onCreate)", True,
    [Edit(KT + "app/myspoti/MyspotiApplication.kt", [
        Option("after", exact("        super.onCreate()\n"), INIT),
        Option("after", r"^[ \t]*super\.onCreate\(\)[ \t]*\n", INIT),
    ])],
)

# ---------------------------------------------------------------- 6. own app id and name (installs next to the original)
feature(
    "app-id", "Application id", True,
    [Edit("app/build.gradle.kts", [
        Option(
            "replace",
            exact('applicationId = if (automixLab) "app.myspoti.automixlab" else if (beta) "app.myspoti" else "app.myspoti.stable"'),
            'applicationId = if (automixLab) "app.myspoti.automixlab" else if (beta) "app.myspoti" else "' + CFG["applicationId"] + '"',
        ),
        Option("replace", r'(?m)^[ \t]*applicationId[ \t]*=.*$', '        applicationId = "' + CFG["applicationId"] + '"'),
    ])],
)
feature(
    "app-label", "App name", False,
    [Edit("app/build.gradle.kts", [
        Option(
            "replace",
            exact('manifestPlaceholders["appLabel"] = if (automixLab) "Myspoti Mix Lab" else if (beta) "Myspoti Beta" else "Myspoti"'),
            'manifestPlaceholders["appLabel"] = if (automixLab) "Myspoti Mix Lab" else if (beta) "Myspoti Beta" else "' + CFG["appLabel"] + '"',
        ),
        Option("replace", r'(?m)^[ \t]*manifestPlaceholders\["appLabel"\][ \t]*=.*$',
               '        manifestPlaceholders["appLabel"] = "' + CFG["appLabel"] + '"'),
    ])],
)

# ---------------------------------------------------------------- 7. fine volume (soft; also what the workflow drops to retry)
FINE_VOLUME = '''    // Fine volume (Myspoti AI): this phone's own playback in 1 % steps, under the system's 15 steps.
    private val _fineVolume = androidx.compose.runtime.mutableIntStateOf(100)
    /** This phone's own playback level, 1..100. */
    val fineVolume: Int get() = _fineVolume.intValue
    private var fineVolumeSend: kotlinx.coroutines.Job? = null

    private fun fineVolumePrefs() = Graph.app.getSharedPreferences("myspoti_ai_volume", android.content.Context.MODE_PRIVATE)

    private fun restoreFineVolume() {
        val saved = fineVolumePrefs().getInt("fine_volume", 100).coerceIn(1, 100)
        _fineVolume.intValue = saved
        FineGain.level = saved / 100f
        if (saved < 100) scope.launch {
            // The player page may not be there yet at launch: the level is sent again a little later.
            for (wait in longArrayOf(0L, 5_000L, 15_000L)) {
                kotlinx.coroutines.delay(wait)
                applyFineVolume()
            }
        }
    }

    fun setFineVolume(percent: Int) {
        val value = percent.coerceIn(1, 100)
        _fineVolume.intValue = value
        fineVolumePrefs().edit().putInt("fine_volume", value).apply()
        applyFineVolume()
    }

    /** Puts the fine volume into the sound: the player's audio chain (own files), and the player page (Spotify's songs). */
    private fun applyFineVolume() {
        FineGain.level = fineVolume / 100f
        // Another device plays: its own volume bar is the one in use.
        if (_activeDevice.value != null) return
        fineVolumeSend?.cancel()
        fineVolumeSend = scope.launch {
            // A drag on the bar asks for many levels a second: only the last one goes out.
            kotlinx.coroutines.delay(60)
            runCatching { api.player.setVolume(fineVolume / 100.0) }.onFailure { Log.w(TAG, "fine volume failed: ${it.message}") }
        }
    }

'''
FV_DOC = '    /** Set the volume of the device that plays (volume keys, system slider, the player\'s volume bar). */\n    fun setRemoteVolume(volume: Int) {\n'
feature(
    "volume", "Fine volume (audio chain, player page, devices sheet)", False,
    [
        Edit(J + "di/LocalPlayer.kt", [
            Option("replace", exact("arrayOf(stage, EqualizerProcessor(), SpatialProcessor())"),
                   "arrayOf(stage, EqualizerProcessor(), SpatialProcessor(), FineGainProcessor())"),
            Option("replace", r"arrayOf\(([^()\n]*SpatialProcessor\(\))\)", lambda m: f"arrayOf({m.group(1)}, FineGainProcessor())"),
        ], name="audio chain"),
        Edit(J + "di/SongPlayer.kt", [
            Option("after", exact("        restoreRepeat(state)\n"), "        restoreFineVolume()\n"),
            Option("after", r"^[ \t]*restoreRepeat\(\s*\w+\s*\)[ \t]*\n", "        restoreFineVolume()\n"),
        ], name="restore at start"),
        Edit(J + "di/SongPlayer.kt", [
            Option("after", exact("        PlaybackService.setPlaybackDevice(false, remoteVolume)\n        kotlinx.coroutines.delay(PULL_BACK_SETTLE_MS)\n"),
                   "        applyFineVolume()\n"),
            Option("after", r"^[ \t]*(?:kotlinx\.coroutines\.)?delay\(PULL_BACK_SETTLE_MS\)[ \t]*\n", "        applyFineVolume()\n"),
        ], name="after pulling the song back"),
        Edit(J + "di/SongPlayer.kt", [
            Option("before", exact(FV_DOC), FINE_VOLUME),
            Option("before", r"^[ \t]*fun setRemoteVolume\(volume: Int\) \{", FINE_VOLUME),
            Option("after", r"^(?:internal\s+)?object SongPlayer\b[^{\n]*\{[ \t]*\n", FINE_VOLUME),
        ], name="state and functions"),
        Edit(J + "ui/components/PlayerSheets.kt", [
            Option("before", exact("            Spacer(Modifier.height(4.dp))\n            val list = devices\n"),
                   "            // Fine volume (Myspoti AI): one percent at a time.\n            FineVolumeRow()\n"),
            Option("before", r"^[ \t]*val list = devices\b", "            FineVolumeRow()\n"),
        ], name="devices sheet row"),
    ],
    overlay="volume",
)


# ---------------------------------------------------------------- 8. "Charts" in Browse all (soft)
# Spotify does not always list its Charts page among the browse categories; it is added to the end of the
# list when none is there. It opens like any other category (browsePage of its page uri).
CHARTS = '''    // Myspoti AI: the "Charts" category, added when Spotify's list has none.
    private suspend fun withCharts(list: List<Category>): List<Category> {
        val id = "0JQ5DAudkNjCgYMM0TZXDw"
        if (list.any { it.uri.contains(id) || it.title.contains("chart", ignoreCase = true) || it.title.contains("차트") }) return list
        val title = if (java.util.Locale.getDefault().language == "ko") "차트" else "Charts"
        return list + Category("spotify:page:" + id, title, emptyList(), "#8D67AB")
    }

    override suspend fun categories(): List<Category> = withCharts(spotifyCategories())

'''
feature(
    "charts", "Charts category in Browse all (SpotifyHome.categories)", False,
    [Edit("api-spotify/src/main/kotlin/app/myspoti/spotify/SpotifyMore.kt", [
        Option(
            "replace", r"^([ \t]*)override suspend fun categories\(\):[ \t]*List<Category>[ \t]*=",
            lambda m: CHARTS + m.group(1) + "private suspend fun spotifyCategories(): List<Category> =",
        ),
    ])],
)


# ------------------------------------------------------------------ Korean text for the strings the original lacks

def write_korean_strings(base):
    """Only names that exist in the default strings, are not translatable="false" and have no Korean yet are
    written, so a change in the original can never cause a duplicate or an unused resource."""
    table = json.loads((ROOT / "scripts" / "ko_strings.json").read_text(encoding="utf-8"))
    res = base / "app" / "src" / "main" / "res"
    name_re = re.compile(r'<string\s+name="([^"]+)"([^>]*)>')
    default = set()
    for f in (res / "values").glob("*.xml"):
        for n, attrs in name_re.findall(f.read_text(encoding="utf-8")):
            if 'translatable="false"' not in attrs:
                default.add(n)
    have = set()
    for f in (res / "values-ko").glob("*.xml"):
        have.update(n for n, _ in name_re.findall(f.read_text(encoding="utf-8")))
    # the strings of this patch set have their own Korean file
    own = set()
    own_ko = ROOT / "overlay" / "core" / "app" / "src" / "main" / "res" / "values-ko"
    for f in own_ko.glob("*.xml"):
        own.update(n for n, _ in name_re.findall(f.read_text(encoding="utf-8")))
    names = sorted(n for n in table if n in default and n not in have and n not in own)
    lines = ['<?xml version="1.0" encoding="utf-8"?>', '<resources xmlns:tools="http://schemas.android.com/tools">']
    for n in names:
        v = table[n].replace("&", "&amp;").replace("&amp;amp;", "&amp;").replace("<", "&lt;")
        lines.append(f'    <string name="{n}">{v}</string>')
    lines.append("</resources>")
    (res / "values-ko").mkdir(parents=True, exist_ok=True)
    (res / "values-ko" / "ai_ko_strings.xml").write_text("\n".join(lines) + "\n", encoding="utf-8")
    return len(names), len(default - have)


# ------------------------------------------------------------------ main

def copy_overlay(name, base):
    src_root = ROOT / "overlay" / name
    count = 0
    for src in sorted(src_root.rglob("*")):
        if src.is_file():
            dst = base / src.relative_to(src_root)
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(src, dst)
            count += 1
    return count


def try_feature(feat, base):
    """Applies every edit of [feat] in memory. (texts, notes) when it fits, else (None, problems)."""
    texts, notes, problems = {}, [], []
    for edit in feat.edits:
        target = base / edit.path
        if not target.is_file():
            if edit.optional:
                notes.append(f"{edit.name or edit.path}: file not found")
            else:
                problems.append(f"{edit.name or edit.path}: file not found ({edit.path})")
            continue
        text = texts.get(edit.path)
        if text is None:
            text = target.read_text(encoding="utf-8")
        out, which = run_edit(text, edit)
        if out is None:
            if edit.optional:
                notes.append(f"{edit.name}: not placed (left out)")
            else:
                problems.append(f"{edit.name or edit.path}: no single match in {edit.path}")
            continue
        texts[edit.path] = out
        if which:
            notes.append(f"{edit.name or edit.path}: placed with a fallback anchor ({which + 1})")
    if problems:
        return None, problems
    return texts, notes


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("checkout")
    ap.add_argument("--report")
    ap.add_argument("--without", action="append", default=[], help="feature id to leave out on purpose")
    a = ap.parse_args()
    base = Path(a.checkout).resolve() / "base"
    if not (base / "app" / "build.gradle.kts").is_file():
        print(f"{base} does not look like a Myspoti checkout (no app/build.gradle.kts)")
        return 2

    rows, core_failed, left_out = [], [], []
    pending = {}  # path -> text, edits of features that fit, in order
    applied = []
    for feat in FEATURES:
        if feat.id in a.without:
            rows.append(f"- ⏭️ **{feat.id}**: {feat.desc}: left out on purpose")
            left_out.append(feat.id)
            continue
        # a feature sees the files as earlier features left them
        saved = {}
        for p, t in pending.items():
            saved[p] = (base / p).read_text(encoding="utf-8")
            (base / p).write_text(t, encoding="utf-8")
        texts, info = try_feature(feat, base)
        for p, t in saved.items():
            (base / p).write_text(t, encoding="utf-8")
        if texts is None:
            mark = "❌" if feat.core else "⚠️"
            rows.append(f"- {mark} **{feat.id}**: {feat.desc}: not placed")
            rows += [f"    - {x}" for x in info]
            (core_failed if feat.core else left_out).append(feat.id)
            continue
        pending.update(texts)
        applied.append(feat)
        rows.append(f"- ✅ **{feat.id}**: {feat.desc}")
        rows += [f"    - {x}" for x in info]

    # Korean text
    ko_note = ""
    ko_ok = True
    if "ko-strings" in a.without:
        ko_ok = False
        ko_note = "left out on purpose"

    report = ["# Myspoti AI patch report", ""] + rows + [""]
    if core_failed:
        report += ["## ❌ Not placed (no release is made)", ""] + [f"- {x}" for x in core_failed] + [""]
        text = "\n".join(report)
        print(text)
        if a.report:
            Path(a.report).write_text(text, encoding="utf-8")
        print("The patch set does not fit this version of Myspoti. Nothing was changed.")
        return 2

    # everything fits: write
    for p, t in pending.items():
        (base / p).write_text(t, encoding="utf-8")
    new_files = copy_overlay("core", base)
    for feat in applied:
        if feat.overlay and feat.overlay != "core":
            new_files += copy_overlay(feat.overlay, base)
    if ko_ok:
        try:
            filled, missing = write_korean_strings(base)
            report.append(f"- ✅ **ko-strings**: {filled} Korean UI strings added (the original lacked {missing + 0})")
        except Exception as e:  # soft
            report.append(f"- ⚠️ **ko-strings**: left out ({type(e).__name__})")
            left_out.append("ko-strings")
    else:
        report.append(f"- ⏭️ **ko-strings**: {ko_note}")

    report += [""]
    report.append(f"{len(applied)} features, {new_files} new files.")
    if left_out:
        report += ["", "## ⚠️ Left out of this build", ""] + [f"- {x}" for x in left_out]
    text = "\n".join(report)
    print(text)
    if a.report:
        Path(a.report).write_text(text, encoding="utf-8")
    # for the workflow: which features are in
    Path(a.checkout, "ai-applied.txt").write_text("\n".join(f.id for f in applied) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
