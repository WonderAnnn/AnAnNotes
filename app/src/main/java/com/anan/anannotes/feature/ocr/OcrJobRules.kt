package com.anan.anannotes.feature.ocr

import com.anan.anannotes.core.model.OcrStatus

enum class OcrJobEvent { START, SUCCEED, FAIL, CANCEL, RETRY }

object OcrJobRules {
  fun canRun(status: OcrStatus?): Boolean = status == OcrStatus.PENDING || status == OcrStatus.RUNNING

  /** Stable transitions make retries and late worker callbacks harmless. */
  fun transition(status: OcrStatus?, event: OcrJobEvent): OcrStatus = when (event) {
    OcrJobEvent.START -> when (status) {
      OcrStatus.PENDING, OcrStatus.RUNNING -> OcrStatus.RUNNING
      else -> status ?: OcrStatus.NOT_REQUESTED
    }
    OcrJobEvent.SUCCEED -> if (canRun(status)) OcrStatus.SUCCEEDED else status ?: OcrStatus.NOT_REQUESTED
    OcrJobEvent.FAIL -> if (canRun(status)) OcrStatus.FAILED else status ?: OcrStatus.NOT_REQUESTED
    OcrJobEvent.CANCEL -> if (canRun(status)) OcrStatus.CANCELLED else status ?: OcrStatus.NOT_REQUESTED
    OcrJobEvent.RETRY -> when (status) {
      OcrStatus.FAILED, OcrStatus.CANCELLED -> OcrStatus.PENDING
      else -> status ?: OcrStatus.NOT_REQUESTED
    }
  }

  /** Canonicalizes platform line endings and the OCR BOM without collapsing document lines. */
  fun normalizeText(value: String): String? {
    val normalized = value.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
    val lines = normalized.split('\n').toMutableList()
    while (lines.firstOrNull()?.isBlank() == true) lines.removeAt(0)
    while (lines.lastOrNull()?.isBlank() == true) lines.removeAt(lines.lastIndex)
    if (lines.isEmpty() || lines.all(String::isBlank)) return null
    return lines.joinToString("\n")
  }
}
