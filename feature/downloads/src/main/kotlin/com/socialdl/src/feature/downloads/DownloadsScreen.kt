package com.socialdl.src.feature.downloads

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.collect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.socialdl.src.core.common.util.Logger
import com.socialdl.src.core.common.util.MediaStorageUtils
import com.socialdl.src.core.common.util.toHumanReadableSize
import com.socialdl.src.core.database.entity.DownloadHistoryEntity
import com.socialdl.src.core.database.entity.DownloadJobEntity
import com.socialdl.src.core.model.DownloadStatus
import com.socialdl.src.core.ui.component.SocialDLToastHost
import com.socialdl.src.core.ui.component.ToastMessage
import com.socialdl.src.core.ui.component.ToastType
import com.socialdl.src.core.ui.theme.SocialDLAccent
import com.socialdl.src.core.ui.theme.SocialDLBg
import com.socialdl.src.core.ui.theme.SocialDLCard
import com.socialdl.src.core.ui.theme.SocialDLOnSurface
import com.socialdl.src.core.ui.theme.SocialDLOnSurfaceDim
import com.socialdl.src.core.ui.theme.SocialDLOnSurfaceVariant
import java.util.Locale

@Composable
fun DownloadsScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DownloadsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var toastMessage by remember { mutableStateOf<ToastMessage?>(null) }

    LaunchedEffect(Unit) {
        // One-shot messages from the VM (deletion failures, download completions)
        // surfaced as styled app toasts — same mechanism and look as Home.
        viewModel.messages.collect { msg ->
            toastMessage = ToastMessage(
                message = msg.message,
                type = if (msg.isError) ToastType.ERROR else ToastType.SUCCESS
            )
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SocialDLBg)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = SocialDLOnSurface
                    )
                }

                Text(
                    text = "Downloads",
                    style = MaterialTheme.typography.titleLarge,
                    color = SocialDLOnSurface
                )
            }

            if (uiState.activeJobs.isEmpty() && uiState.historyItems.isEmpty()) {
                // Empty State Placeholder
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudDownload,
                            contentDescription = null,
                            tint = SocialDLOnSurfaceDim,
                            modifier = Modifier.size(56.dp)
                        )
                        Text(
                            text = "No downloads yet",
                            style = MaterialTheme.typography.titleSmall,
                            color = SocialDLOnSurfaceVariant
                        )
                        Text(
                            text = "Active downloads appear on top, completed move down by time.",
                            style = MaterialTheme.typography.bodySmall,
                            color = SocialDLOnSurfaceDim
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Active Tasks Section
                    if (uiState.activeJobs.isNotEmpty()) {
                        item(key = "header_active") {
                            Text(
                                text = "Active Queue (${uiState.activeJobs.size})",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = SocialDLAccent,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }

                        items(uiState.activeJobs, key = { "active_${it.id}" }) { job ->
                            ActiveDownloadCard(
                                job = job,
                                onCancel = { viewModel.cancelTask(job.id, job.status) }
                            )
                        }
                    }

                    // Completed History Section
                    if (uiState.historyItems.isNotEmpty()) {
                        item(key = "header_completed") {
                            Text(
                                text = "Completed (${uiState.historyItems.size})",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = SocialDLOnSurface,
                                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                            )
                        }

                        items(uiState.historyItems, key = { "history_${it.id}" }) { history ->
                            CompletedDownloadCard(
                                entity = history,
                                onOpen = {
                                    try {
                                        val mimeType = MediaStorageUtils.getMimeType(history.container)
                                        val intent = Intent(Intent.ACTION_VIEW).apply {
                                            setDataAndType(Uri.parse(history.localUri), mimeType)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        context.startActivity(Intent.createChooser(intent, "Play Media").apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        })
                                    } catch (e: Exception) {
                                        Logger.e("DownloadsScreen", "Failed to open media", e)
                                        Toast.makeText(context, "No app available to open this file", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onShare = {
                                    try {
                                        val mimeType = MediaStorageUtils.getMimeType(history.container)
                                        val intent = Intent(Intent.ACTION_SEND).apply {
                                            type = mimeType
                                            putExtra(Intent.EXTRA_STREAM, Uri.parse(history.localUri))
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        context.startActivity(Intent.createChooser(intent, "Share Media").apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        })
                                    } catch (e: Exception) {
                                        Logger.e("DownloadsScreen", "Failed to share media", e)
                                        Toast.makeText(context, "No app available to share this file", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onDelete = { viewModel.deleteHistory(history) }
                            )
                        }

                    }
                }
            }
        }

        // Floating Toast (styled pill, same as Home — replaces the old system toast)
        SocialDLToastHost(
            toast = toastMessage,
            onDismiss = { toastMessage = null }
        )
    }
}

@Composable
private fun ActiveDownloadCard(
    job: DownloadJobEntity,
    onCancel: () -> Unit
) {
    val isFailed = job.status == DownloadStatus.FAILED
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isFailed) SocialDLOnSurfaceVariant.copy(alpha = 0.08f) else SocialDLCard
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = job.title,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = SocialDLOnSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${job.formatLabel} • ${job.status.name}",
                        style = MaterialTheme.typography.labelSmall,
                        color = SocialDLAccent
                    )
                }

                // Single "X" action: instant-deletion for any live phase; record
                // removal for FAILED cards (background job is already dead).
                IconButton(onClick = onCancel) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = if (isFailed) "Remove" else "Cancel",
                        tint = SocialDLOnSurfaceDim
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (isFailed) {
                // Typed, user-facing error message (DownloadError.userMessage).
                Text(
                    text = job.errorMessage ?: "Download failed",
                    style = MaterialTheme.typography.labelMedium,
                    color = SocialDLOnSurfaceDim,
                    maxLines = 2
                )
            } else {
                LinearProgressIndicator(
                    progress = { job.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(CircleShape),
                    color = SocialDLAccent,
                    trackColor = SocialDLCard.copy(alpha = 0.5f)
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val percentage = (job.progress * 100).toInt()
                    Text(
                        text = if (job.status == DownloadStatus.MUXING) "Muxing streams..."
                        else if (job.status == DownloadStatus.EXPORTING) "Exporting..."
                        else if (job.status == DownloadStatus.QUEUED) "Queued..."
                        else "$percentage%",
                        style = MaterialTheme.typography.labelSmall,
                        color = SocialDLOnSurfaceVariant
                    )
                    Text(
                        text = job.downloadedBytes.toHumanReadableSize() + (job.totalBytes?.let { " / " + it.toHumanReadableSize() } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = SocialDLOnSurfaceDim
                    )
                }
            }
        }
    }
}

@Composable
private fun CompletedDownloadCard(
    entity: DownloadHistoryEntity,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SocialDLCard),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail
            Box(
                modifier = Modifier
                    .size(width = 80.dp, height = 60.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(SocialDLBg),
                contentAlignment = Alignment.Center
            ) {
                if (!entity.thumbnailUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = entity.thumbnailUrl,
                        contentDescription = entity.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entity.title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = SocialDLOnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = (entity.author ?: entity.platform) + " • " + entity.fileSizeBytes.toHumanReadableSize(),
                    style = MaterialTheme.typography.labelSmall,
                    color = SocialDLOnSurfaceDim
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = entity.container.uppercase(Locale.ROOT),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = SocialDLAccent,
                        modifier = Modifier
                            .background(SocialDLAccent.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                    Text(
                        text = entity.formatLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = SocialDLOnSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Action Buttons
            Row {
                IconButton(onClick = onOpen, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Open",
                        tint = SocialDLOnSurface
                    )
                }
                IconButton(onClick = onShare, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Share",
                        tint = SocialDLOnSurfaceDim
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = SocialDLOnSurfaceDim
                    )
                }
            }
        }
    }
}
