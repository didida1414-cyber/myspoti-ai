// Myspoti AI patch set, built on Myspoti (https://github.com/kota-zo/myspoti), GPL-3.0.
// New file written for this patch set; see NOTICE.md at the root of the patch repository.
package com.music.spotui.gemini

/** Notes of the AI features in the app's own log: they come out with the diagnostic report (Settings). */
object AiLog {
    fun log(note: String) {
        runCatching { app.myspoti.host.android.Logbook.log("ai: $note") }
    }
}
