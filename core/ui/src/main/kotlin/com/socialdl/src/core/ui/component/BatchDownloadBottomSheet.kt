package com.socialdl.src.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

import com.socialdl.src.core.common.util.toTimeString
import com.socialdl.src.core.model.MediaFormat
import com.socialdl.src.core.model.MediaItem
import com.socialdl.src.core.model.uiKey



import com.socialdl.src.core.ui.theme.SocialDLBadgeDark


import com.socialdl.src.core.ui.theme.SocialDLIndicator
import com.socialdl.src.core.ui.theme.SocialDLOnSurface
import com.socialdl.src.core.ui.theme.SocialDLOnSurfaceDim
import com.socialdl.src.core.ui.theme.SocialDLOnSurfaceVariant
import com.socialdl.src.core.ui.theme.SocialDLSurfaceContainerHigh
import com.socialdl.src.core.ui.theme.SocialDLSurfaceContainerLow
import com.socialdl.src.core.ui.theme.SocialDLSurfaceVariant

/**
 * Batch download flyout modal / bottom sheet matching the YTDLnis "Edit selected" design.
 * Features:
 * - Header with "Edit" title and "OK" pill button
 * - Sub-header showing dynamic "X Selected" count and select/deselect all actions
 * - Video list with individual checkboxes, index badge, thumbnail, duration chip, quality chips, and video icon
 * - Defaults to highest available preset for all selected videos
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchDownloadBottomSheet(
    items: List<MediaItem>,
    onDismiss: () -> Unit,
    onConfirmDownload: (List<Pair<MediaItem, MediaFormat>>) -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
) {
    // Default selection: all items selected (shared uiKey formula)
    var selectedUrls by remember(items) {
        mutableStateOf(items.mapIndexed { index, it -> it.uiKey(fallbackIndex = index) }.toSet())
    }

    val isAllSelected = selectedUrls.size == items.size && items.isNotEmpty()

    fun toggleItem(key: String) {
        selectedUrls = if (selectedUrls.contains(key)) {
            selectedUrls - key
        } else {
            selectedUrls + key
        }
    }

    fun toggleSelectAll() {
        selectedUrls = if (isAllSelected) {
            emptySet()
        } else {
            items.mapIndexed { index, it -> it.uiKey(fallbackIndex = index) }.toSet()
        }
    }

    fun handleConfirm() {
        val selectedPairs = items.mapIndexedNotNull { index, item ->
            val key = item.uiKey(fallbackIndex = index)
            if (selectedUrls.contains(key)) {
                val bestFormat = item.videoFormats.firstOrNull()
                    ?: item.imageFormats.firstOrNull()
                    ?: item.audioFormats.firstOrNull()
                bestFormat?.let { Pair(item, it) }
            } else {
                null
            }
        }
        if (selectedPairs.isNotEmpty()) {
            onConfirmDownload(selectedPairs)
        }
    }

    SocialDLModalBottomSheet(
        onDismiss = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header: "Edit / Edit selected" and "OK" Pill Button
            SheetHeader(
                title = "Edit",
                subtitle = "Edit selected",
                titleBold = true
            ) {
                SheetPillButton(
                    label = "OK",
                    icon = Icons.Default.Check,
                    iconContentDescription = "Confirm",
                    enabled = selectedUrls.isNotEmpty(),
                    onClick = ::handleConfirm
                )
            }

            // Subheader: "X Selected" count and Action Icons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${selectedUrls.size} Selected",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = SocialDLOnSurface
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = ::toggleSelectAll) {
                        Icon(
                            imageVector = Icons.Default.FormatListNumbered,
                            contentDescription = if (isAllSelected) "Deselect all" else "Select all",
                            tint = if (isAllSelected) SocialDLIndicator else SocialDLOnSurfaceVariant
                        )
                    }

                    IconButton(onClick = ::handleConfirm) {
                        Icon(
                            imageVector = Icons.Default.ArrowDownward,
                            contentDescription = "Download selected",
                            tint = SocialDLOnSurfaceVariant
                        )
                    }
                }
            }

            // Scrollable List of Video Items
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(
                    items = items,
                    key = { index, item -> item.uiKey(fallbackIndex = index) }
                ) { index, item ->
                    val key = item.uiKey(fallbackIndex = index)
                    val isChecked = selectedUrls.contains(key)
                    val defaultPreset = item.videoFormats.firstOrNull()
                        ?: item.imageFormats.firstOrNull()
                        ?: item.audioFormats.firstOrNull()

                    BatchItemRow(
                        index = item.carouselIndex ?: (index + 1),
                        item = item,
                        preset = defaultPreset,
                        isSelected = isChecked,
                        onToggle = { toggleItem(key) }
                    )
                }
            }
        }
    }
}

@Composable
private fun BatchItemRow(
    index: Int,
    item: MediaItem,
    preset: MediaFormat?,
    isSelected: Boolean,
    onToggle: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onToggle),
        shape = RoundedCornerShape(14.dp),
        color = if (isSelected) SocialDLSurfaceContainerHigh else SocialDLSurfaceContainerLow
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Checkbox
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggle() },
                colors = CheckboxDefaults.colors(
                    checkedColor = SocialDLIndicator,
                    uncheckedColor = SocialDLOnSurfaceDim,
                    checkmarkColor = Color.White
                )
            )

            // Index Badge (1, 2, 3...)
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = SocialDLBadgeDark,
                modifier = Modifier.size(24.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = index.toString(),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = SocialDLOnSurfaceVariant
                    )
                }
            }

            // Thumbnail with Duration Chip
            Box(
                modifier = Modifier
                    .size(width = 72.dp, height = 44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.4f))
            ) {
                if (!item.thumbnailUrl.isNullOrEmpty()) {
                    AsyncImage(
                        model = item.thumbnailUrl,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(width = 72.dp, height = 44.dp)
                    )
                }

                val duration = item.durationSeconds
                if (duration != null && duration > 0) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color.Black.copy(alpha = 0.75f),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(2.dp)
                    ) {
                        Text(
                            text = duration.toTimeString(),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
            }

            // Title & Chips Column
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    ),
                    color = SocialDLOnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Size Chip or AUTO indicator, preset label chip, codec chip (shared markup)
                    preset?.let {
                        FormatChips(
                            format = it,
                            chips = listOf(FormatChip.SIZE, FormatChip.LABEL, FormatChip.CODEC)
                        )
                    }
                }
            }

            // Right Type Icon (Video/Image/Audio)
            Icon(
                imageVector = when {
                    item.videoFormats.isNotEmpty() -> Icons.Default.Videocam
                    item.imageFormats.isNotEmpty() -> Icons.Default.Image
                    else -> Icons.Default.Audiotrack
                },
                contentDescription = null,
                tint = SocialDLOnSurfaceVariant,
                modifier = Modifier
                    .padding(end = 4.dp)
                    .size(20.dp)
            )
        }
    }
}
