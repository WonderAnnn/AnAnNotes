package com.anan.anannotes.security

import com.anan.anannotes.core.model.BlockAlignment
import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.FolderKind
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupRecordPayloadWriterTest {
  @Test
  fun writesTypedFieldsAndStreamedAssetsIntoAuthenticatedRecordFraming() {
    val destination = ByteArrayOutputStream()
    val writer = BackupRecordPayloadWriter(destination)
    writer.write(BackupRecordDomain.ORDINARY, BackupRecordPayload.Folder("f", "Folder", FolderKind.USER, 0L, 0))
    writer.write(BackupRecordDomain.ORDINARY, note())
    writer.write(
      BackupRecordDomain.ORDINARY,
      BackupRecordPayload.Block("b", "n", 0, BlockType.IMAGE, null, "asset", 100f, BlockAlignment.CENTER, 1f, null, null),
    )
    writer.writeAsset(BackupRecordDomain.ORDINARY, "asset", "image/png", source = { ByteArrayInputStream(byteArrayOf(1, 2, 3)) })

    writer.finish()
  }

  @Test
  fun rejectsSourceMutationBetweenHashPassAndWritePass() {
    val destination = ByteArrayOutputStream()
    val writer = BackupRecordPayloadWriter(destination)
    writer.write(BackupRecordDomain.ORDINARY, BackupRecordPayload.Folder("f", "Folder", FolderKind.USER, 0L, 0))
    writer.write(BackupRecordDomain.ORDINARY, note())
    writer.write(
      BackupRecordDomain.ORDINARY,
      BackupRecordPayload.Block("b", "n", 0, BlockType.IMAGE, null, "asset", 100f, BlockAlignment.CENTER, 1f, null, null),
    )
    var openCount = 0

    assertThrows(BackupRecordPayloadException::class.java) {
      writer.writeAsset(BackupRecordDomain.ORDINARY, "asset", "image/png") {
        openCount++
        ByteArrayInputStream(if (openCount == 1) byteArrayOf(1, 2, 3) else byteArrayOf(3, 2, 1))
      }
    }
  }

  @Test
  fun finishRejectsMissingReferencedAssetsAndDoesNotWriteAValidTerminator() {
    val destination = ByteArrayOutputStream()
    val writer = BackupRecordPayloadWriter(destination)
    writer.write(BackupRecordDomain.ORDINARY, BackupRecordPayload.Folder("f", "Folder", FolderKind.USER, 0L, 0))
    writer.write(BackupRecordDomain.ORDINARY, note())
    writer.write(
      BackupRecordDomain.ORDINARY,
      BackupRecordPayload.Block("b", "n", 0, BlockType.IMAGE, null, "missing", 100f, BlockAlignment.CENTER, 1f, null, null),
    )

    assertThrows(BackupRecordPayloadException::class.java) { writer.finish() }
  }

  @Test
  fun resolvesAttachmentMimeFromOnlyTheBoundedFirstPassSignatureSample() {
    val writer = BackupRecordPayloadWriter(ByteArrayOutputStream())
    writer.write(BackupRecordDomain.ORDINARY, BackupRecordPayload.Folder("f", "Folder", FolderKind.USER, 0L, 0))
    writer.write(BackupRecordDomain.ORDINARY, note())
    writer.write(
      BackupRecordDomain.ORDINARY,
      BackupRecordPayload.Block("b", "n", 0, BlockType.IMAGE, null, "asset", 100f, BlockAlignment.CENTER, 1f, null, null),
    )
    val pngSignatureAndData = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1)

    writer.writeAssetDetectedMimeType(
      domain = BackupRecordDomain.ORDINARY,
      assetId = "asset",
      source = { ByteArrayInputStream(pngSignatureAndData) },
      resolveMimeType = { signature ->
        assertTrue(signature.size <= 16)
        assertArrayEquals(pngSignatureAndData.copyOfRange(0, 8), signature.copyOfRange(0, 8))
        "image/png"
      },
    )

    writer.finish()
  }

  private fun note() = BackupRecordPayload.Note(
    "n", "f", "title", false, 1L, 1L, "minimal-white", null, null, false, null, 1L, null,
  )
}
