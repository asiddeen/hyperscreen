package dev.hyperscreen.client

import android.graphics.Color

/**
 * Theme selection modes for HyperScreen Pro Android client.
 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

/**
 * High-craft semantic color palette for HyperScreen Pro Android.
 * Adapts seamlessly between Dark and Light environments while preserving
 * high legibility, WCAG contrast compliance, and Apple/Material physical glass depth.
 */
class HyperScreenPalette(val isDark: Boolean) {
    // Root Canvas & Glass Layer
    val canvasBg = if (isDark) Color.parseColor("#0A0E1A") else Color.parseColor("#F1F5F9")
    val cardBg = if (isDark) Color.argb(246, 15, 22, 38) else Color.argb(252, 255, 255, 255)
    val cardBorder = if (isDark) Color.parseColor("#1E293B") else Color.parseColor("#E2E8F0")
    val cardShadow = if (isDark) Color.argb(110, 0, 0, 0) else Color.argb(25, 15, 23, 42)
    val cardInnerRim = if (isDark) Color.argb(22, 255, 255, 255) else Color.argb(140, 255, 255, 255)

    // Floating Streaming HUDs (High-Contrast Frosted Overlays over Mac Stream)
    val hudBg = if (isDark) Color.argb(235, 12, 17, 29) else Color.argb(242, 255, 255, 255)
    val hudBorder = if (isDark) Color.parseColor("#334155") else Color.parseColor("#CBD5E1")
    val hudShadow = if (isDark) Color.argb(130, 0, 0, 0) else Color.argb(35, 15, 23, 42)

    // Typography
    val title = if (isDark) Color.parseColor("#F8FAFC") else Color.parseColor("#0F172A")
    val subtitle = if (isDark) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7")
    val body = if (isDark) Color.parseColor("#E0F2FE") else Color.parseColor("#1E293B")
    val muted = if (isDark) Color.parseColor("#94A3B8") else Color.parseColor("#64748B")
    val caption = if (isDark) Color.parseColor("#64748B") else Color.parseColor("#94A3B8")

    // Buttons & Interactivity
    val primaryBtnBg = Color.parseColor("#0284C7")
    val primaryBtnText = Color.parseColor("#FFFFFF")
    val secondaryBtnBg = if (isDark) Color.parseColor("#1E293B") else Color.parseColor("#F8FAFC")
    val secondaryBtnBorder = if (isDark) Color.parseColor("#334155") else Color.parseColor("#CBD5E1")
    val secondaryBtnText = if (isDark) Color.parseColor("#F1F5F9") else Color.parseColor("#1E293B")
    val inactiveBtnText = if (isDark) Color.parseColor("#94A3B8") else Color.parseColor("#64748B")

    // Diagnostics & Panels
    val panelBg = if (isDark) Color.parseColor("#131B2E") else Color.parseColor("#F8FAFC")
    val panelBorder = if (isDark) Color.parseColor("#1E293B") else Color.parseColor("#E2E8F0")
    val checklistLabel = if (isDark) Color.parseColor("#94A3B8") else Color.parseColor("#64748B")

    // Status Signals & Halos
    val success = if (isDark) Color.parseColor("#34D399") else Color.parseColor("#059669")
    val successHalo = if (isDark) Color.argb(55, 52, 211, 153) else Color.argb(45, 5, 150, 105)
    val warning = if (isDark) Color.parseColor("#F59E0B") else Color.parseColor("#D97706")
    val warningHalo = if (isDark) Color.argb(55, 245, 158, 11) else Color.argb(45, 217, 119, 6)
    val warningBg = if (isDark) Color.argb(35, 245, 158, 11) else Color.argb(30, 217, 119, 6)
    val warningTitle = if (isDark) Color.parseColor("#FBBF24") else Color.parseColor("#B45309")
    val warningHint = if (isDark) Color.parseColor("#E2E8F0") else Color.parseColor("#475569")

    // Contextual Errors
    val error = if (isDark) Color.parseColor("#EF4444") else Color.parseColor("#DC2626")
    val errorBg = if (isDark) Color.argb(240, 28, 18, 24) else Color.argb(245, 254, 242, 242)
    val errorBorder = if (isDark) Color.parseColor("#EF4444") else Color.parseColor("#FCA5A5")
    val errorDiagText = if (isDark) Color.parseColor("#FCA5A5") else Color.parseColor("#B91C1C")

    // Destructive Actions
    val disconnectBg = if (isDark) Color.parseColor("#DC2626") else Color.parseColor("#EF4444")
    val cancelBg = if (isDark) Color.parseColor("#991B1B") else Color.parseColor("#FEE2E2")
    val cancelBorder = if (isDark) Color.parseColor("#EF4444") else Color.parseColor("#F87171")
    val cancelText = if (isDark) Color.parseColor("#FFFFFF") else Color.parseColor("#991B1B")

    // Modifiers & Trackpad Badges
    val activeModifierBg = Color.parseColor("#6366F1")
    val trackpadBadgeBg = Color.parseColor("#A855F7")
    val rightClickBadgeBg = Color.parseColor("#0EA5E9")
}
