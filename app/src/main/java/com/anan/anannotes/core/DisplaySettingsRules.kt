package com.anan.anannotes.core

import com.anan.anannotes.core.model.DEFAULT_FONT_SIZE_SP
import com.anan.anannotes.core.model.DEFAULT_LINE_SPACING_MULTIPLIER
import com.anan.anannotes.core.model.DisplaySettings
import com.anan.anannotes.core.model.ResolvedDisplayStyle
import com.anan.anannotes.core.model.ACTIVE_NOTE_SKIN_IDS
import com.anan.anannotes.core.model.canonicalNoteSkinId

object DisplaySettingsRules {
  const val MIN_FONT_SIZE_SP = 14f
  const val MAX_FONT_SIZE_SP = 32f
  const val MIN_LINE_SPACING_MULTIPLIER = 1f
  const val MAX_LINE_SPACING_MULTIPLIER = 2f

  fun normalizeFontSize(value: Float): Float = when {
    !value.isFinite() -> DEFAULT_FONT_SIZE_SP
    else -> value.coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP)
  }

  fun normalizeLineSpacing(value: Float): Float = when {
    !value.isFinite() -> DEFAULT_LINE_SPACING_MULTIPLIER
    else -> value.coerceIn(MIN_LINE_SPACING_MULTIPLIER, MAX_LINE_SPACING_MULTIPLIER)
  }

  fun normalize(settings: DisplaySettings): DisplaySettings = settings.copy(
    defaultFontSizeSp = normalizeFontSize(settings.defaultFontSizeSp),
    lineSpacingMultiplier = normalizeLineSpacing(settings.lineSpacingMultiplier),
    defaultSkinId = canonicalNoteSkinId(settings.defaultSkinId),
  )

  fun isApprovedSkinId(id: String): Boolean = id in ACTIVE_NOTE_SKIN_IDS

  fun resolve(
    defaults: DisplaySettings,
    fontSizeSpOverride: Float?,
    lineSpacingMultiplierOverride: Float?,
  ): ResolvedDisplayStyle = ResolvedDisplayStyle(
    fontSizeSp = normalizeFontSize(fontSizeSpOverride ?: defaults.defaultFontSizeSp),
    lineSpacingMultiplier = normalizeLineSpacing(lineSpacingMultiplierOverride ?: defaults.lineSpacingMultiplier),
  )
}
