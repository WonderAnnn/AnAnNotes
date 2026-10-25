package com.anan.anannotes.security

import com.anan.anannotes.core.model.BlockAlignment
import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.FolderKind
import com.anan.anannotes.core.model.OcrStatus
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupRecordPayloadCodecTest {
  @Test
  fun folderEncodingUsesDocumentedBigEndianFieldsAndDecodesExactValues() {
    val folder = BackupRecordPayload.Folder(
      id = "f",
      name = "N",
      kind = FolderKind.USER,
      createdAtMillis = 0L,
      sortPosition = -1,
    )
    val expected = byteArrayOf(
      0, 0, 0, 1, 'f'.code.toByte(),
      0, 0, 0, 1, 'N'.code.toByte(),
      1,
      0, 0, 0, 0, 0, 0, 0, 0,
      -1, -1, -1, -1,
    )

    val encoded = BackupRecordPayloadCodec.encode(BackupRecordDomain.ORDINARY, folder)

    assertArrayEquals(expected, encoded)
    assertEquals(
      folder,
      BackupRecordPayloadCodec.decode(
        BackupRecordDomain.ORDINARY,
        BackupRecordKind.FOLDER,
        schemaVersion = 1,
        ByteArrayInputStream(encoded),
        payloadBytes = encoded.size.toLong(),
      ),
    )
  }

  @Test
  fun everyNonAssetRecordRoundTripsItsPersistedFields() {
    val cases = listOf(
      Triple(
        BackupRecordDomain.ORDINARY,
        BackupRecordKind.NOTE,
        BackupRecordPayload.Note(
          id = "note-1",
          folderId = "folder-1",
          title = "标题",
          titleManuallySet = true,
          createdAtMillis = 100L,
          updatedAtMillis = 200L,
          skinId = "warm-paper",
          fontSizeSpOverride = 19f,
          lineSpacingMultiplierOverride = 1.25f,
          isPinned = true,
          deletedAtMillis = 300L,
          contentRevision = 7L,
          importRequestId = "request-1",
        ),
      ),
      Triple(
        BackupRecordDomain.PRIVATE,
        BackupRecordKind.BLOCK,
        BackupRecordPayload.Block(
          id = "block-1",
          noteId = "note-1",
          position = 2,
          type = BlockType.OCR_TEXT,
          text = "原样\n保留换行",
          assetRef = null,
          widthPercent = 80f,
          alignment = BlockAlignment.END,
          aspectRatio = null,
          ocrStatus = OcrStatus.PENDING,
          formattingBlob = byteArrayOf(0x41, 0x4e, 0x46, 0x4d),
        ),
      ),
      Triple(
        BackupRecordDomain.ORDINARY,
        BackupRecordKind.TODO,
        BackupRecordPayload.Todo(
          id = "todo-1",
          noteId = "note-1",
          blockId = "block-1",
          text = "完成测试",
          isCompleted = true,
          updatedAtMillis = 500L,
        ),
      ),
      Triple(
        BackupRecordDomain.ORDINARY,
        BackupRecordKind.DRAFT,
        BackupRecordPayload.Draft(
          draftId = "draft-1",
          noteId = null,
          folderId = "folder-1",
          baseContentRevision = null,
          localRevision = 4L,
          savedAtMillis = 600L,
          importRequestId = "request-2",
          title = "草稿",
          titleManuallySet = false,
          skinId = "minimal-white",
          fontSizeSpOverride = null,
          lineSpacingMultiplierOverride = 1.5f,
          blockCount = 1,
        ),
      ),
      Triple(
        BackupRecordDomain.PRIVATE,
        BackupRecordKind.DRAFT_BLOCK,
        BackupRecordPayload.DraftBlock(
          draftId = "draft-2",
          position = 0,
          blockId = "block-2",
          type = BlockType.IMAGE,
          text = null,
          assetRef = "asset-2",
          widthPercent = 100f,
          alignment = BlockAlignment.CENTER,
          aspectRatio = 1.5f,
          ocrStatus = null,
          formattingBlob = null,
        ),
      ),
    )

    cases.forEach { (domain, kind, value) ->
      val encoded = BackupRecordPayloadCodec.encode(domain, value)
      val decoded = BackupRecordPayloadCodec.decode(
        domain,
        kind,
        schemaVersion = 1,
        ByteArrayInputStream(encoded),
        payloadBytes = encoded.size.toLong(),
      )
      when {
        value is BackupRecordPayload.Block && decoded is BackupRecordPayload.Block -> {
          assertEquals(value.copy(formattingBlob = null), decoded.copy(formattingBlob = null))
          assertArrayEquals(value.formattingBlob, decoded.formattingBlob)
        }
        else -> assertEquals(value, decoded)
      }
    }
  }

  @Test
  fun assetBodyStreamsAndChecksDeclaredLengthAndSha256() {
    val bytes = ByteArray(BackupRecordStreamCodec.READ_BUFFER_BYTES * 2 + 17) { (it * 19).toByte() }
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    val asset = BackupRecordPayload.Asset(
      assetId = "asset-1",
      mimeType = "image/png",
      byteCount = bytes.size.toLong(),
      sha256 = digest,
    )
    val prefix = BackupRecordPayloadCodec.encodeAssetPrefix(BackupRecordDomain.ORDINARY, asset)
    val sink = ByteArrayOutputStream()
    val decoded = BackupRecordPayloadCodec.decode(
      BackupRecordDomain.ORDINARY,
      BackupRecordKind.ASSET,
      schemaVersion = 1,
      ByteArrayInputStream(prefix + bytes),
      payloadBytes = prefix.size.toLong() + bytes.size,
      onAssetBytes = { _, chunk, offset, length -> sink.write(chunk, offset, length) },
    ) as BackupRecordPayload.Asset

    assertEquals(asset.assetId, decoded.assetId)
    assertEquals(asset.mimeType, decoded.mimeType)
    assertEquals(asset.byteCount, decoded.byteCount)
    assertArrayEquals(digest, decoded.sha256)
    assertArrayEquals(bytes, sink.toByteArray())
  }

  @Test
  fun assetDecodeRejectsDigestMismatchAndMissingConsumer() {
    val bytes = "original image".toByteArray()
    val wrongDigest = ByteArray(32)
    val prefix = BackupRecordPayloadCodec.encodeAssetPrefix(
      BackupRecordDomain.PRIVATE,
      BackupRecordPayload.Asset("asset-1", "image/jpeg", bytes.size.toLong(), wrongDigest),
    )
    assertThrows(BackupRecordPayloadException::class.java) {
      BackupRecordPayloadCodec.decode(
        BackupRecordDomain.PRIVATE,
        BackupRecordKind.ASSET,
        1,
        ByteArrayInputStream(prefix + bytes),
        prefix.size.toLong() + bytes.size,
        onAssetBytes = { _, _, _, _ -> Unit },
      )
    }
    assertThrows(BackupRecordPayloadException::class.java) {
      BackupRecordPayloadCodec.decode(
        BackupRecordDomain.PRIVATE,
        BackupRecordKind.ASSET,
        1,
        ByteArrayInputStream(prefix + bytes),
        prefix.size.toLong() + bytes.size,
      )
    }
  }

  @Test
  fun malformedFieldsTrailingBytesAndWrongDomainRecordsAreRejected() {
    val valid = BackupRecordPayloadCodec.encode(
      BackupRecordDomain.ORDINARY,
      BackupRecordPayload.Folder("folder-1", "资料", FolderKind.USER, 1L, 0),
    )
    val trailing = valid + byteArrayOf(0x55)
    assertThrows(BackupRecordPayloadException::class.java) {
      BackupRecordPayloadCodec.decode(
        BackupRecordDomain.ORDINARY,
        BackupRecordKind.FOLDER,
        1,
        ByteArrayInputStream(trailing),
        trailing.size.toLong(),
      )
    }
    assertThrows(BackupRecordPayloadException::class.java) {
      BackupRecordPayloadCodec.decode(
        BackupRecordDomain.ORDINARY,
        BackupRecordKind.NOTE,
        1,
        ByteArrayInputStream(valid),
        valid.size.toLong(),
      )
    }
    assertThrows(BackupRecordPayloadException::class.java) {
      BackupRecordPayloadCodec.decode(
        BackupRecordDomain.ORDINARY,
        BackupRecordKind.FOLDER,
        2,
        ByteArrayInputStream(valid),
        valid.size.toLong(),
      )
    }

    val oneCharacterName = BackupRecordPayloadCodec.encode(
      BackupRecordDomain.ORDINARY,
      BackupRecordPayload.Folder("f", "N", FolderKind.USER, 0L, 0),
    )
    val badKind = oneCharacterName.clone().also { it[10] = 99 }
    assertThrows(BackupRecordPayloadException::class.java) {
      BackupRecordPayloadCodec.decode(
        BackupRecordDomain.ORDINARY,
        BackupRecordKind.FOLDER,
        1,
        ByteArrayInputStream(badKind),
        badKind.size.toLong(),
      )
    }
  }

  @Test
  fun writersRejectNonFiniteOrOutOfRangeValuesAndMalformedUtf8IsNotReplaced() {
    assertThrows(BackupRecordPayloadException::class.java) {
      BackupRecordPayloadCodec.encode(
        BackupRecordDomain.ORDINARY,
        BackupRecordPayload.Block(
          id = "block-1",
          noteId = "note-1",
          position = 0,
          type = BlockType.IMAGE,
          text = null,
          assetRef = "asset-1",
          widthPercent = Float.NaN,
          alignment = BlockAlignment.CENTER,
          aspectRatio = 1f,
          ocrStatus = null,
          formattingBlob = null,
        ),
      )
    }

    val malformedUtf8 = byteArrayOf(0, 0, 0, 1, 0xc3.toByte(), 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
    assertThrows(BackupRecordPayloadException::class.java) {
      BackupRecordPayloadCodec.decode(
        BackupRecordDomain.ORDINARY,
        BackupRecordKind.FOLDER,
        1,
        ByteArrayInputStream(malformedUtf8),
        malformedUtf8.size.toLong(),
      )
    }
  }

  @Test
  fun assetHeaderCannotClaimAByteCountDifferentFromItsRecordBody() {
    val bytes = byteArrayOf(9, 8, 7)
    val header = BackupRecordPayloadCodec.encodeAssetPrefix(
      BackupRecordDomain.ORDINARY,
      BackupRecordPayload.Asset("asset-1", "image/webp", 4L, MessageDigest.getInstance("SHA-256").digest(bytes)),
    )
    assertThrows(BackupRecordPayloadException::class.java) {
      BackupRecordPayloadCodec.decode(
        BackupRecordDomain.ORDINARY,
        BackupRecordKind.ASSET,
        1,
        ByteArrayInputStream(header + bytes),
        header.size.toLong() + bytes.size,
        onAssetBytes = { _, _, _, _ -> Unit },
      )
    }
  }
}
