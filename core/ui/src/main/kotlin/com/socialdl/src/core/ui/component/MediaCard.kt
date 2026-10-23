package com.socialdl.src.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.socialdl.src.core.common.util.toTimeString
import com.socialdl.src.core.model.DownloadTask
import com.socialdl.src.core.model.FormatKind
import com.socialdl.src.core.model.MediaItem
import com.socialdl.src.core.ui.theme.SocialDLCard
import com.socialdl.src.core.ui.theme.SocialDLOnSurface
import com.socialdl.src.core.ui.theme.SocialDLOnSurfaceVariant

/**
 * Result media card matching the YTDLnis / SocialDL mock.
 * Features full bleed preview image, gradient overlay, title, author, duration badge,
 * floating Audio/Video buttons, and inline download progress overlay.
 */
@Composable
fun MediaCard(
    item: MediaItem,
    onCardClick: () -> Unit,
    onQuickAudioDownload: () -> Unit,
    onQuickVideoDownload: () -> Unit,
    activeDownloadTask: DownloadTask? = null,
    onPauseResumeDownload: (() -> Unit)? = null,
    onCancelDownload: (() -> Unit)? = null,
    isResolving: Boolean = false,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onCardClick),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = SocialDLCard),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Background Thumbnail Image
            if (!item.thumbnailUrl.isNullOrEmpty()) {
                AsyncImage(
                    model = item.thumbnailUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(SocialDLCard)
                )
            }

            // Dark Gradient Overlay for legible text
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.75f),
                                Color.Black.copy(alpha = 0.30f),
                                Color.Black.copy(alpha = 0.90f)
                            )
                        )
                    )
            )

            // Top Header: Title, Author & Duration/Badge
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = SocialDLOnSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (!item.author.isNullOrEmpty()) {
                        Text(
                            text = item.author!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = SocialDLOnSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Duration Badge
                if (item.durationSeconds != null) {
                    Surface(
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.6f),
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Text(
                            text = item.durationSeconds.toTimeString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = SocialDLOnSurface,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            // Bottom Actions: Quick Download Audio / Video Pill Buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (item.audioFormats.isNotEmpty()) {
                    FloatingIconButton(
                        icon = Icons.Default.Audiotrack,
                        label = "Audio",
                        onClick = onQuickAudioDownload,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }

                if (item.videoFormats.isNotEmpty()) {
                    FloatingIconButton(
                        icon = Icons.Default.Videocam,
                        label = "Video",
                        onClick = onQuickVideoDownload
                    )
                } else if (item.imageFormats.isNotEmpty()) {
                    FloatingIconButton(
                        icon = Icons.Default.FileDownload,
                        label = "Image",
                        onClick = onQuickVideoDownload
                    )
                }
            }

            // Inline Download Progress Overlay (if actively downloading)
            if (activeDownloadTask != null && onPauseResumeDownload != null && onCancelDownload != null) {
                ProgressOverlayCard(
                    task = activeDownloadTask,
                    onPauseResume = onPauseResumeDownload,
                    onCancel = onCancelDownload
                )
            }

            // Loading overlay when fetching presets on-demand
            if (isResolving) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.65f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            color = Color(0xFF0D59C8),
                            modifier = Modifier.size(32.dp)
                        )
                        Text(
                            text = "Loading presets...",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}
