package com.socialdl.src.feature.home

import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.socialdl.src.core.model.FormatKind
import com.socialdl.src.core.model.MediaItem
import com.socialdl.src.core.ui.component.BatchDownloadBottomSheet
import com.socialdl.src.core.ui.component.FormatBottomSheet
import com.socialdl.src.core.ui.component.MediaCard
import com.socialdl.src.core.ui.component.PillSearchBar
import com.socialdl.src.core.ui.component.SocialDLToastHost
import com.socialdl.src.core.ui.component.ToastMessage
import com.socialdl.src.core.ui.theme.SocialDLAccent
import com.socialdl.src.core.ui.theme.SocialDLBg
import com.socialdl.src.core.ui.theme.SocialDLOnSurface
import com.socialdl.src.core.ui.theme.SocialDLOnSurfaceVariant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val resolvingItemId by viewModel.resolvingItemId.collectAsStateWithLifecycle()
    var toastMessage by remember { mutableStateOf<ToastMessage?>(null) }
    var selectedItemForSheet by remember { mutableStateOf<MediaItem?>(null) }
    var sheetInitialTab by remember { mutableStateOf(FormatKind.VIDEO) }
    var showBatchSheet by remember { mutableStateOf(false) }

    fun handleOpenSheet(item: MediaItem, initialTab: FormatKind) {
        if (item.isPlaceholder) {
            viewModel.resolveItemFormats(item) { resolvedItem ->
                sheetInitialTab = initialTab
                selectedItemForSheet = resolvedItem
            }
        } else {
            sheetInitialTab = initialTab
            selectedItemForSheet = item
        }
    }

    LaunchedEffect(uiState.infoMessage) {
        uiState.infoMessage?.let { msg ->
            toastMessage = ToastMessage(message = msg)
            viewModel.clearInfoMessage()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SocialDLBg)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // App Branding Header matching mock (Social in Red Accent, DL in Slate-200)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Social",
                        style = MaterialTheme.typography.displayMedium,
                        color = SocialDLAccent
                    )
                    Text(
                        text = "DL",
                        style = MaterialTheme.typography.displayMedium,
                        color = SocialDLOnSurface
                    )
                }

                IconButton(onClick = onNavigateToSettings) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Settings",
                        tint = SocialDLOnSurfaceVariant
                    )
                }
            }

            // Search Bar
            PillSearchBar(
                query = uiState.query,
                onQueryChange = viewModel::onQueryChange,
                onSearch = {
                    viewModel.submitQuery(uiState.query)
                },
                isLoading = uiState.isLoading
            )

            // Result Cards List or Empty State
            if (uiState.mediaItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = if (uiState.isLoading) "Fetching media details..." else "No media loaded",
                            style = MaterialTheme.typography.titleMedium,
                            color = SocialDLOnSurface
                        )
                        Text(
                            text = if (uiState.isLoading) "Resolving audio and video formats" else "Paste a video link above to fetch formats and download",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SocialDLOnSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    itemsIndexed(
                        items = uiState.mediaItems,
                        key = { index, item -> "${item.sourceUrl}_${item.carouselIndex ?: index}" }
                    ) { _, item ->
                        MediaCard(
                            item = item,
                            isResolving = resolvingItemId == item.sourceUrl,
                            onCardClick = {
                                handleOpenSheet(item, FormatKind.VIDEO)
                            },
                            onQuickAudioDownload = {
                                handleOpenSheet(item, FormatKind.AUDIO)
                            },
                            onQuickVideoDownload = {
                                handleOpenSheet(item, FormatKind.VIDEO)
                            }
                        )
                    }
                }
            }
        }

        // Format Selection Bottom Sheet
        selectedItemForSheet?.let { item ->
            FormatBottomSheet(
                item = item,
                initialTab = sheetInitialTab,
                onDismiss = { selectedItemForSheet = null },
                onConfirmDownload = { format, container, title, author ->
                    selectedItemForSheet = null
                    viewModel.startDownload(item, format, container, title, author)
                    toastMessage = ToastMessage(message = "Download queued: $title (${format.label})")
                }
            )
        }

        // Floating Action Buttons (Clipboard & Download all) matching YTDLnis screenshot
        if (uiState.mediaItems.size > 1) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Clipboard Button (Square shape)
                Surface(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        val clipText = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()
                        if (!clipText.isNullOrBlank()) {
                            viewModel.onQueryChange(clipText)
                            viewModel.submitQuery(clipText)
                        }
                    },
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF0D59C8),
                    shadowElevation = 6.dp,
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.ContentPaste,
                            contentDescription = "Paste and fetch",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                // Download all Pill Button
                Button(
                    onClick = { showBatchSheet = true },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF0D59C8),
                        contentColor = Color.White
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp),
                    modifier = Modifier.height(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowDownward,
                        contentDescription = "Download all",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Download all",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    )
                }
            }
        }

        // Batch Download Bottom Sheet
        if (showBatchSheet && uiState.mediaItems.isNotEmpty()) {
            BatchDownloadBottomSheet(
                items = uiState.mediaItems,
                onDismiss = { showBatchSheet = false },
                onConfirmDownload = { selectedPairs ->
                    showBatchSheet = false
                    viewModel.startBatchDownload(selectedPairs)
                    toastMessage = ToastMessage(message = "Queued ${selectedPairs.size} downloads to queue")
                }
            )
        }

        // Floating Toast
        SocialDLToastHost(
            toast = toastMessage,
            onDismiss = { toastMessage = null }
        )
    }
}
