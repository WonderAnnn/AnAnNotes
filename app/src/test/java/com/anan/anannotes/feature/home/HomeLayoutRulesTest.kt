package com.anan.anannotes.feature.home

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeLayoutRulesTest {
  @Test
  fun narrow320DpPhoneContentUsesSingleColumn() {
    assertEquals(1, homeGridColumnCount(280f, 1f))
  }

  @Test
  fun normalPhoneContentUsesTwoColumns() {
    assertEquals(2, homeGridColumnCount(340f, 1f))
  }

  @Test
  fun largeSystemFontUsesSingleColumn() {
    assertEquals(1, homeGridColumnCount(400f, 1.25f))
  }

  @Test
  fun veryShortLandscapeWindowUsesCompactHomeLayout() {
    assertEquals(true, homeUsesCompactVerticalLayout(400f))
  }

  @Test
  fun regularCompactLandscapeHeightKeepsStandardHomeLayout() {
    assertEquals(false, homeUsesCompactVerticalLayout(480f))
  }
}
