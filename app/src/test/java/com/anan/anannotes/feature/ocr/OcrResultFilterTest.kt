package com.anan.anannotes.feature.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrResultFilterTest {
  @Test
  fun removesCompactStatusBarLineButPreservesBodyAndMixedIdentifier() {
    val result = OcrResultFilter.clean(
      lines = listOf(
        OcrLineEvidence("12:17 5G 60%", confidence = 96f, top = 5, bottom = 29),
        OcrLineEvidence("安安简记的正文", confidence = 91f, top = 120, bottom = 158),
        OcrLineEvidence("Order ID: A7B9-2046", confidence = 84f, top = 210, bottom = 248),
      ),
      imageHeight = 1000,
    )

    assertEquals("安安简记的正文\nOrder ID: A7B9-2046", result)
  }

  @Test
  fun keepsShortValidMixedContentEvenAtTheTopEdge() {
    val result = OcrResultFilter.clean(
      lines = listOf(
        OcrLineEvidence("A7", confidence = 61f, top = 8, bottom = 32),
        OcrLineEvidence("待办", confidence = 54f, top = 42, bottom = 70),
      ),
      imageHeight = 1000,
    )

    assertEquals("A7\n待办", result)
  }

  @Test
  fun keepsHighConfidenceEdgeTextWithOnlyPartialStatusBarPattern() {
    val result = OcrResultFilter.clean(
      lines = listOf(OcrLineEvidence("12:17 5G", confidence = 96f, top = 5, bottom = 29)),
      imageHeight = 1000,
    )

    assertEquals("12:17 5G", result)
  }

  @Test
  fun filtersLowConfidenceEdgeLineWithPartialStatusBarPattern() {
    val result = OcrResultFilter.clean(
      lines = listOf(OcrLineEvidence("12:17 5G", confidence = 24f, top = 5, bottom = 29)),
      imageHeight = 1000,
    )

    assertEquals("", result)
  }

  @Test
  fun removesOnlyTinyLowConfidenceSymbolNoiseAtImageEdge() {
    val result = OcrResultFilter.clean(
      lines = listOf(
        OcrLineEvidence("| ~", confidence = 8f, top = 1, bottom = 12),
        OcrLineEvidence("计划 2026", confidence = 42f, top = 130, bottom = 165),
      ),
      imageHeight = 1000,
    )

    assertEquals("计划 2026", result)
  }

  @Test
  fun choosesEnhancedCandidateWhenConfidenceImprovesWithoutLosingContent() {
    val original = OcrTextCandidate(
      lines = listOf(
        OcrLineEvidence("安安简记混合内容", confidence = 48f, top = 120, bottom = 158),
        OcrLineEvidence("Order ID: A7B9-2046", confidence = 51f, top = 210, bottom = 248),
      ),
      meanConfidence = 50f,
    )
    val enhanced = OcrTextCandidate(
      lines = listOf(
        OcrLineEvidence("安安简记混合内容", confidence = 78f, top = 120, bottom = 158),
        OcrLineEvidence("Order ID: A7B9-2046", confidence = 81f, top = 210, bottom = 248),
      ),
      meanConfidence = 80f,
    )

    assertEquals(enhanced, OcrCandidateSelector.choose(original, enhanced, imageHeight = 1000))
  }

  @Test
  fun acceptsHigherConfidenceCandidateWithSmallMixedTextCorrections() {
    val original = OcrTextCandidate(
      lines = listOf(
        OcrLineEvidence("安安简记混合内容", confidence = 48f, top = 120, bottom = 158),
        OcrLineEvidence("Order ID: A7B9-2046", confidence = 51f, top = 210, bottom = 248),
      ),
      meanConfidence = 50f,
    )
    val enhanced = OcrTextCandidate(
      lines = listOf(
        OcrLineEvidence("安安简记混合信息", confidence = 78f, top = 120, bottom = 158),
        OcrLineEvidence("Order ID: A7B9-2048", confidence = 81f, top = 210, bottom = 248),
      ),
      meanConfidence = 80f,
    )

    assertEquals(enhanced, OcrCandidateSelector.choose(original, enhanced, imageHeight = 1000))
  }

  @Test
  fun keepsOriginalCandidateWhenEnhancedPassDropsMostOfTheText() {
    val original = OcrTextCandidate(
      lines = listOf(
        OcrLineEvidence("第一行混合正文", confidence = 48f, top = 120, bottom = 158),
        OcrLineEvidence("第二行 Order ID A7B9", confidence = 51f, top = 210, bottom = 248),
      ),
      meanConfidence = 50f,
    )
    val truncatedEnhanced = OcrTextCandidate(
      lines = listOf(OcrLineEvidence("第一行", confidence = 96f, top = 120, bottom = 158)),
      meanConfidence = 96f,
    )

    assertEquals(original, OcrCandidateSelector.choose(original, truncatedEnhanced, imageHeight = 1000))
  }

  @Test
  fun keepsOriginalWhenEnhancedCandidateHasSimilarLengthButDifferentText() {
    val original = OcrTextCandidate(
      lines = listOf(
        OcrLineEvidence("安安简记混合内容", confidence = 48f, top = 120, bottom = 158),
        OcrLineEvidence("Order ID: A7B9-2046", confidence = 51f, top = 210, bottom = 248),
      ),
      meanConfidence = 50f,
    )
    val unrelatedEnhanced = OcrTextCandidate(
      lines = listOf(
        OcrLineEvidence("天气预报账户密码样例", confidence = 96f, top = 120, bottom = 158),
        OcrLineEvidence("Invoice NO: C9D4-5182", confidence = 98f, top = 210, bottom = 248),
      ),
      meanConfidence = 97f,
    )

    assertEquals(original, OcrCandidateSelector.choose(original, unrelatedEnhanced, imageHeight = 1000))
  }

  @Test
  fun comparesLinePositionsProportionallyWhenCorrectedBitmapHasExpandedHeight() {
    val original = OcrTextCandidate(
      lines = listOf(
        OcrLineEvidence("第一行混合正文", confidence = 48f, top = 120, bottom = 158),
        OcrLineEvidence("Order ID: A7B9-2046", confidence = 51f, top = 210, bottom = 248),
      ),
      meanConfidence = 50f,
    )
    val corrected = OcrTextCandidate(
      lines = listOf(
        OcrLineEvidence("第一行混合正文", confidence = 78f, top = 135, bottom = 178),
        OcrLineEvidence("Order ID: A7B9-2046", confidence = 81f, top = 237, bottom = 280),
      ),
      meanConfidence = 80f,
    )

    assertEquals(
      corrected,
      OcrCandidateSelector.choose(
        original = original,
        originalImageHeight = 1000,
        enhanced = corrected,
        enhancedImageHeight = 1120,
      ),
    )
  }
}
