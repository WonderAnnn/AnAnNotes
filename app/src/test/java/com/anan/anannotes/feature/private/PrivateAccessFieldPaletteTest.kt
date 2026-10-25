package com.anan.anannotes.feature.privatemodule

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateAccessFieldPaletteTest {
  @Test
  fun credentialSurfaceUsesMediumGraphiteInsteadOfNearBlack() {
    assertTrue(
      "The credential field surface must be lifted from the near-black palette",
      luminance(PrivateAccessFieldPalette.container) >= 0.05,
    )
  }

  @Test
  fun passwordTextAndLabelStayReadableAgainstTheFilledFieldSurface() {
    assertTrue(
      "Password text must meet WCAG AA contrast against the field surface",
      contrast(PrivateAccessFieldPalette.text, PrivateAccessFieldPalette.container) >= 4.5,
    )
    assertTrue(
      "Password label must meet WCAG AA contrast against the field surface",
      contrast(PrivateAccessFieldPalette.label, PrivateAccessFieldPalette.container) >= 4.5,
    )
  }

  private fun contrast(first: Color, second: Color): Double {
    val firstLuminance = luminance(first)
    val secondLuminance = luminance(second)
    val lighter = maxOf(firstLuminance, secondLuminance)
    val darker = minOf(firstLuminance, secondLuminance)
    return (lighter + 0.05) / (darker + 0.05)
  }

  private fun luminance(color: Color): Double {
    fun linear(channel: Float): Double =
      if (channel <= 0.04045f) channel / 12.92 else Math.pow(((channel + 0.055f) / 1.055f).toDouble(), 2.4)
    return 0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)
  }
}
