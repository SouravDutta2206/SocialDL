package com.socialdl.src.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.socialdl.src.core.common.util.toHumanReadableSize
import com.socialdl.src.core.model.MediaFormat
import com.socialdl.src.core.ui.theme.SocialDLBadgeBlueBg
import com.socialdl.src.core.ui.theme.SocialDLBadgeBlueText
import com.socialdl.src.core.ui.theme.SocialDLBadgeCodecBg
import com.socialdl.src.core.ui.theme.SocialDLBadgeRoseBg
import com.socialdl.src.core.ui.theme.SocialDLBadgeRoseText
import com.socialdl.src.core.ui.theme.SocialDLOnSurfaceVariant

/** Which badge chips [FormatChips] renders, in the order given. */
enum class FormatChip {
    /** Resolution/quality label chip (batch-sheet preset: "BEST QUALITY" / label before "("). */
    LABEL,
    /** Container chip (e.g. "mp4", "m4a"). */
    CONTAINER,
    /** Codec chip (monospace). */
    CODEC,
    /** Approximate size chip ("~12 MB"), or "AUTO" for byte-less placeholder formats. */
    SIZE,
}

/**
 * Shared badge-chip row for [MediaFormat] metadata.
 * Defined exactly once (R-B.2) so chip markup cannot drift between
 * [FormatBottomSheet]/[FormatItemRow]/[BatchDownloadBottomSheet].
 */
@Composable
fun FormatChips(
    format: MediaFormat,
    modifier: Modifier = Modifier,
    chips: List<FormatChip> = listOf(FormatChip.CONTAINER, FormatChip.CODEC, FormatChip.SIZE)
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        chips.forEach { chip ->
            when (chip) {
                FormatChip.LABEL -> {
                    val label = when {
                        format.isPlaceholder -> "BEST QUALITY"
                        format.label.contains("(") -> format.label.substringBefore("(")
                        else -> format.label
                    }.trim()
                    BadgeChip(
                        text = label,
                        containerColor = SocialDLBadgeBlueBg,
                        textColor = SocialDLBadgeBlueText,
                        bold = true
                    )
                }
                FormatChip.CONTAINER -> BadgeChip(
                    text = format.container,
                    containerColor = SocialDLBadgeBlueBg,
                    textColor = SocialDLBadgeBlueText
                )
                FormatChip.CODEC -> {
                    val codec = format.codec
                    if (!codec.isNullOrBlank()) {
                        BadgeChip(
                            text = codec,
                            containerColor = SocialDLBadgeCodecBg,
                            textColor = SocialDLOnSurfaceVariant,
                            monospace = true,
                            maxLines = 1
                        )
                    }
                }
                FormatChip.SIZE -> {
                    val sizeBytes = format.approxSizeBytes
                    when {
                        sizeBytes != null -> BadgeChip(
                            text = (if (format.isSizeEstimated) "~" else "") + sizeBytes.toHumanReadableSize(),
                            containerColor = SocialDLBadgeRoseBg,
                            textColor = SocialDLBadgeRoseText,
                            bold = true
                        )
                        // Placeholder formats carry no byte estimate — show the AUTO marker instead.
                        format.isPlaceholder -> BadgeChip(
                            text = "AUTO",
                            containerColor = SocialDLBadgeRoseBg,
                            textColor = SocialDLBadgeRoseText,
                            bold = true
                        )
                        else -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun BadgeChip(
    text: String,
    containerColor: Color,
    textColor: Color,
    bold: Boolean = false,
    monospace: Boolean = false,
    maxLines: Int = Int.MAX_VALUE
) {
    Surface(shape = RoundedCornerShape(4.dp), color = containerColor) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 9.sp,
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                fontFamily = if (monospace) FontFamily.Monospace else null
            ),
            color = textColor,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            maxLines = maxLines
        )
    }
}
