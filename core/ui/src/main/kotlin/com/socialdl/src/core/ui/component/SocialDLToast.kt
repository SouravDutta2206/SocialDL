package com.socialdl.src.core.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.socialdl.src.core.ui.theme.SocialDLSuccess
import com.socialdl.src.core.ui.theme.SocialDLToastBg
import com.socialdl.src.core.ui.theme.SocialDLOnSurface
import com.socialdl.src.core.ui.theme.SocialDLError
import com.socialdl.src.core.ui.theme.SocialDLIndicator
import kotlinx.coroutines.delay

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
                    shape = CircleShape,
                    color = SocialDLToastBg.copy(alpha = 0.95f),
                    shadowElevation = 8.dp,
                    modifier = Modifier.padding(bottom = 80.dp)
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
