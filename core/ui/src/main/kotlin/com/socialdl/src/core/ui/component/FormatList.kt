package com.socialdl.src.core.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.socialdl.src.core.model.MediaFormat
import com.socialdl.src.core.model.MediaItem
import com.socialdl.src.core.ui.theme.SocialDLIndicator
import com.socialdl.src.core.ui.theme.SocialDLOnSurface
import com.socialdl.src.core.ui.theme.SocialDLOnSurfaceDim
import com.socialdl.src.core.ui.theme.SocialDLOnSurfaceVariant
import com.socialdl.src.core.ui.theme.SocialDLSheetHeader
import com.socialdl.src.core.ui.theme.SocialDLSurfaceContainerHigh
import com.socialdl.src.core.ui.theme.SocialDLSurfaceContainerLow

/**
 * Format stream submenu (Video/Audio/Image sections) and its row composable.
 * Split out of FormatBottomSheet.kt (R-B.2) so the list markup lives with
 * [FormatChips] instead of inside the sheet file.
 */
@Composable
fun FormatSubmenuView(
    item: MediaItem,
    selectedFormat: MediaFormat?,
    onSelectFormat: (MediaFormat) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Submenu Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Format",
                    style = MaterialTheme.typography.titleLarge,
                    color = SocialDLOnSurface
                )
                Text(
                    text = "Select format",
                    style = MaterialTheme.typography.bodySmall,
                    color = SocialDLOnSurfaceVariant
                )
            }

            Button(
                onClick = onBack,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = SocialDLSheetHeader,
                    contentColor = SocialDLOnSurface
                )
            ) {
                Icon(imageVector = Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = "OK", style = MaterialTheme.typography.labelLarge)
            }
        }

        // Single scrollable stream list with Video Section then Audio Section
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .height(380.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (item.videoFormats.isNotEmpty()) {
                item {
                    Text(
                        text = "VIDEO STREAMS",
                        style = MaterialTheme.typography.labelSmall,
                        color = SocialDLIndicator,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                    )
                }
                items(item.videoFormats) { fmt ->
                    FormatItemRow(
                        format = fmt,
                        isSelected = fmt.id == selectedFormat?.id,
                        onClick = { onSelectFormat(fmt) }
                    )
                }
            }

            if (item.audioFormats.isNotEmpty()) {
                item {
                    Text(
                        text = "AUDIO STREAMS",
                        style = MaterialTheme.typography.labelSmall,
                        color = SocialDLIndicator,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                    )
                }
                items(item.audioFormats) { fmt ->
                    FormatItemRow(
                        format = fmt,
                        isSelected = fmt.id == selectedFormat?.id,
                        onClick = { onSelectFormat(fmt) }
                    )
                }
            }

            if (item.imageFormats.isNotEmpty()) {
                item {
                    Text(
                        text = "IMAGE STREAMS",
                        style = MaterialTheme.typography.labelSmall,
                        color = SocialDLIndicator,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                    )
                }
                items(item.imageFormats) { fmt ->
                    FormatItemRow(
                        format = fmt,
                        isSelected = fmt.id == selectedFormat?.id,
                        onClick = { onSelectFormat(fmt) }
                    )
                }
            }
        }
    }
}

/** A single selectable stream row with label, id and the shared container/codec/size chips. */
@Composable
fun FormatItemRow(
    format: MediaFormat,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) SocialDLSurfaceContainerHigh else SocialDLSurfaceContainerLow
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = format.label,
                        style = MaterialTheme.typography.titleSmall,
                        color = SocialDLOnSurface
                    )
                    Text(
                        text = "id: ${format.id}",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = SocialDLOnSurfaceDim
                    )
                }

                FormatChips(format)
            }

            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = SocialDLIndicator,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
