package com.anan.anannotes.feature.backup

import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.FolderKind
import com.anan.anannotes.core.model.NoteBlockDraft
import com.anan.anannotes.core.model.NoteDraft
import com.anan.anannotes.core.model.BlockAlignment
import com.anan.anannotes.data.local.BlockEntity
import com.anan.anannotes.data.local.FolderEntity
import com.anan.anannotes.data.local.NoteEntity
import com.anan.anannotes.data.local.TodoEntity
import com.anan.anannotes.feature.editor.EditorDraftSnapshot
import com.anan.anannotes.feature.privatemodule.PrivateEditSnapshot
import com.anan.anannotes.feature.privatemodule.PrivateNoteDocument
import com.anan.anannotes.feature.privatemodule.PrivateNoteId
import com.anan.anannotes.feature.privatemodule.PrivateTodo
import com.anan.anannotes.feature.privatemodule.PrivateVaultSnapshot
import com.anan.anannotes.security.BackupRecordDomain
import com.anan.anannotes.security.BackupRecordKind
import com.anan.anannotes.security.BackupRecordPayload
import com.anan.anannotes.security.BackupRecordPayloadWriter
import com.anan.anannotes.security.PrivateAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class BackupRecordSnapshotMapperTest {
  @Test
  fun mapsRoomAndPrivateSnapshotsWithoutLosingPersistedFieldsOrMixingDomains() {
    val folder = FolderEntity("folder", "学习", FolderKind.USER.name, 11L, 3)
    val note = NoteEntity(
      id = "note", folderId = folder.id, title = "标题", titleManuallySet = true,
      createdAtMillis = 12L, updatedAtMillis = 13L, skinId = "paper",
      fontSizeSpOverride = 21f, lineSpacingMultiplierOverride = 1.4f,
      isPinned = true, deletedAtMillis = 14L, contentRevision = 7L,
      importRequestId = "import-request",
    )
    val block = BlockEntity(
      id = "image-block", noteId = note.id, position = 0, type = BlockType.IMAGE.name,
      text = null, assetPath = "notes/assets/asset-id.png", previewPath = "cache/ignored.png",
      widthPercent = 72f, alignment = BlockAlignment.CENTER.name, aspectRatio = 1.5f,
      ocrStatus = null, formattingBlob = null,
    )
    val todoBlock = BlockEntity(
      id = "todo-block", noteId = note.id, position = 1, type = BlockType.TODO.name,
      text = "复核", assetPath = null, previewPath = null, widthPercent = 100f,
      alignment = BlockAlignment.START.name, aspectRatio = null, ocrStatus = null,
      formattingBlob = null,
    )
    val todo = TodoEntity("todo", note.id, todoBlock.id, "复核", true, 15L)
    val ordinaryDraft = EditorDraftSnapshot(
      draftId = "draft", noteId = note.id, folderId = folder.id,
      baseContentRevision = 7L, localRevision = 8L, savedAtMillis = 16L,
      draft = NoteDraft(
        folderId = folder.id, title = "恢复草稿", titleManuallySet = false,
        blocks = listOf(NoteBlockDraft(id = "draft-text", type = BlockType.TEXT, text = "草稿正文")),
        skinId = "mint", fontSizeSpOverride = 19f, lineSpacingMultiplierOverride = 1.3f,
      ),
    )
    val privateNote = PrivateNoteDocument(
      id = PrivateNoteId("private-note"), title = "私密标题", titleManuallySet = true,
      blocks = listOf(
        NoteBlockDraft(id = "private-text", type = BlockType.TEXT, text = "机密正文"),
        NoteBlockDraft(id = "private-todo-block", type = BlockType.TODO, text = "私密待办"),
      ),
      todos = listOf(PrivateTodo("private-todo", "private-todo-block", "私密待办", false, 21L)),
      skinId = "night", fontSizeSpOverride = 20f, lineSpacingMultiplierOverride = 1.5f,
      createdAtMillis = 17L, updatedAtMillis = 18L, isPinned = true, deletedAtMillis = 19L,
      contentRevision = 4L,
    )
    val privateDraft = PrivateEditSnapshot(
      draftId = "private-draft", noteId = null, folderId = "private-uncategorized",
      title = "未完成私密草稿", titleManuallySet = false,
      blocks = listOf(NoteBlockDraft(id = "private-draft-text", type = BlockType.TEXT, text = "稍后写")),
      skinId = "night", baseContentRevision = null, localRevision = 2L, savedAtMillis = 22L,
    )

    val result = BackupRecordSnapshotMapper.map(
      folders = listOf(folder), notes = listOf(note), blocks = listOf(block, todoBlock),
      todos = listOf(todo), ordinaryDrafts = listOf(ordinaryDraft),
      privateSnapshot = PrivateVaultSnapshot(notes = listOf(privateNote), drafts = listOf(privateDraft)),
      openOrdinaryAsset = { ByteArrayInputStream(byteArrayOf(1)) },
      openPrivateAsset = { _, _ -> ByteArrayInputStream(byteArrayOf(2)) },
    )

    val ordinaryNote = result.records.single {
      it.domain == BackupRecordDomain.ORDINARY && it.payload is BackupRecordPayload.Note
    }.payload as BackupRecordPayload.Note
    assertEquals(note.id, ordinaryNote.id)
    assertEquals(note.folderId, ordinaryNote.folderId)
    assertEquals(note.title, ordinaryNote.title)
    assertEquals(note.titleManuallySet, ordinaryNote.titleManuallySet)
    assertEquals(note.skinId, ordinaryNote.skinId)
    assertEquals(note.fontSizeSpOverride, ordinaryNote.fontSizeSpOverride)
    assertEquals(note.lineSpacingMultiplierOverride, ordinaryNote.lineSpacingMultiplierOverride)
    assertEquals(note.isPinned, ordinaryNote.isPinned)
    assertEquals(note.deletedAtMillis, ordinaryNote.deletedAtMillis)
    assertEquals(note.contentRevision, ordinaryNote.contentRevision)
    assertEquals(note.importRequestId, ordinaryNote.importRequestId)

    val ordinaryBlock = result.records.mapNotNull { it.payload as? BackupRecordPayload.Block }.single { it.id == block.id }
    assertEquals("asset-id.png", ordinaryBlock.assetRef)
    assertEquals(block.widthPercent, ordinaryBlock.widthPercent)
    assertEquals(block.aspectRatio, ordinaryBlock.aspectRatio)
    val ordinaryTodo = result.records.single {
      it.domain == BackupRecordDomain.ORDINARY && it.payload is BackupRecordPayload.Todo
    }.payload as BackupRecordPayload.Todo
    assertEquals(todo.isCompleted, ordinaryTodo.isCompleted)
    val ordinaryDraftPayload = result.records.single {
      it.domain == BackupRecordDomain.ORDINARY && it.payload is BackupRecordPayload.Draft
    }.payload as BackupRecordPayload.Draft
    assertEquals(7L, ordinaryDraftPayload.baseContentRevision)
    assertEquals(8L, ordinaryDraftPayload.localRevision)

    val privateNotePayload = result.records.single { it.domain == BackupRecordDomain.PRIVATE && it.payload is BackupRecordPayload.Note }.payload as BackupRecordPayload.Note
    assertEquals("private-uncategorized", privateNotePayload.folderId)
    assertEquals("私密标题", privateNotePayload.title)
    assertEquals(null, privateNotePayload.importRequestId)
    val privateTodoPayload = result.records.single { it.domain == BackupRecordDomain.PRIVATE && it.payload is BackupRecordPayload.Todo }.payload as BackupRecordPayload.Todo
    assertEquals(privateNote.id.value, privateTodoPayload.noteId)
    assertEquals("private-todo-block", privateTodoPayload.blockId)
    assertTrue(result.records.any { it.domain == BackupRecordDomain.PRIVATE && it.payload is BackupRecordPayload.Draft })
    assertEquals(BackupRecordKind.NOTE, ordinaryNote.kind())

    val writer = com.anan.anannotes.security.BackupRecordPayloadWriter(ByteArrayOutputStream())
    val summary = result.use { snapshot ->
      snapshot.writeTo(writer)
      writer.finish()
    }
    assertEquals(8L, summary.ordinaryRecordCount)
    assertEquals(6L, summary.privateRecordCount)
    assertEquals(14L, summary.recordCount)
  }

  @Test
  fun privateImageReferencesBecomeStableIdsAndMimetypeIsDetectedFromStreamedBytes() {
    val access = PrivateAccess(ByteArray(32) { it.toByte() })
    val imageBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1)
    val snapshot = BackupRecordSnapshotMapper.map(
      folders = emptyList(), notes = emptyList(), blocks = emptyList(), todos = emptyList(),
      ordinaryDrafts = emptyList(),
      privateSnapshot = PrivateVaultSnapshot(
        notes = listOf(
          PrivateNoteDocument(
            id = PrivateNoteId("private-image-note"),
            title = "照片",
            blocks = listOf(
              NoteBlockDraft(id = "image-block", type = BlockType.IMAGE, assetPath = "private-asset:123e4567-e89b-12d3-a456-426614174000"),
            ),
            createdAtMillis = 1L,
            updatedAtMillis = 1L,
            contentRevision = 1L,
          ),
        ),
      ),
      openOrdinaryAsset = { error("ordinary image was not expected") },
      openPrivateAsset = { _, reference ->
        assertEquals("private-asset:123e4567-e89b-12d3-a456-426614174000", reference)
        ByteArrayInputStream(imageBytes)
      },
      privateAccess = access,
    )

    val mappedBlock = snapshot.records.mapNotNull { it.payload as? BackupRecordPayload.Block }.single()
    assertEquals("123e4567-e89b-12d3-a456-426614174000", mappedBlock.assetRef)
    val writer = BackupRecordPayloadWriter(ByteArrayOutputStream())
    snapshot.use { it.writeTo(writer) }
    val summary = writer.finish()
    assertEquals(3L, summary.privateRecordCount)
    access.revoke()
  }
  private fun BackupRecordPayload.kind(): BackupRecordKind = when (this) {
    is BackupRecordPayload.Folder -> BackupRecordKind.FOLDER
    is BackupRecordPayload.Note -> BackupRecordKind.NOTE
    is BackupRecordPayload.Block -> BackupRecordKind.BLOCK
    is BackupRecordPayload.Todo -> BackupRecordKind.TODO
    is BackupRecordPayload.Draft -> BackupRecordKind.DRAFT
    is BackupRecordPayload.DraftBlock -> BackupRecordKind.DRAFT_BLOCK
    is BackupRecordPayload.Asset -> BackupRecordKind.ASSET
  }
}
