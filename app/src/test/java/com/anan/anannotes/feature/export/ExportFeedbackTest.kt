package com.anan.anannotes.feature.export

import org.junit.Assert.assertTrue
import org.junit.Test

class ExportFeedbackTest {
  @Test
  fun historyWarningPreservesTheSuccessfulFileSummary() {
    val message = ExportFeedback.format(ExportEvent.Completed(1, 0,
      completedFiles = listOf(ExportEvent.ItemCompleted("note", "一条笔记.png", "content://image/1", "系统相册")),
      historyFailures = 1))
    assertTrue(message.contains("已导出 1 个文件"))
    assertTrue(message.contains("一条笔记.png"))
    assertTrue(message.contains("导出记录未保存"))
  }
  @Test
  fun completeFeedbackNamesTheDestinationAndExportedFiles() {
    val message = ExportFeedback.format(
      ExportEvent.Completed(
        succeeded = 2,
        failed = 0,
        completedFiles = listOf(
          ExportEvent.ItemCompleted("n1", "学习_计划.txt", "uri-1", "学习资料"),
          ExportEvent.ItemCompleted("n2", "学习_日记.txt", "uri-2", "学习资料"),
        ),
      ),
    )

    assertTrue(message.contains("学习资料"))
    assertTrue(message.contains("学习_计划.txt"))
    assertTrue(message.contains("学习_日记.txt"))
  }

  @Test
  fun partialFeedbackShowsSuccessfulFilesAndActionableFailureCode() {
    val message = ExportFeedback.format(
      ExportEvent.Completed(
        succeeded = 1,
        failed = 1,
        completedFiles = listOf(ExportEvent.ItemCompleted("n1", "会议.txt", "uri-1", "工作")),
        failedItems = listOf(ExportEvent.ItemFailed("n2", "AN-EXP-003", "无法访问保存文件夹")),
      ),
    )

    assertTrue(message.contains("工作"))
    assertTrue(message.contains("会议.txt"))
    assertTrue(message.contains("AN-EXP-003"))
    assertTrue(message.contains("无法访问保存文件夹"))
  }
}
