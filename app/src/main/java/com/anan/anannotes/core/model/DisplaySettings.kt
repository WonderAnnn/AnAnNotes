package com.anan.anannotes.core.model

const val DEFAULT_FONT_SIZE_SP = 18f
const val DEFAULT_LINE_SPACING_MULTIPLIER = 1.35f

enum class ThemeMode {
  SYSTEM,
  LIGHT,
  DARK,
}

data class DisplaySettings(
  val defaultFontSizeSp: Float = DEFAULT_FONT_SIZE_SP,
  val lineSpacingMultiplier: Float = DEFAULT_LINE_SPACING_MULTIPLIER,
  val themeMode: ThemeMode = ThemeMode.SYSTEM,
  val defaultSkinId: String = DEFAULT_NOTE_SKIN_ID,
)

data class ResolvedDisplayStyle(
  val fontSizeSp: Float,
  val lineSpacingMultiplier: Float,
)
