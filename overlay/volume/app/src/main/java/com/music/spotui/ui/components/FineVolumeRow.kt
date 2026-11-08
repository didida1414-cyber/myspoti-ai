// Myspoti AI patch set, built on Myspoti (https://github.com/kota-zo/myspoti), GPL-3.0.
// New file written for this patch set; see NOTICE.md at the root of the patch repository.
package com.music.spotui.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.myspoti.engine.R
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.theme.AppAccent
import com.music.spotui.ui.theme.AppText
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Fine volume for the "Listen on" sheet: one percent at a time with - and + (hold to repeat), or drag the
 * bar. It sits under the system's 15 volume steps: with a Spotify Connect device playing it moves that
 * device's volume, else this phone's own fine volume.
 */
@Composable
internal fun FineVolumeRow() {
    val remote = SongPlayer.activeDevice.value != null
    val level by rememberUpdatedState(if (remote) SongPlayer.remoteVolume else SongPlayer.fineVolume)
    val floor = if (remote) 0 else 1
    val setTo: (Int) -> Unit by rememberUpdatedState({ wanted: Int ->
        val value = wanted.coerceIn(floor, 100)
        if (value != level) {
            if (remote) SongPlayer.setRemoteVolume(value) else SongPlayer.setFineVolume(value)
        }
    })
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
    ) {
        StepButton(Icons.Default.Remove, stringResource(R.string.immersive_player_volume_down)) { setTo(level - 1) }
        Slider(
            value = level.toFloat(),
            onValueChange = { setTo(it.roundToInt()) },
            valueRange = floor.toFloat()..100f,
            colors = SliderDefaults.colors(
                thumbColor = AppAccent,
                activeTrackColor = AppAccent,
                inactiveTrackColor = Color(0xFF4A4A4A),
            ),
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        Text(
            "$level%",
            color = AppText, fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.End,
            modifier = Modifier.width(44.dp),
        )
        StepButton(Icons.Default.Add, stringResource(R.string.immersive_player_volume_up)) { setTo(level + 1) }
    }
}

/** A round button: one step on a tap, then a step every 70 ms while it stays pressed (after 0.45 s). */
@Composable
private fun StepButton(icon: ImageVector, description: String, onStep: () -> Unit) {
    val scope = rememberCoroutineScope()
    val step by rememberUpdatedState(onStep)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(48.dp).clip(CircleShape).background(Color(0xFF4A4A4A))
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    step()
                    val repeating = scope.launch {
                        delay(450)
                        while (true) {
                            step()
                            delay(70)
                        }
                    }
                    tryAwaitRelease()
                    repeating.cancel()
                })
            },
    ) {
        Icon(icon, contentDescription = description, tint = AppText, modifier = Modifier.size(26.dp))
    }
}
