package com.socialdl.src.feature.settings.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.socialdl.src.core.cookies.CAPTURABLE_PLATFORMS
import com.socialdl.src.core.model.Platform
import com.socialdl.src.core.ui.component.SheetHeader
import com.socialdl.src.core.ui.component.SocialDLModalBottomSheet
import com.socialdl.src.core.ui.theme.SocialDLAccent
import com.socialdl.src.core.ui.theme.SocialDLButton
import com.socialdl.src.core.ui.theme.SocialDLIndicator
import com.socialdl.src.core.ui.theme.SocialDLOnSurface
import com.socialdl.src.core.ui.theme.SocialDLOnSurfaceVariant
import com.socialdl.src.core.ui.theme.SocialDLSurfaceContainerHigh
import com.socialdl.src.core.ui.theme.SocialDLSurfaceContainerLow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddCookieBottomSheet(
    onDismiss: () -> Unit,
    onSignIn: (Platform) -> Unit,
    onImportCookies: (Platform) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedPlatform by remember {
        mutableStateOf(CAPTURABLE_PLATFORMS.firstOrNull() ?: Platform.YOUTUBE)
    }
    var dropdownExpanded by remember { mutableStateOf(false) }

    SocialDLModalBottomSheet(
        onDismiss = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SheetHeader(
                title = "New cookie",
                subtitle = "Choose a site to add cookies",
                titleBold = true
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = SocialDLOnSurfaceVariant
                    )
                }
            }

            Box(modifier = Modifier.fillMaxWidth()) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { dropdownExpanded = true },
                    shape = RoundedCornerShape(16.dp),
                    color = SocialDLSurfaceContainerLow
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "SITE",
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
                                text = selectedPlatform.displayName,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp
                                ),
                                color = SocialDLOnSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "Select site",
                                tint = SocialDLOnSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                DropdownMenu(
                    expanded = dropdownExpanded,
                    onDismissRequest = { dropdownExpanded = false },
                    modifier = Modifier.background(SocialDLSurfaceContainerHigh)
                ) {
                    CAPTURABLE_PLATFORMS.forEach { platform ->
                        val isSelected = platform == selectedPlatform
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = platform.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isSelected) SocialDLIndicator else SocialDLOnSurface,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            onClick = {
                                selectedPlatform = platform
                                dropdownExpanded = false
                            }
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        onDismiss()
                        onSignIn(selectedPlatform)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SocialDLAccent,
                        contentColor = SocialDLOnSurface
                    )
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Login,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Sign in", style = MaterialTheme.typography.labelLarge)
                }

                Button(
                    onClick = {
                        onDismiss()
                        onImportCookies(selectedPlatform)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SocialDLButton,
                        contentColor = SocialDLOnSurface
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.UploadFile,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Import cookies.txt", style = MaterialTheme.typography.labelLarge)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}
