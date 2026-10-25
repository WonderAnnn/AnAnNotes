package com.anan.anannotes.feature.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ExportFeedbackInboxTest {
  @Test
  fun storageActionSurvivesRecollectionUntilAcknowledged() {
    val inbox = ExportFeedbackInbox()
    val posted = inbox.post("存储空间不足", showStorageAction = true)
    assertEquals(true, inbox.pending.value.single().showStorageAction)
    assertEquals(posted, inbox.pending.value.single())
    inbox.acknowledge(posted.id)
    assertEquals(emptyList<PendingExportFeedback>(), inbox.pending.value)
  }
  @Test
  fun feedbackRemainsPendingUntilAcknowledged() {
    val inbox = ExportFeedbackInbox()
    val posted = inbox.post("导出完成")

    assertEquals(listOf(posted), inbox.pending.value)
    assertEquals(listOf(posted), inbox.pending.value) // A recreated collector observes the same ViewModel state.
    inbox.acknowledge(posted.id)
    assertEquals(emptyList<PendingExportFeedback>(), inbox.pending.value)
  }

  @Test
  fun acknowledgingAnOlderSnackbarCannotClearANewerExportResult() {
    val inbox = ExportFeedbackInbox()
    val previous = inbox.post("第一次导出")
    val current = inbox.post("第二次导出")

    assertNotEquals(previous.id, current.id)
    assertEquals(listOf(previous, current), inbox.pending.value)
    inbox.acknowledge(previous.id)
    assertEquals(listOf(current), inbox.pending.value)
    inbox.acknowledge(previous.id)
    assertEquals(listOf(current), inbox.pending.value)
  }

  @Test
  fun rapidExportResultsAreQueuedAndShownInOrder() {
    val inbox = ExportFeedbackInbox()
    val first = inbox.post("第一次导出")
    val second = inbox.post("第二次导出")

    assertEquals(listOf(first, second), inbox.pending.value)
    inbox.acknowledge(first.id)
    assertEquals(listOf(second), inbox.pending.value)
  }
}
