package com.socialdl.src.core.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * SocialDL color palette — extracted from the HTML mock's Tailwind config.
 * These are raw color values; they're mapped to M3 roles in [SocialDLTheme].
 */

// ── Primary surface hierarchy ──
val SocialDLBg = Color(0xFF18171C)               // Deep charcoal background
val SocialDLCard = Color(0xFF222128)              // Card backgrounds
val SocialDLSurface = Color(0xFF292832)           // Search pill & surface elements
val SocialDLSurfaceHover = Color(0xFF33323F)      // Hover/focus state for surfaces
val SocialDLSurfaceVariant = Color(0xFF1A1920)    // Bottom sheet background
val SocialDLSurfaceContainerLow = Color(0xFF24232C)  // Input field backgrounds
val SocialDLSurfaceContainerHigh = Color(0xFF2C2B36) // Dropdown menu backgrounds

// ── Interactive elements ──
val SocialDLButton = Color(0xFF3E4559)            // Slate-blue pill buttons
val SocialDLButtonHover = Color(0xFF4A536B)       // Button hover state
val SocialDLActivePill = Color(0xFF3D4558)        // Bottom nav active pill fill

// ── Accent ──
val SocialDLAccent = Color(0xFFEF4444)            // Red branding accent
val SocialDLAccentDark = Color(0xFFDC2626)        // Darker accent variant

// ── Text ──
val SocialDLOnSurface = Color(0xFFF1F5F9)         // slate-100 — primary text
val SocialDLOnSurfaceVariant = Color(0xFF9E9CA5)  // Muted text / subtitles
val SocialDLOnSurfaceDim = Color(0xFF94A3B8)      // slate-400 — readable on SocialDLBg

// ── Status / semantic ──
val SocialDLSuccess = Color(0xFF34D399)           // emerald-400
val SocialDLWarning = Color(0xFFFBBF24)           // amber-400
val SocialDLError = Color(0xFFEF4444)             // Same as accent (red)

// ── Format badge colors ──
val SocialDLBadgeBlue = Color(0xFF2563EB)         // blue-600 — MP4/format badge
val SocialDLBadgeBlueBg = Color(0xFF172554)       // blue-950 — audio track badge bg
val SocialDLBadgeBlueText = Color(0xFF93C5FD)     // blue-300 — audio track badge text
val SocialDLBadgeRoseBg = Color(0xFF4C0519)       // rose-950 — size badge bg
val SocialDLBadgeRoseText = Color(0xFFFDA4AF)     // rose-300 — size badge text
val SocialDLBadgeCodecBg = Color(0xFF1E293B)      // slate-800 — codec badge bg

// ── Indicator ──
val SocialDLIndicator = Color(0xFF8AB4F8)         // Blue tab indicator / active underline

// ── Component-specific tokens ──
val SocialDLAccentButton = Color(0xFF0D59C8)      // FAB / primary button blue
val SocialDLSheetHeader = Color(0xFF353E54)       // Bottom-sheet header pill buttons
val SocialDLBadgeDark = Color(0xFF282732)         // Batch list index badge bg
val SocialDLToastBg = Color(0xFF1E293B)           // Toast pill background
