package com.socialdl.src.core.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.socialdl.src.core.common.util.UiMessage
import com.socialdl.src.core.ui.theme.SocialDLSuccess
import com.socialdl.src.core.ui.theme.SocialDLToastBg
import com.socialdl.src.core.ui.theme.SocialDLOnSurface
import com.socialdl.src.core.ui.theme.SocialDLError
import com.socialdl.src.core.ui.theme.SocialDLIndicator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

enum class ToastType {
    SUCCESS,
    ERROR,
    INFO
}

data class ToastMessage(
    val id: Long = System.currentTimeMillis(),
    val message: String,
    val type: ToastType = ToastType.SUCCESS,
    val durationMs: Long = 2500L
)

/**
 * Toast state holder shared by feature screens: optionally collects a [UiMessage]
 * flow from the ViewModel and maps each event onto a [ToastMessage].
 */
@Composable
fun rememberToastState(messages: Flow<UiMessage>? = null): MutableState<ToastMessage?> {
    val state = remember { mutableStateOf<ToastMessage?>(null) }
    if (messages != null) {
        LaunchedEffect(messages) {
            messages.collect { msg ->
                state.value = ToastMessage(
                    message = msg.message,
                    type = if (msg.isError) ToastType.ERROR else ToastType.SUCCESS
                )
            }
        }
    }
    return state
}

/**
 * Floating pill toast notification matching the reference UI mock.
 */
@Composable
fun SocialDLToastHost(
    toast: ToastMessage?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Keep the last toast around so AnimatedVisibility can animate it out
    // after toast becomes null (otherwise the content vanishes instantly).
    var lastToast by remember { mutableStateOf<ToastMessage?>(null) }

    LaunchedEffect(toast) {
        if (toast != null) {
            lastToast = toast
            delay(toast.durationMs)
            onDismiss()
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter
    ) {
        AnimatedVisibility(
            visible = toast != null,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 })
        ) {
            lastToast?.let { current ->
                Surface(
                    // Rounded rect (not a pill) + side insets so long wrapped
                    // messages never clip against the screen edges.
                    shape = RoundedCornerShape(24.dp),
                    color = SocialDLToastBg.copy(alpha = 0.95f),
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .padding(start = 16.dp, end = 16.dp, bottom = 80.dp)
                        // Announce toast text to screen readers instead of only drawing it.
                        .semantics { liveRegion = LiveRegionMode.Polite }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val icon = when (current.type) {
                            ToastType.SUCCESS -> Icons.Default.CheckCircle
                            ToastType.ERROR -> Icons.Default.Error
                            ToastType.INFO -> Icons.Default.Info
                        }
                        val tint = when (current.type) {
                            ToastType.SUCCESS -> SocialDLSuccess
                            ToastType.ERROR -> SocialDLError
                            ToastType.INFO -> SocialDLIndicator
                        }

                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(16.dp)
                        )

                        Text(
                            text = current.message,
                            style = MaterialTheme.typography.labelMedium,
                            color = SocialDLOnSurface
                        )
                    }
                }
            }
        }
    }
}
