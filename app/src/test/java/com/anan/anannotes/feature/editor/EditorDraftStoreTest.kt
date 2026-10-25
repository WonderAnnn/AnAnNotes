package com.anan.anannotes.feature.editor

import com.anan.anannotes.core.model.BlockAlignment
import com.anan.anannotes.core.model.BlockFormatting
import com.anan.anannotes.core.model.BlockFormattingState
import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.FormattingDecodeError
import com.anan.anannotes.core.model.InlineStyle
import com.anan.anannotes.core.model.InlineStyleRange
import com.anan.anannotes.core.model.NoteBlockDraft
import com.anan.anannotes.core.model.NoteDraft
import com.anan.anannotes.core.model.OcrStatus
import com.anan.anannotes.core.model.TextBlockPresentation
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.io.RandomAccessFile
import java.io.DataOutputStream
import java.io.FileOutputStream

class EditorDraftStoreTest {
  private val directory = Files.createTempDirectory("anan-editor-drafts-").toFile()
  private val store = FileEditorDraftStore(directory)

  @After
  fun removeTemporaryDirectory() {
    directory.deleteRecursively()
  }

  @Test
  fun atomicSnapshotRoundTripsNoteMetadataAndMixedBlocks() = runBlocking {
    val snapshot = EditorDraftSnapshot(
      draftId = "c2c5caaa-6f0f-46c9-9db0-e1783f46e4d0",
      noteId = null,
      importRequestId = "dbf1d5f7-16a4-4330-93ef-43db57f79b1d",
      folderId = "folder-study",
      baseContentRevision = 0L,
      localRevision = 7L,
      savedAtMillis = 1234L,
      draft = NoteDraft(
        folderId = "folder-study",
        title = "恢复测试",
        titleManuallySet = true,
        skinId = "mint-green",
        fontSizeSpOverride = 19f,
        lineSpacingMultiplierOverride = 1.4f,
        blocks = listOf(
          NoteBlockDraft(
            type = BlockType.TEXT,
            text = "正文第一行\n正文第二行",
            formatting = BlockFormattingState.Supported(
              BlockFormatting(
                presentation = TextBlockPresentation.HEADING,
                inlineStyles = listOf(
                  InlineStyleRange(0, 2, InlineStyle.BOLD),
                  InlineStyleRange(1, 4, InlineStyle.ITALIC),
                ),
              ),
            ),
          ),
          NoteBlockDraft(
            type = BlockType.IMAGE,
            assetPath = "notes/assets/image-1.jpg",
            previewPath = "notes/previews/image-1.webp",
            widthPercent = 62.5f,
            alignment = BlockAlignment.CENTER,
            aspectRatio = 1.5f,
          ),
          NoteBlockDraft(type = BlockType.OCR_TEXT, text = "识别出的文字", ocrStatus = OcrStatus.SUCCEEDED),
        ),
      ),
    )

    store.writeAtomic(snapshot)

    assertEquals(listOf(snapshot), store.listRecoverable())
  }

  @Test
  fun versionThreeDraftRemainsReadableWithoutImportRequestId() = runBlocking {
    val draftId = "3d7f6da0-9622-4f7f-a6d0-03254405dd9f"
    val legacyFile = File(directory, "$draftId.aan-draft")
    DataOutputStream(FileOutputStream(legacyFile)).use { output ->
      output.writeInt(0x414E414E)
      output.writeInt(3)
      output.writeString(draftId)
      output.writeNullableString(null)
      output.writeString("folder-v3")
      output.writeLong(0L)
      output.writeLong(4L)
      output.writeLong(789L)
      output.writeString("folder-v3")
      output.writeString("v3 草稿")
      output.writeBoolean(false)
      output.writeString("minimal-white")
      output.writeNullableFloat(null)
      output.writeNullableFloat(null)
      output.writeInt(0)
    }

    val legacy = store.listRecoverable().single()

    assertEquals("v3 草稿", legacy.draft.title)
    assertEquals(null, legacy.importRequestId)
  }

  @Test
  fun olderCommittedRevisionCannotClearNewerDraft() = runBlocking {
    val snapshot = snapshot(localRevision = 8L)
    store.writeAtomic(snapshot)

    assertFalse(store.clearIfAtMost(snapshot.draftId, committedRevision = 7L))
    assertEquals(listOf(snapshot), store.listRecoverable())
    assertTrue(store.clearIfAtMost(snapshot.draftId, committedRevision = 8L))
    assertTrue(store.listRecoverable().isEmpty())
  }

  @Test
  fun writingSameDraftIdReplacesSnapshotWithoutCreatingDuplicates() = runBlocking {
    val first = snapshot(localRevision = 2L)
    val latest = first.copy(localRevision = 3L, savedAtMillis = 4567L)

    store.writeAtomic(first)
    store.writeAtomic(latest)

    assertEquals(listOf(latest), store.listRecoverable())
  }

  @Test
  fun unsupportedFormatIsRejectedWithoutDeletingOriginalDraft() = runBlocking {
    val snapshot = snapshot(localRevision = 5L)
    store.writeAtomic(snapshot)
    val draftFile = directory.listFiles()!!.single()
    RandomAccessFile(draftFile, "rw").use { file ->
      file.seek(4L)
      file.writeInt(99)
    }

    try {
      store.listRecoverable()
      fail("Expected unsupported draft format to be rejected")
    } catch (_: IOException) {
      assertTrue("Unreadable drafts must be retained for recovery", draftFile.exists())
    }
  }

  @Test
  fun versionOneDraftRemainsReadableAndUpgradesWithStableBlockIds() = runBlocking {
    val draftId = "3d7f6da0-9622-4f7f-a6d0-03254405dd9f"
    val legacyFile = File(directory, "$draftId.aan-draft")
    DataOutputStream(FileOutputStream(legacyFile)).use { output ->
      output.writeInt(0x414E414E)
      output.writeInt(1)
      output.writeString(draftId)
      output.writeNullableString(null)
      output.writeString("folder-study")
      output.writeLong(0L)
      output.writeLong(2L)
      output.writeLong(123L)
      output.writeString("folder-study")
      output.writeString("旧格式草稿")
      output.writeBoolean(false)
      output.writeString("minimal-white")
      output.writeNullableFloat(null)
      output.writeNullableFloat(null)
      output.writeInt(1)
      output.writeString("TEXT")
      output.writeNullableString("旧版本正文")
      output.writeNullableString(null)
      output.writeNullableString(null)
      output.writeFloat(100f)
      output.writeString("CENTER")
      output.writeNullableFloat(null)
      output.writeNullableString(null)
    }

    val legacy = store.listRecoverable().single()
    val generatedBlockId = legacy.draft.blocks.single().id
    assertEquals("旧版本正文", legacy.draft.blocks.single().text)
    assertTrue(generatedBlockId.isNotBlank())

    store.writeAtomic(legacy)

    assertEquals(generatedBlockId, store.listRecoverable().single().draft.blocks.single().id)
  }

  @Test
  fun versionTwoDraftRemainsReadableWithDefaultFormatting() = runBlocking {
    val draftId = "a4788ff3-3132-40f0-b8a7-4d54c747b4b3"
    val legacyFile = File(directory, "$draftId.aan-draft")
    DataOutputStream(FileOutputStream(legacyFile)).use { output ->
      output.writeInt(0x414E414E)
      output.writeInt(2)
      output.writeString(draftId)
      output.writeNullableString("legacy-note-v2")
      output.writeString("folder-v2")
      output.writeLong(3L)
      output.writeLong(8L)
      output.writeLong(456L)
      output.writeString("folder-v2")
      output.writeString("v2 草稿")
      output.writeBoolean(true)
      output.writeString("minimal-white")
      output.writeNullableFloat(null)
      output.writeNullableFloat(null)
      output.writeInt(1)
      output.writeString("stable-v2-block")
      output.writeString("TEXT")
      output.writeNullableString("旧版正文")
      output.writeNullableString(null)
      output.writeNullableString(null)
      output.writeFloat(100f)
      output.writeString("CENTER")
      output.writeNullableFloat(null)
      output.writeNullableString(null)
    }

    val legacy = store.listRecoverable().single()

    assertEquals("旧版正文", legacy.draft.blocks.single().text)
    assertEquals("stable-v2-block", legacy.draft.blocks.single().id)
    assertEquals(
      BlockFormattingState.Supported(BlockFormatting()),
      legacy.draft.blocks.single().formatting,
    )
  }

  @Test
  fun unknownFormattingBlobSurvivesDraftRoundTrip() = runBlocking {
    val originalBlob = byteArrayOf(
      0x41, 0x4e, 0x46, 0x4d,
      0x7f,
      0x00,
      0x00, 0x00, 0x00, 0x00,
    )
    val snapshot = snapshot(localRevision = 9L).copy(
      draft = NoteDraft(
        folderId = "folder-study",
        title = "未知格式恢复",
        blocks = listOf(
          NoteBlockDraft(
            type = BlockType.TEXT,
            text = "正文仍可恢复",
            formatting = BlockFormattingState.Unsupported(originalBlob, FormattingDecodeError.UNSUPPORTED_VERSION),
          ),
        ),
      ),
    )

    store.writeAtomic(snapshot)

    val formatting = store.listRecoverable().single().draft.blocks.single().formatting
    assertTrue(formatting is BlockFormattingState.Unsupported)
    assertEquals(originalBlob.toList(), (formatting as BlockFormattingState.Unsupported).originalBlob.toList())
  }

  private fun DataOutputStream.writeString(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    writeInt(bytes.size)
    write(bytes)
  }

  private fun DataOutputStream.writeNullableString(value: String?) {
    writeBoolean(value != null)
    if (value != null) writeString(value)
  }

  private fun DataOutputStream.writeNullableFloat(value: Float?) {
    writeBoolean(value != null)
    if (value != null) writeFloat(value)
  }

  private fun snapshot(localRevision: Long) = EditorDraftSnapshot(
    draftId = "c2c5caaa-6f0f-46c9-9db0-e1783f46e4d0",
    noteId = "note-1",
    folderId = "folder-study",
    baseContentRevision = 4L,
    localRevision = localRevision,
    savedAtMillis = 1000L + localRevision,
    draft = NoteDraft(folderId = "folder-study", title = "保留较新草稿"),
  )
}
