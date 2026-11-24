package com.theveloper.pixelplay.presentation.components

import android.view.KeyEvent
import android.view.View
import android.view.ViewParent
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import com.theveloper.pixelplay.data.sonos.session.VolumeKeyGate

/**
 * Gives the volume keys to [onKey] while a dialog (a sheet is one) is in front. A dialog is a window of its own: its keys
 * do not pass through the activity, and the system would change the phone's volume. [onKey] says whether the speaker took
 * the key ([true]); otherwise the key goes on as always. Must be called from the content of the dialog.
 */
@Composable
internal fun DialogVolumeKeys(onKey: (up: Boolean) -> Boolean) {
    val window = dialogWindowOf(LocalView.current) ?: return
    val currentOnKey = rememberUpdatedState(onKey)
    DisposableEffect(window) {
        val original = window.callback
        val gate = VolumeKeyGate { up -> currentOnKey.value(up) }
        window.callback = object : Window.Callback by original {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                val key = event.keyCode
                if (key == KeyEvent.KEYCODE_VOLUME_UP || key == KeyEvent.KEYCODE_VOLUME_DOWN) {
                    val taken = when (event.action) {
                        KeyEvent.ACTION_DOWN -> gate.down(up = key == KeyEvent.KEYCODE_VOLUME_UP)
                        KeyEvent.ACTION_UP -> gate.up()
                        else -> false
                    }
                    if (taken) return true
                }
                return original.dispatchKeyEvent(event)
            }
        }
        onDispose { window.callback = original }
    }
}

/** The window of the dialog that [view] is in, or null when it is not in one. */
private fun dialogWindowOf(view: View): Window? {
    var parent: ViewParent? = view.parent
    while (parent != null) {
        (parent as? DialogWindowProvider)?.let { return it.window }
        parent = parent.parent
    }
    return null
}
