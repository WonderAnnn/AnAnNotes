package com.anan.anannotes.feature.export

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import java.util.concurrent.atomic.AtomicLong

data class PendingExportFeedback(
  val id: Long,
  val message: String,
  val showStorageAction: Boolean = false,
)

/** Keeps terminal export feedback replayable until the UI has shown and acknowledged it. */
class ExportFeedbackInbox {
  private val sequence = AtomicLong()
  private val _pending = MutableStateFlow<List<PendingExportFeedback>>(emptyList())
  val pending: StateFlow<List<PendingExportFeedback>> = _pending.asStateFlow()
  val next: Flow<PendingExportFeedback?> = pending.map { it.firstOrNull() }

  fun post(message: String, showStorageAction: Boolean = false): PendingExportFeedback =
    PendingExportFeedback(id = sequence.incrementAndGet(), message = message, showStorageAction = showStorageAction).also { pending ->
      _pending.value = _pending.value + pending
    }

  fun acknowledge(id: Long) {
    _pending.value = _pending.value.filterNot { it.id == id }
  }
}
