package com.anan.anannotes.security

import com.anan.anannotes.core.model.BlockAlignment
import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.FolderKind
import com.anan.anannotes.core.model.OcrStatus
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupRecordSetValidatorTest {
  @Test
  fun validatesOutOfOrderRecordsAndReturnsCountsOnlyAfterFinish() {
    val assetBytes = byteArrayOf(1, 2, 3)
    val asset = BackupRecordPayload.Asset(
      assetId = "asset-1",
      mimeType = "image/png",
      byteCount = assetBytes.size.toLong(),
      sha256 = MessageDigest.getInstance("SHA-256").digest(assetBytes),
    )
    val validator = BackupRecordSetValidator()

    validator.accept(BackupRecordDomain.ORDINARY, imageBlock(asset.assetId))
    validator.accept(BackupRecordDomain.ORDINARY, todoBlock())
    validator.accept(BackupRecordDomain.ORDINARY, todo())
    validator.accept(BackupRecordDomain.ORDINARY, note())
    validator.accept(BackupRecordDomain.ORDINARY, folder())
    validator.accept(BackupRecordDomain.ORDINARY, asset)
    validator.accept(BackupRecordDomain.ORDINARY, draft())
    validator.accept(BackupRecordDomain.ORDINARY, draftBlock(asset.assetId))

    assertEquals(
      BackupRecordSetSummary(
        ordinaryFolders = 1,
        ordinaryNotes = 1,
        ordinaryBlocks = 2,
        ordinaryTodos = 1,
        ordinaryDrafts = 1,
        ordinaryDraftBlocks = 1,
        ordinaryAssets = 1,
        ordinaryAssetBytes = 3L,
        privateNotes = 0,
        privateBlocks = 0,
        privateTodos = 0,
        privateDrafts = 0,
        privateDraftBlocks = 0,
        privateAssets = 0,
        privateAssetBytes = 0L,
      ),
      validator.finish(),
    )
  }

  @Test
  fun rejectsDuplicateIdsWithinTypeAndDomainButKeepsDomainsIsolated() {
    val validator = BackupRecordSetValidator()
    validator.accept(BackupRecordDomain.ORDINARY, folder())
    validator.accept(BackupRecordDomain.PRIVATE, note("note-1", "private-uncategorized"))
    validator.accept(BackupRecordDomain.ORDINARY, note())
    assertThrows(BackupRecordPayloadException::class.java) {
      validator.accept(BackupRecordDomain.ORDINARY, note())
    }
  }

  @Test
  fun rejectsFolderNamesThatWouldCollideWithTheRoomRepository() {
    val validator = BackupRecordSetValidator()
    validator.accept(BackupRecordDomain.ORDINARY, folder().copy(name = "Notes"))
    assertThrows(BackupRecordPayloadException::class.java) {
      validator.accept(
        BackupRecordDomain.ORDINARY,
        BackupRecordPayload.Folder("folder-2", "notes", FolderKind.USER, 2L, 1),
      )
    }
  }

  @Test
  fun rejectsDanglingFolderNoteBlockAndAssetReferences() {
    val missingFolder = BackupRecordSetValidator().also {
      it.accept(BackupRecordDomain.ORDINARY, note())
    }
    assertThrows(BackupRecordPayloadException::class.java) { missingFolder.finish() }

    val missingNote = BackupRecordSetValidator().also {
      it.accept(BackupRecordDomain.ORDINARY, folder())
      it.accept(BackupRecordDomain.ORDINARY, todoBlock(noteId = "absent"))
    }
    assertThrows(BackupRecordPayloadException::class.java) { missingNote.finish() }

    val missingAsset = BackupRecordSetValidator().also {
      it.accept(BackupRecordDomain.ORDINARY, folder())
      it.accept(BackupRecordDomain.ORDINARY, note())
      it.accept(BackupRecordDomain.ORDINARY, imageBlock("absent"))
    }
    assertThrows(BackupRecordPayloadException::class.java) { missingAsset.finish() }
  }

  @Test
  fun rejectsNonContiguousOrDuplicateBlockPositions() {
    val gap = BackupRecordSetValidator().also {
      it.accept(BackupRecordDomain.ORDINARY, folder())
      it.accept(BackupRecordDomain.ORDINARY, note())
      it.accept(BackupRecordDomain.ORDINARY, todoBlock(position = 1))
    }
    assertThrows(BackupRecordPayloadException::class.java) { gap.finish() }

    val duplicate = BackupRecordSetValidator().also {
      it.accept(BackupRecordDomain.ORDINARY, folder())
      it.accept(BackupRecordDomain.ORDINARY, note())
      it.accept(BackupRecordDomain.ORDINARY, todoBlock(blockId = "b1", position = 0))
    }
    assertThrows(BackupRecordPayloadException::class.java) {
      duplicate.accept(BackupRecordDomain.ORDINARY, todoBlock(blockId = "b2", position = 0))
    }
  }

  @Test
  fun todosMustExactlyMatchOneTodoBlockAndEveryNonBlankTodoBlockMustHaveRecord() {
    val textMismatch = BackupRecordSetValidator().also {
      it.accept(BackupRecordDomain.ORDINARY, folder())
      it.accept(BackupRecordDomain.ORDINARY, note())
      it.accept(BackupRecordDomain.ORDINARY, todoBlock(text = "block text"))
      it.accept(BackupRecordDomain.ORDINARY, todo(text = "different text"))
    }
    assertThrows(BackupRecordPayloadException::class.java) { textMismatch.finish() }

    val missingTodo = BackupRecordSetValidator().also {
      it.accept(BackupRecordDomain.ORDINARY, folder())
      it.accept(BackupRecordDomain.ORDINARY, note())
      it.accept(BackupRecordDomain.ORDINARY, todoBlock())
    }
    assertThrows(BackupRecordPayloadException::class.java) { missingTodo.finish() }

    val wrongType = BackupRecordSetValidator().also {
      it.accept(BackupRecordDomain.ORDINARY, folder())
      it.accept(BackupRecordDomain.ORDINARY, note())
      it.accept(BackupRecordDomain.ORDINARY, textBlock())
      it.accept(BackupRecordDomain.ORDINARY, todo(blockId = "b1"))
    }
    assertThrows(BackupRecordPayloadException::class.java) { wrongType.finish() }
  }

  @Test
  fun draftReferencesAndDeclaredBlockCountsAreValidated() {
    val countMismatch = BackupRecordSetValidator().also {
      it.accept(BackupRecordDomain.ORDINARY, folder())
      it.accept(BackupRecordDomain.ORDINARY, draft(blockCount = 2))
      it.accept(BackupRecordDomain.ORDINARY, draftBlock())
    }
    assertThrows(BackupRecordPayloadException::class.java) { countMismatch.finish() }

    val missingNote = BackupRecordSetValidator().also {
      it.accept(BackupRecordDomain.ORDINARY, folder())
      it.accept(BackupRecordDomain.ORDINARY, draft(noteId = "missing", baseRevision = 1L))
    }
    assertThrows(BackupRecordPayloadException::class.java) { missingNote.finish() }
  }

  @Test
  fun newEditorDraftMayStoreTheZeroBaseRevisionUsedByTheCurrentDraftStore() {
    val validator = BackupRecordSetValidator()
    validator.accept(BackupRecordDomain.ORDINARY, folder())
    validator.accept(BackupRecordDomain.ORDINARY, draft(noteId = null, baseRevision = 0L, blockCount = 0))

    assertEquals(1L, validator.finish().ordinaryDrafts)
  }

  @Test
  fun privateRecordsUseOnlyPrivateUncategorizedAndCannotCarryFolders() {
    val badFolder = BackupRecordSetValidator()
    assertThrows(BackupRecordPayloadException::class.java) {
      badFolder.accept(BackupRecordDomain.PRIVATE, folder())
    }
    val badReference = BackupRecordSetValidator().also {
      it.accept(BackupRecordDomain.PRIVATE, note("p1", "folder-1"))
    }
    assertThrows(BackupRecordPayloadException::class.java) { badReference.finish() }

    val validPrivate = BackupRecordSetValidator().also {
      it.accept(BackupRecordDomain.PRIVATE, note("p1", "private-uncategorized"))
    }
    assertEquals(1L, validPrivate.finish().privateNotes)
  }

  @Test
  fun rejectsUnreferencedAssetsAndDuplicateTodoIds() {
    val digest = MessageDigest.getInstance("SHA-256").digest(byteArrayOf(4))
    val orphanAsset = BackupRecordSetValidator().also {
      it.accept(BackupRecordDomain.ORDINARY, BackupRecordPayload.Asset("asset", "image/png", 1L, digest))
    }
    assertThrows(BackupRecordPayloadException::class.java) { orphanAsset.finish() }

    val duplicateTodos = BackupRecordSetValidator().also {
      it.accept(BackupRecordDomain.ORDINARY, folder())
      it.accept(BackupRecordDomain.ORDINARY, note())
      it.accept(BackupRecordDomain.ORDINARY, todoBlock())
      it.accept(BackupRecordDomain.ORDINARY, todo())
    }
    assertThrows(BackupRecordPayloadException::class.java) {
      duplicateTodos.accept(BackupRecordDomain.ORDINARY, todo())
    }
  }

  private fun folder() = BackupRecordPayload.Folder("folder-1", "资料", FolderKind.USER, 1L, 0)

  private fun note(id: String = "note-1", folderId: String = "folder-1") = BackupRecordPayload.Note(
    id = id,
    folderId = folderId,
    title = "Note",
    titleManuallySet = true,
    createdAtMillis = 1L,
    updatedAtMillis = 2L,
    skinId = "minimal-white",
    fontSizeSpOverride = null,
    lineSpacingMultiplierOverride = null,
    isPinned = false,
    deletedAtMillis = null,
    contentRevision = 1L,
    importRequestId = null,
  )

  private fun textBlock() = BackupRecordPayload.Block(
    id = "b1", noteId = "note-1", position = 0, type = BlockType.TEXT, text = "plain",
    assetRef = null, widthPercent = 100f, alignment = BlockAlignment.START,
    aspectRatio = null, ocrStatus = null, formattingBlob = null,
  )

  private fun todoBlock(
    noteId: String = "note-1",
    blockId: String = "b1",
    position: Int = 0,
    text: String = "Task",
  ) = BackupRecordPayload.Block(
    id = blockId, noteId = noteId, position = position, type = BlockType.TODO, text = text,
    assetRef = null, widthPercent = 100f, alignment = BlockAlignment.START,
    aspectRatio = null, ocrStatus = null, formattingBlob = null,
  )

  private fun imageBlock(assetId: String) = BackupRecordPayload.Block(
    id = "image-1", noteId = "note-1", position = 1, type = BlockType.IMAGE, text = null,
    assetRef = assetId, widthPercent = 100f, alignment = BlockAlignment.CENTER,
    aspectRatio = 1.5f, ocrStatus = null, formattingBlob = null,
  )

  private fun todo(blockId: String = "b1", text: String = "Task") = BackupRecordPayload.Todo(
    id = "todo-1", noteId = "note-1", blockId = blockId, text = text,
    isCompleted = false, updatedAtMillis = 2L,
  )

  private fun draft(
    noteId: String? = null,
    baseRevision: Long? = null,
    blockCount: Int = 1,
  ) = BackupRecordPayload.Draft(
    draftId = "draft-1", noteId = noteId, folderId = "folder-1",
    baseContentRevision = baseRevision, localRevision = 1L, savedAtMillis = 3L,
    importRequestId = null, title = "Draft", titleManuallySet = false, skinId = "minimal-white",
    fontSizeSpOverride = null, lineSpacingMultiplierOverride = null, blockCount = blockCount,
  )

  private fun draftBlock(assetId: String? = null) = BackupRecordPayload.DraftBlock(
    draftId = "draft-1", position = 0, blockId = "draft-block-1", type = BlockType.TEXT,
    text = "Draft body", assetRef = assetId, widthPercent = 100f, alignment = BlockAlignment.START,
    aspectRatio = null, ocrStatus = if (assetId == null) null else OcrStatus.PENDING, formattingBlob = null,
  )
}
