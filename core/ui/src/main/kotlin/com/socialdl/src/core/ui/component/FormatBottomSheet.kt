package com.socialdl.src.core.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width


import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward

import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import com.socialdl.src.core.model.FormatKind
import com.socialdl.src.core.model.MediaContainer
import com.socialdl.src.core.model.MediaFormat
import com.socialdl.src.core.model.MediaItem
import com.socialdl.src.core.ui.theme.SocialDLBadgeBlue





import com.socialdl.src.core.ui.theme.SocialDLButton
import com.socialdl.src.core.ui.theme.SocialDLIndicator
import com.socialdl.src.core.ui.theme.SocialDLOnSurface
import com.socialdl.src.core.ui.theme.SocialDLOnSurfaceDim
import com.socialdl.src.core.ui.theme.SocialDLOnSurfaceVariant
import com.socialdl.src.core.ui.theme.SocialDLSurface
import com.socialdl.src.core.ui.theme.SocialDLSurfaceContainerHigh
import com.socialdl.src.core.ui.theme.SocialDLSurfaceContainerLow
import com.socialdl.src.core.ui.theme.SocialDLSurfaceVariant

/**
 * Format selection bottom sheet matching the reference UI mock.
 * Features:
 * - Video/Audio tab switch
 * - Editable Title and Author input fields (allowing custom strings for final muxing)
 * - Increased height container dropdown menu
 * - Quality picker card that opens format submenu
 * - Format submenu listing each individual stream with codec, resolution/bitrate, and size
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormatBottomSheet(
    item: MediaItem,
    initialTab: FormatKind = FormatKind.VIDEO,
    onDismiss: () -> Unit,
    onConfirmDownload: (selectedFormat: MediaFormat, container: String, title: String, author: String) -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
) {
    var selectedTab by remember { mutableIntStateOf(if (initialTab == FormatKind.AUDIO) 0 else 1) }
    val isAudio = selectedTab == 0

    val availableFormats = if (isAudio) item.audioFormats else item.videoFormats
    var selectedFormat by remember(selectedTab, item) {
        mutableStateOf(availableFormats.firstOrNull() ?: item.imageFormats.firstOrNull())
    }

    // Only containers that can actually be produced for the selected stream. This
    // prevents saving WebM/Opus bytes under an .mp4/.mp3 extension: a single
    // stream is never transcoded, and a mux target is chosen from the codec.
    val containerOptions = remember(selectedFormat?.id) {
        selectedFormat?.let { format ->
            MediaContainer.optionsFor(format).map { it.extension.uppercase() }
        } ?: listOf("Default")
    }
    var selectedContainer by remember(selectedFormat?.id) {
        mutableStateOf(containerOptions.first())
    }

    var customTitle by remember(item) { mutableStateOf(item.title) }
    var customAuthor by remember(item) { mutableStateOf(item.author ?: "") }

    var showFormatSubmenu by remember { mutableStateOf(false) }

    SocialDLModalBottomSheet(
        onDismiss = onDismiss,
        sheetState = sheetState
    ) {
        AnimatedContent(
            targetState = showFormatSubmenu,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "SheetViewTransition"
        ) { isSubmenu ->
            if (isSubmenu) {
                // FORMAT SUBMENU VIEW
                FormatSubmenuView(
                    item = item,
                    selectedFormat = selectedFormat,
                    activeKind = if (isAudio) FormatKind.AUDIO else FormatKind.VIDEO,
                    onSelectFormat = {
                        selectedFormat = it
                        showFormatSubmenu = false
                    },
                    onBack = { showFormatSubmenu = false }
                )
            } else {
                // MAIN DOWNLOAD VIEW
                MainDownloadSheetView(
                    item = item,
                    customTitle = customTitle,
                    onTitleChange = { customTitle = it },
                    customAuthor = customAuthor,
                    onAuthorChange = { customAuthor = it },
                    selectedTab = selectedTab,
                    onTabSelected = { selectedTab = it },
                    selectedFormat = selectedFormat,
                    containerOptions = containerOptions,
                    selectedContainer = selectedContainer,
                    onContainerSelected = { selectedContainer = it },
                    onOpenSubmenu = { showFormatSubmenu = true },
                    onClose = onDismiss,
                    onDownload = {
                        selectedFormat?.let { fmt ->
                            val finalTitle = customTitle.trim().ifEmpty { item.title }
                            val finalAuthor = customAuthor.trim().ifEmpty { item.author ?: "Unknown" }
                            onConfirmDownload(fmt, selectedContainer, finalTitle, finalAuthor)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun MainDownloadSheetView(
    item: MediaItem,
    customTitle: String,
    onTitleChange: (String) -> Unit,
    customAuthor: String,
    onAuthorChange: (String) -> Unit,
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    selectedFormat: MediaFormat?,
    containerOptions: List<String>,
    selectedContainer: String,
    onContainerSelected: (String) -> Unit,
    onOpenSubmenu: () -> Unit,
    onClose: () -> Unit,
    onDownload: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Header
        SheetHeader(
            title = "Download",
            subtitle = "Adjust download"
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SheetPillButton(
                    label = "Download",
                    icon = Icons.Default.ArrowDownward,
                    onClick = onDownload
                )

                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = SocialDLOnSurfaceVariant
                    )
                }
            }
        }

        // Audio / Video Tabs
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color.Transparent,
            contentColor = SocialDLOnSurface,
            indicator = { tabPositions ->
                TabRowDefaults.PrimaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                    color = SocialDLIndicator,
                    height = 2.dp
                )
            },
            divider = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color.White.copy(alpha = 0.1f))
                )
            }
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { onTabSelected(0) },
                text = {
                    Text(
                        text = "Audio",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selectedTab == 0) SocialDLOnSurface else SocialDLOnSurfaceVariant
                    )
                }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { onTabSelected(1) },
                text = {
                    Text(
                        text = "Video",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selectedTab == 1) SocialDLOnSurface else SocialDLOnSurfaceVariant
                    )
                }
            )
        }

        // Title Field - Editable Input Form
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = SocialDLSurfaceContainerLow
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 11.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "TITLE",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.6.sp
                    ),
                    color = SocialDLOnSurfaceVariant
                )
                BasicTextField(
                    value = customTitle,
                    onValueChange = onTitleChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = SocialDLOnSurface,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    ),
                    cursorBrush = SolidColor(SocialDLIndicator),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { innerTextField ->
                        if (customTitle.isEmpty()) {
                            Text(
                                text = "Enter custom title...",
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                                color = SocialDLOnSurfaceDim
                            )
                        }
                        innerTextField()
                    }
                )
            }
        }

        // Author & Container row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Author Box - Editable Input Form
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16.dp),
                color = SocialDLSurfaceContainerLow
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "AUTHOR",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.6.sp
                        ),
                        color = SocialDLOnSurfaceVariant
                    )
                    BasicTextField(
                        value = customAuthor,
                        onValueChange = onAuthorChange,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = SocialDLOnSurface,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        ),
                        cursorBrush = SolidColor(SocialDLIndicator),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { innerTextField ->
                            if (customAuthor.isEmpty()) {
                                Text(
                                    text = "Enter author...",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                                    color = SocialDLOnSurfaceDim
                                )
                            }
                            innerTextField()
                        }
                    )
                }
            }

            // Container Dropdown Box
            var containerMenuExpanded by remember { mutableStateOf(false) }

            Box(modifier = Modifier.weight(1f)) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { containerMenuExpanded = true },
                    shape = RoundedCornerShape(16.dp),
                    color = SocialDLSurfaceContainerLow
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 11.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "CONTAINER",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.6.sp
                            ),
                            color = SocialDLOnSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = selectedContainer,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp
                                ),
                                color = SocialDLOnSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "Select container",
                                tint = SocialDLOnSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                DropdownMenu(
                    expanded = containerMenuExpanded,
                    onDismissRequest = { containerMenuExpanded = false },
                    modifier = Modifier.background(SocialDLSurfaceContainerHigh)
                ) {
                    containerOptions.forEach { containerOption ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = containerOption,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (containerOption == selectedContainer) SocialDLIndicator else SocialDLOnSurface,
                                    fontWeight = if (containerOption == selectedContainer) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            onClick = {
                                onContainerSelected(containerOption)
                                containerMenuExpanded = false
                            }
                        )
                    }
                }
            }
        }

        // Quality Picker Card (Clicking opens format submenu)
        Text(
            text = when (selectedFormat?.kind) {
                FormatKind.AUDIO -> "Audio quality"
                FormatKind.IMAGE -> "Image quality"
                else -> "Video quality"
            },
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = SocialDLOnSurface
        )

        if (selectedFormat != null) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(onClick = onOpenSubmenu),
                shape = RoundedCornerShape(16.dp),
                color = SocialDLSurfaceContainerLow
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Format Badge (MP4/MP3)
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(SocialDLBadgeBlue),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = selectedFormat.container.uppercase(),
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                color = Color.White
                            )
                        }

                        // Quality details & chips
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = selectedFormat.label,
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = SocialDLOnSurface
                                )
                                Text(
                                    text = "id: ${selectedFormat.id}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                    color = SocialDLOnSurfaceDim
                                )
                            }

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                FormatChips(
                                    format = selectedFormat,
                                    chips = listOf(FormatChip.CODEC, FormatChip.SIZE)
                                )
                            }
                        }
                    }

                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = SocialDLOnSurfaceVariant
                    )
                }
            }
        } else {
            Text(
                text = "No streams available for this kind",
                style = MaterialTheme.typography.bodySmall,
                color = SocialDLOnSurfaceDim
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
