// Myspoti AI patch set, built on Myspoti (https://github.com/kota-zo/myspoti), GPL-3.0.
// New file written for this patch set; see NOTICE.md at the root of the patch repository.
package com.music.spotui.gemini

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.myspoti.engine.R
import com.music.spotui.ui.viewmodel.LyricsViewModel

/** The two windows the lyrics menus open: the key of Gemini, and the style of a new translation. */
enum class GeminiDialog { KEY, STYLE }

/**
 * Which window is open. The menus (which close themselves when an entry is tapped) set it, and
 * a [GeminiDialogHost] that stays in the screen shows it.
 */
object GeminiUi {
    var dialog by mutableStateOf<GeminiDialog?>(null)
}

private val DialogColor = Color(0xFF282828)
private val DialogText = Color(0xFFE6E6E6)

/** Shows the window asked for in [GeminiUi]. [onFinished]: called when it closes (a sheet behind it can go too). */
@Composable
fun GeminiDialogHost(vm: LyricsViewModel, targetLanguage: String, onFinished: () -> Unit = {}) {
    when (GeminiUi.dialog) {
        GeminiDialog.KEY -> KeyDialog(onClose = { GeminiUi.dialog = null; onFinished() })
        GeminiDialog.STYLE -> StyleDialog(
            onPick = { style ->
                GeminiUi.dialog = null
                vm.regenerateAi(targetLanguage, style)
                onFinished()
            },
            onClose = { GeminiUi.dialog = null; onFinished() },
        )
        null -> Unit
    }
}

/**
 * The entries of the Classic lyrics menu (inside its DropdownMenu), first in the menu:
 * AI lyrics translation (a check when it is on), AI pronunciation, AI lyrics regeneration, the key.
 */
@Composable
fun GeminiLyricsMenuEntries(canTranslate: Boolean, translating: Boolean, onTranslate: () -> Unit, onDismiss: () -> Unit) {
    // The screen's own view model: the same instance (one per owner), so the menu needs no extra parameter.
    val vm: LyricsViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    var pronunciation by remember { mutableStateOf(LyricsAi.pronunciationEnabled()) }
    if (canTranslate) DropdownMenuItem(
        text = { Text(stringResource(R.string.gemini_menu_translate)) },
        trailingIcon = { if (translating) Icon(Icons.Default.Check, contentDescription = null) },
        onClick = { onDismiss(); onTranslate() },
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.gemini_menu_pronunciation)) },
        trailingIcon = { if (pronunciation) Icon(Icons.Default.Check, contentDescription = null) },
        onClick = {
            pronunciation = !pronunciation
            vm.setAiPronunciation(pronunciation)
            onDismiss()
        },
    )
    if (canTranslate) DropdownMenuItem(
        text = { Text(stringResource(R.string.gemini_menu_regenerate)) },
        onClick = { onDismiss(); GeminiUi.dialog = GeminiDialog.STYLE },
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.gemini_menu_key)) },
        onClick = { onDismiss(); GeminiUi.dialog = GeminiDialog.KEY },
    )
}

@Composable
private fun KeyDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    val stored = remember { GeminiKeyStore.has() }
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = DialogColor,
        titleContentColor = Color.White,
        textContentColor = DialogText,
        title = { Text(stringResource(R.string.gemini_key_title)) },
        text = {
            Column {
                Text(stringResource(R.string.gemini_key_help), fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.gemini_key_label)) },
                    textStyle = LocalTextStyle.current.copy(color = Color.White),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(if (stored) R.string.gemini_key_status_saved else R.string.gemini_key_status_none),
                    fontSize = 12.sp,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = input.isNotBlank(),
                onClick = {
                    val ok = GeminiKeyStore.save(input)
                    Toast.makeText(
                        context,
                        if (ok) R.string.gemini_key_saved_toast else R.string.gemini_key_not_saved_toast,
                        Toast.LENGTH_SHORT,
                    ).show()
                    onClose()
                },
            ) { Text(stringResource(R.string.gemini_key_save)) }
        },
        dismissButton = {
            Row {
                if (stored) TextButton(onClick = {
                    GeminiKeyStore.clear()
                    Toast.makeText(context, R.string.gemini_key_deleted_toast, Toast.LENGTH_SHORT).show()
                    onClose()
                }) { Text(stringResource(R.string.gemini_key_delete)) }
                TextButton(onClick = onClose) { Text(stringResource(R.string.gemini_close)) }
            }
        },
    )
}

@Composable
private fun StyleDialog(onPick: (TranslationStyle) -> Unit, onClose: () -> Unit) {
    val options = listOf(
        Triple(TranslationStyle.LITERAL, R.string.gemini_style_literal, R.string.gemini_style_literal_hint),
        Triple(TranslationStyle.BALANCED, R.string.gemini_style_balanced, R.string.gemini_style_balanced_hint),
        Triple(TranslationStyle.FREE, R.string.gemini_style_free, R.string.gemini_style_free_hint),
    )
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = DialogColor,
        titleContentColor = Color.White,
        textContentColor = DialogText,
        title = { Text(stringResource(R.string.gemini_style_title)) },
        text = {
            Column {
                for ((style, label, hint) in options) {
                    Column(Modifier.fillMaxWidth().clickable { onPick(style) }.padding(vertical = 10.dp)) {
                        Text(stringResource(label), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(stringResource(hint), fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.gemini_close)) } },
    )
}
