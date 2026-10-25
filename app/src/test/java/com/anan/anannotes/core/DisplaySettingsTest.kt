package com.anan.anannotes.core

import com.anan.anannotes.core.model.DisplaySettings
import com.anan.anannotes.core.model.ThemeMode
import com.anan.anannotes.ui.theme.NoteSkinCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplaySettingsTest {
  @Test
  fun noteWithoutOverridesInheritsCurrentGlobalTypography() {
    val global = DisplaySettings(defaultFontSizeSp = 20f, lineSpacingMultiplier = 1.5f)

    val resolved = DisplaySettingsRules.resolve(global, fontSizeSpOverride = null, lineSpacingMultiplierOverride = null)

    assertEquals(20f, resolved.fontSizeSp, 0f)
    assertEquals(1.5f, resolved.lineSpacingMultiplier, 0f)
  }

  @Test
  fun noteOverridesRemainIndependentWhenGlobalDefaultsChange() {
    val before = DisplaySettings(defaultFontSizeSp = 18f, lineSpacingMultiplier = 1.3f)
    val after = DisplaySettings(defaultFontSizeSp = 24f, lineSpacingMultiplier = 1.8f)

    val beforeResolved = DisplaySettingsRules.resolve(before, fontSizeSpOverride = 19f, lineSpacingMultiplierOverride = 1.4f)
    val afterResolved = DisplaySettingsRules.resolve(after, fontSizeSpOverride = 19f, lineSpacingMultiplierOverride = 1.4f)

    assertEquals(beforeResolved, afterResolved)
  }

  @Test
  fun displaySettingsNormalizeOutOfRangeValuesAndRejectNonFiniteValues() {
    assertEquals(14f, DisplaySettingsRules.normalizeFontSize(1f), 0f)
    assertEquals(32f, DisplaySettingsRules.normalizeFontSize(80f), 0f)
    assertEquals(18f, DisplaySettingsRules.normalizeFontSize(Float.NaN), 0f)
    assertEquals(1f, DisplaySettingsRules.normalizeLineSpacing(0.1f), 0f)
    assertEquals(2f, DisplaySettingsRules.normalizeLineSpacing(3f), 0f)
    assertEquals(1.35f, DisplaySettingsRules.normalizeLineSpacing(Float.POSITIVE_INFINITY), 0f)
    assertEquals("minimal-white", DisplaySettingsRules.normalize(DisplaySettings(defaultSkinId = "missing-skin")).defaultSkinId)
  }

  @Test
  fun catalogContainsTheCurrentElevenSelectableNoteSkins() {
    assertEquals(
      setOf(
        "minimal-white",
        "warm-paper",
        "mint-green",
        "sakura-petal-paper",
        "bamboo-shadow",
        "misty-tide",
        "osmanthus-note",
        "moonlit-sky",
        "wisteria-cloud",
        "camellia-letter",
        "southern-ginkgo",
      ),
      NoteSkinCatalog.all.map { it.id }.toSet(),
    )
    assertEquals(ThemeMode.SYSTEM, DisplaySettings().themeMode)
    assertTrue(NoteSkinCatalog.all.all { it.name.isNotBlank() })
    assertEquals("纸笺散花", NoteSkinCatalog.byId("sakura-petal-paper").name)
    assertEquals("sakura-petal-paper", DisplaySettingsRules.normalize(
      DisplaySettings(defaultSkinId = "sakura-petal-paper"),
    ).defaultSkinId)
  }

  @Test
  fun retiredSkinIdsResolveToTheirPatternedSuccessors() {
    assertEquals("misty-tide", NoteSkinCatalog.byId("deep-sea").id)
    assertEquals("moonlit-sky", NoteSkinCatalog.byId("night-ink").id)
    assertEquals(
      "misty-tide",
      DisplaySettingsRules.normalize(DisplaySettings(defaultSkinId = "deep-sea")).defaultSkinId,
    )
    assertEquals(
      "moonlit-sky",
      DisplaySettingsRules.normalize(DisplaySettings(defaultSkinId = "night-ink")).defaultSkinId,
    )
  }
}
