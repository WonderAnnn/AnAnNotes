package com.anan.anannotes.feature.privatemodule

import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.NoteBlockDraft
import java.util.UUID

data class PrivateTodoDraft(
  val blockId: String = UUID.randomUUID().toString(),
  val text: String,
)

object PrivateEditorRules {
  fun canExportSavedNote(
    note: PrivateNoteDocument?,
    title: String,
    blocks: List<NoteBlockDraft>,
    todos: List<PrivateTodoDraft>,
  ): Boolean {
    if (note == null || note.deletedAtMillis != null || title != note.title) return false
    return runCatching { mergeBlocks(note.blocks, blocks, todos) == note.blocks }.getOrDefault(false)
  }

  fun findRecoverableDraft(
    drafts: List<PrivateEditSnapshot>,
    noteId: PrivateNoteId?,
    contentRevision: Long?,
  ): PrivateEditSnapshot? = drafts.asSequence()
    .filter { it.noteId == noteId && it.baseContentRevision == contentRevision }
    .maxWithOrNull(compareBy<PrivateEditSnapshot> { it.localRevision }.thenBy { it.savedAtMillis })

  fun resolveTitle(title: String, body: String): String =
    title.trim().ifEmpty {
      body.lineSequence().map(String::trim).firstOrNull(String::isNotEmpty)?.take(MAX_TITLE_LENGTH).orEmpty()
    }

  fun mergeBlocks(existing: List<NoteBlockDraft>, body: String, todos: List<PrivateTodoDraft>): List<NoteBlockDraft> {
    val firstTextId = existing.firstOrNull { it.type == BlockType.TEXT }?.id
    val blocks = buildList {
      existing.forEach { block ->
        if (block.id != firstTextId || body.isNotBlank()) {
          add(if (block.id == firstTextId) block.copy(text = body) else block)
        }
      }
      if (firstTextId == null && body.isNotBlank()) add(NoteBlockDraft(type = BlockType.TEXT, text = body))
    }
    return mergeBlocks(existing, blocks, todos)
  }

  fun mergeBlocks(
    existing: List<NoteBlockDraft>,
    blocks: List<NoteBlockDraft>,
    todos: List<PrivateTodoDraft>,
  ): List<NoteBlockDraft> {
    require(todos.map(PrivateTodoDraft::blockId).toSet().size == todos.size) { "Private todo block IDs must be unique" }
    val existingTodoIds = existing.asSequence()
      .filter { it.type == BlockType.TODO }
      .map(NoteBlockDraft::id)
      .toSet()
    val currentTodoIds = blocks.asSequence()
      .filter { it.type == BlockType.TODO }
      .map(NoteBlockDraft::id)
      .toSet()
    val temporarilyBlankTodoIds = existingTodoIds intersect currentTodoIds
    val activeTodos = todos.filter { it.text.isNotBlank() || it.blockId in temporarilyBlankTodoIds }
    val result = ArrayList<NoteBlockDraft>(maxOf(existing.size, blocks.size) + activeTodos.size)
    val includedNonTodoIds = mutableSetOf<String>()
    var todosWritten = false
    blocks.forEach { block ->
      when (block.type) {
        BlockType.TODO -> {
          if (!todosWritten) {
            activeTodos.forEach { todo ->
              result += NoteBlockDraft(id = todo.blockId, type = BlockType.TODO, text = todo.text.trim())
            }
            todosWritten = true
          }
        }
        else -> {
          result += block
          includedNonTodoIds += block.id
        }
      }
    }
    existing.filter { it.type != BlockType.TODO && it.id !in includedNonTodoIds }.forEach { preserved ->
      result += preserved
    }
    if (!todosWritten) activeTodos.forEach { todo ->
      result += NoteBlockDraft(id = todo.blockId, type = BlockType.TODO, text = todo.text.trim())
    }
    return result
  }

  private const val MAX_TITLE_LENGTH = 60
}
