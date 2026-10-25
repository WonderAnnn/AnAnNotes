package com.anan.anannotes.feature.home

internal fun homeGridColumnCount(availableWidthDp: Float, fontScale: Float): Int =
  if (availableWidthDp < 316f || fontScale >= 1.25f) 1 else 2

internal fun homeUsesCompactVerticalLayout(windowHeightDp: Float): Boolean =
  windowHeightDp < 440f
