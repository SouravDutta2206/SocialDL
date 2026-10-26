package com.socialdl.src.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.socialdl.src.core.ui.theme.SocialDLOnSurface
import com.socialdl.src.core.ui.theme.SocialDLOnSurfaceVariant
import com.socialdl.src.core.ui.theme.SocialDLSheetHeader
import com.socialdl.src.core.ui.theme.SocialDLSurfaceVariant

/**
 * Shared bottom-sheet chrome for all modal sheets (single source of truth for
 * container color, top-corner rounding, and the drag handle) — previously
 * duplicated across FormatBottomSheet and BatchDownloadBottomSheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SocialDLModalBottomSheet(
    onDismiss: () -> Unit,
    sheetState: SheetState,
    content: @Composable () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SocialDLSurfaceVariant,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.2f))
            )
        }
    ) {
        content()
    }
}

/**
 * Shared sheet header: bold-capable title + subtitle on the left, trailing
 * actions (pill button, close icon) on the right. Previously duplicated 3x.
 */
@Composable
fun SheetHeader(
    title: String,
    subtitle: String,
    titleBold: Boolean = false,
    trailing: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge
                    .copy(fontWeight = if (titleBold) FontWeight.Bold else FontWeight.Normal),
                color = SocialDLOnSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = SocialDLOnSurfaceVariant
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            content = trailing
        )
    }
}

/**
 * The shared circular primary pill (icon + bold label) used as sheet-header
 * actions ("OK", "Download"). Previously duplicated 3x.
 */
@Composable
fun SheetPillButton(
    label: String,
    icon: ImageVector? = null,
    iconContentDescription: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = SocialDLSheetHeader,
            contentColor = SocialDLOnSurface
        )
    ) {
        icon?.let {
            Icon(
                imageVector = it,
                contentDescription = iconContentDescription,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
        )
    }
}
