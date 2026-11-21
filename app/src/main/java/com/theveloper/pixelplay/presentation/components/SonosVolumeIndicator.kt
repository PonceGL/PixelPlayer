package com.theveloper.pixelplay.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.sonos.session.SonosVolumeBridge
import com.theveloper.pixelplay.data.sonos.session.SonosVolumeKeys
import com.theveloper.pixelplay.data.sonos.session.indicatorVisible

/**
 * A small pill at the top that says the volume of the speaker for a moment when the volume keys are used with one on:
 * the phone's own volume bar does not show then. It lets touches through and goes away by itself.
 */
@Composable
internal fun SonosVolumeIndicator(keys: SonosVolumeKeys, bridge: SonosVolumeBridge) {
    val visible by remember(keys) { indicatorVisible(keys.requests, SonosVolumeKeys.INDICATOR_MS) }
        .collectAsStateWithLifecycle(initialValue = false)
    val volume by bridge.volume.collectAsStateWithLifecycle(initialValue = null)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(top = 8.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        AnimatedVisibility(
            visible = visible && volume != null,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
        ) {
            val shown = volume ?: return@AnimatedVisibility
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
                shadowElevation = 6.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = when {
                            shown.muted || shown.level == 0 -> Icons.Rounded.VolumeOff
                            shown.level < LOW_LEVEL -> Icons.Rounded.VolumeDown
                            else -> Icons.Rounded.VolumeUp
                        },
                        contentDescription = stringResource(R.string.cast_volume_device),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                    LinearProgressIndicator(
                        progress = { if (shown.muted) 0f else shown.level / 100f },
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .width(120.dp),
                    )
                    Text(
                        text = if (shown.muted) "—" else shown.level.toString(),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

private const val LOW_LEVEL = 35
