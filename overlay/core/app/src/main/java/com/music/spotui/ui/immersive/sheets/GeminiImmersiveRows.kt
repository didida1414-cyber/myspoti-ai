// Myspoti AI patch set, built on Myspoti (https://github.com/kota-zo/myspoti), GPL-3.0.
// New file written for this patch set; see NOTICE.md at the root of the patch repository.
package com.music.spotui.ui.immersive.sheets

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import app.myspoti.engine.R
import com.music.spotui.gemini.GeminiDialog
import com.music.spotui.gemini.GeminiDialogHost
import com.music.spotui.gemini.GeminiUi
import com.music.spotui.gemini.LyricsAi
import com.music.spotui.ui.viewmodel.LyricsViewModel

/**
 * The Gemini rows of the Immersive song options sheet, under the lyrics' own entries. The windows they
 * open sit on top of the sheet, which goes away when they are done.
 */
@Composable
fun GeminiImmersiveRows(vm: LyricsViewModel, targetLanguage: String, canTranslate: Boolean, onDismiss: () -> Unit) {
    var pronunciation by remember { mutableStateOf(LyricsAi.pronunciationEnabled()) }
    ImmersiveSheetRow(
        Icons.Rounded.AutoAwesome,
        stringResource(R.string.gemini_menu_pronunciation),
        tint = if (pronunciation) LocalImmersiveSheetTint.current.accent else null,
    ) {
        pronunciation = !pronunciation
        vm.setAiPronunciation(pronunciation)
        onDismiss()
    }
    if (canTranslate) {
        ImmersiveSheetRow(Icons.Rounded.Refresh, stringResource(R.string.gemini_menu_regenerate)) {
            GeminiUi.dialog = GeminiDialog.STYLE
        }
    }
    ImmersiveSheetRow(Icons.Rounded.Key, stringResource(R.string.gemini_menu_key)) {
        GeminiUi.dialog = GeminiDialog.KEY
    }
    GeminiDialogHost(vm = vm, targetLanguage = targetLanguage, onFinished = onDismiss)
}
