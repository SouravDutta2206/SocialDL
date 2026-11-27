package com.socialdl.src.feature.settings.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.socialdl.src.core.ui.component.SocialDLToastHost
import com.socialdl.src.core.ui.component.ToastMessage
import com.socialdl.src.core.ui.theme.SocialDLAccent
import com.socialdl.src.core.ui.theme.SocialDLBg
import com.socialdl.src.core.ui.theme.SocialDLCard
import com.socialdl.src.core.ui.theme.SocialDLOnSurface
import com.socialdl.src.core.ui.theme.SocialDLOnSurfaceDim
import com.socialdl.src.core.ui.theme.SocialDLOnSurfaceVariant
import com.socialdl.src.core.ui.theme.SocialDLSurface
import kotlin.math.roundToInt

/**
 * Shared scaffold for the Settings root screen and its category subpages.
 */
@Composable
fun SettingsScreenScaffold(
    title: String,
    onNavigateBack: () -> Unit,
    isRoot: Boolean = false,
    toast: ToastMessage? = null,
    onDismissToast: () -> Unit = {},
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SocialDLBg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onNavigateBack, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = SocialDLOnSurface
                    )
                }
            }

            Text(
                text = title,
                style = if (isRoot) {
                    MaterialTheme.typography.displayLarge
                } else {
                    MaterialTheme.typography.displayMedium
                },
                color = SocialDLOnSurface,
                modifier = Modifier.padding(vertical = 12.dp)
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content
            )
        }

        SocialDLToastHost(
            toast = toast,
            onDismiss = onDismissToast
        )
    }
}

@Composable
fun SettingsSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp
        ),
        color = SocialDLOnSurfaceVariant,
        modifier = modifier.padding(start = 4.dp, top = 8.dp, bottom = 12.dp)
    )
}

@Composable
fun SettingsGroupCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = SocialDLCard
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            content = content
        )
    }
}

@Composable
fun SettingsCardDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        thickness = 1.dp,
        color = Color.White.copy(alpha = 0.06f)
    )
}

@Composable
fun SettingsCategoryRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsActionRow(
        icon = icon,
        title = title,
        subtitle = subtitle,
        trailingIcon = Icons.Default.ChevronRight,
        onClick = onClick,
        modifier = modifier
    )
}

@Composable
fun SettingsActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    trailingIcon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val rowModifier = if (onClick != null) {
        modifier
            .fillMaxWidth()
            .clickable {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
    } else {
        modifier.fillMaxWidth()
    }

    Row(
        modifier = rowModifier.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(SocialDLSurface),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = SocialDLOnSurface,
                modifier = Modifier.size(20.dp)
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = SocialDLOnSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = SocialDLOnSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (trailingIcon != null) {
            Icon(
                imageVector = trailingIcon,
                contentDescription = null,
                tint = SocialDLOnSurfaceDim,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun SettingsToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    description: String? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (enabled) {
                    Modifier.clickable { onCheckedChange(!checked) }
                } else {
                    Modifier
                }
            )
            .alpha(if (enabled) 1f else 0.45f)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = SocialDLOnSurface
            )
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = SocialDLOnSurfaceVariant
                )
            }
        }

        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = SocialDLAccent,
                checkedThumbColor = SocialDLOnSurface
            )
        )
    }
}

@Composable
fun SettingsSliderRow(
    title: String,
    description: String,
    value: Int,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChangeFinished: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var sliderValue by remember(value) {
        mutableFloatStateOf(value.toFloat().coerceIn(valueRange.start, valueRange.endInclusive))
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = SocialDLOnSurface,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp)
            )
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = SocialDLSurface
            ) {
                Text(
                    text = sliderValue.roundToInt().toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = SocialDLOnSurface,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }

        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = SocialDLOnSurfaceVariant
        )

        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            valueRange = valueRange,
            steps = steps,
            onValueChangeFinished = { onValueChangeFinished(sliderValue.roundToInt()) },
            colors = SliderDefaults.colors(
                thumbColor = SocialDLAccent,
                activeTrackColor = SocialDLAccent,
                inactiveTrackColor = SocialDLSurface
            )
        )
    }
}
