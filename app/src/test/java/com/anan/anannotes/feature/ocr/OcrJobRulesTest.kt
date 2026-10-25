package com.anan.anannotes.feature.ocr

import com.anan.anannotes.core.model.OcrStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrJobRulesTest {
  @Test
  fun normalizesNewlineEncodingWithoutFlatteningRecognizedLines() {
    assertEquals("第一行\n  缩进第二行\nEnglish 2026", OcrJobRules.normalizeText("\uFEFF第一行\r\n  缩进第二行\rEnglish 2026\r\n"))
  }

  @Test
  fun whitespaceOnlyRecognitionIsNotSearchableText() {
    assertNull(OcrJobRules.normalizeText(" \r\n\t \n"))
  }

  @Test
  fun retryIsIdempotentAndOnlyReopensFailedOrCancelledJobs() {
    assertEquals(OcrStatus.PENDING, OcrJobRules.transition(OcrStatus.FAILED, OcrJobEvent.RETRY))
    assertEquals(OcrStatus.PENDING, OcrJobRules.transition(OcrStatus.CANCELLED, OcrJobEvent.RETRY))
    assertEquals(OcrStatus.RUNNING, OcrJobRules.transition(OcrStatus.RUNNING, OcrJobEvent.RETRY))
    assertEquals(OcrStatus.SUCCEEDED, OcrJobRules.transition(OcrStatus.SUCCEEDED, OcrJobEvent.RETRY))
  }

  @Test
  fun lateWorkerCompletionCannotOverwriteCancellationOrSuccess() {
    assertEquals(OcrStatus.CANCELLED, OcrJobRules.transition(OcrStatus.CANCELLED, OcrJobEvent.SUCCEED))
    assertEquals(OcrStatus.SUCCEEDED, OcrJobRules.transition(OcrStatus.SUCCEEDED, OcrJobEvent.FAIL))
    assertEquals(OcrStatus.SUCCEEDED, OcrJobRules.transition(OcrStatus.PENDING, OcrJobEvent.SUCCEED))
  }

  @Test
  fun onlyPendingAndInterruptedRunningJobsCanBeClaimed() {
    assertTrue(OcrJobRules.canRun(OcrStatus.PENDING))
    assertTrue(OcrJobRules.canRun(OcrStatus.RUNNING))
    assertFalse(OcrJobRules.canRun(OcrStatus.SUCCEEDED))
    assertFalse(OcrJobRules.canRun(OcrStatus.FAILED))
    assertFalse(OcrJobRules.canRun(null))
  }
}
