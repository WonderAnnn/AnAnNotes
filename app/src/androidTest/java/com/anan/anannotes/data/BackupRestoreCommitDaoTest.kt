package com.anan.anannotes.data

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anan.anannotes.data.local.AnAnDatabase
import com.anan.anannotes.data.local.BackupRestoreCommitEntity
import com.anan.anannotes.data.local.FolderEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupRestoreCommitDaoTest {
  private lateinit var database: AnAnDatabase

  @Before
  fun setUp() {
    database = Room.inMemoryDatabaseBuilder(
      ApplicationProvider.getApplicationContext<Context>(),
      AnAnDatabase::class.java,
    ).allowMainThreadQueries().build()
  }

  @After
  fun tearDown() {
    database.close()
  }

  @Test
  fun restoreCommitMarkerAndOrdinaryRowsRollbackTogether() = runBlocking {
    try {
      database.withTransaction {
        database.folderDao().upsert(FolderEntity("restore-folder", "恢复资料", "USER", 1L, 1))
        database.backupRestoreCommitDao().insert(BackupRestoreCommitEntity("123e4567-e89b-12d3-a456-426614174000"))
        throw IllegalStateException("inject before transaction commit")
      }
    } catch (_: IllegalStateException) {
      // The injected failure is the condition under test.
    }

    assertNull(database.folderDao().getById("restore-folder"))
    assertFalse(database.backupRestoreCommitDao().contains("123e4567-e89b-12d3-a456-426614174000"))
  }

  @Test
  fun committedMarkerIsDurableUntilRecoveryFinalizesItsJournal() = runBlocking {
    val transactionId = "123e4567-e89b-12d3-a456-426614174000"
    database.withTransaction {
      database.folderDao().upsert(FolderEntity("restore-folder", "恢复资料", "USER", 1L, 1))
      database.backupRestoreCommitDao().insert(BackupRestoreCommitEntity(transactionId))
    }

    assertTrue(database.backupRestoreCommitDao().contains(transactionId))
    assertTrue(database.backupRestoreCommitDao().allTransactionIds().contains(transactionId))
    database.backupRestoreCommitDao().delete(transactionId)
    assertFalse(database.backupRestoreCommitDao().contains(transactionId))
  }
}
