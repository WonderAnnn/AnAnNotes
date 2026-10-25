package com.anan.anannotes.data.repository

import androidx.room.withTransaction
import com.anan.anannotes.core.model.FolderKind
import com.anan.anannotes.core.model.FolderModel
import com.anan.anannotes.core.model.UNCATEGORIZED_FOLDER_ID
import com.anan.anannotes.data.local.AnAnDatabase
import com.anan.anannotes.data.local.FolderEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class FolderRepository(
  private val database: AnAnDatabase,
  private val nowMillis: () -> Long = System::currentTimeMillis,
  private val newId: () -> String = { UUID.randomUUID().toString() },
) {
  fun observeFolders(): Flow<List<FolderModel>> =
    database.folderDao().observeFolders().map { folders -> folders.map(FolderEntity::toModel) }

  suspend fun createFolder(rawName: String): FolderModel {
    val name = rawName.trim()
    require(name.isNotEmpty()) { "文件夹名称不能为空" }
    return database.withTransaction {
      check(database.folderDao().countByName(name) == 0) { "同名文件夹已存在" }
      FolderEntity(
        id = newId(),
        name = name,
        kind = FolderKind.USER.name,
        createdAtMillis = nowMillis(),
        sortPosition = database.folderDao().nextSortPosition(),
      ).also { database.folderDao().insert(it) }.toModel()
    }
  }

  suspend fun renameFolder(folderId: String, rawName: String): FolderModel {
    val name = rawName.trim()
    require(name.isNotEmpty()) { "文件夹名称不能为空" }
    return database.withTransaction {
      val folder = checkNotNull(database.folderDao().getById(folderId)) { "文件夹不存在" }
      check(folder.kind == FolderKind.USER.name) { "系统文件夹不能重命名" }
      check(database.folderDao().countByNameExcept(name, folderId) == 0) { "同名文件夹已存在" }
      folder.copy(name = name).also { database.folderDao().upsert(it) }.toModel()
    }
  }

  suspend fun deleteFolder(folderId: String): Boolean = database.withTransaction {
    val folder = database.folderDao().getById(folderId) ?: return@withTransaction false
    if (folder.kind != FolderKind.USER.name) return@withTransaction false
    database.noteDao().reassignFolder(folderId, UNCATEGORIZED_FOLDER_ID)
    database.homeViewPreferenceDao().deleteByView(folderId)
    database.folderDao().deleteById(folderId)
    true
  }
}
