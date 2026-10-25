package com.anan.anannotes.feature.backup

import com.anan.anannotes.core.model.BlockAlignment
import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.FolderKind
import com.anan.anannotes.security.BackupRecordDomain
import com.anan.anannotes.security.BackupRecordPayload
import com.anan.anannotes.security.CryptoBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BackupObjectFingerprintCollectorTest {
  @Test
  fun fingerprintsAreOrderIndependentAndCoverNoteBlocksTodosAndReferencedAssetHash() {
    val normal = fingerprintSet(assetDigest = 3, reverse = false)
    val reordered = fingerprintSet(assetDigest = 3, reverse = true)
    val changedAsset = fingerprintSet(assetDigest = 4, reverse = false)

    assertEquals(normal, reordered)
    assertNotEquals(normal, changedAsset)
  }

  private fun fingerprintSet(assetDigest: Int, reverse: Boolean): Map<BackupObjectKey, CryptoBytes> {
    val records = listOf(
      BackupRecordPayload.Folder("folder", "Study", FolderKind.USER, 1L, 1),
      note(),
      BackupRecordPayload.Block("image", "note", 0, BlockType.IMAGE, null, "asset", 80f, BlockAlignment.CENTER, 1.5f, null, null),
      BackupRecordPayload.Block("todo-block", "note", 1, BlockType.TODO, "Do this", null, 100f, BlockAlignment.START, null, null, null),
      BackupRecordPayload.Todo("todo", "note", "todo-block", "Do this", false, 3L),
      BackupRecordPayload.Asset("asset", "image/png", 1L, ByteArray(32) { assetDigest.toByte() }),
    )
    val collector = BackupObjectFingerprintCollector()
    (if (reverse) records.reversed() else records).forEach { collector.accept(BackupRecordDomain.ORDINARY, it) }
    return collector.finish()
  }

  private fun note() = BackupRecordPayload.Note(
    "note", "folder", "Title", false, 1L, 2L, "minimal-white", null, null, false, null, 1L, null,
  )
}
