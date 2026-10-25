package com.anan.anannotes.feature.imports

import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportTextRulesTest {
  @Test
  fun acceptsOnlyPlainTextAndMarkdownDocuments() {
    assertTrue(ImportTextRules.isSupportedTextFile("memo.txt", "text/plain"))
    assertTrue(ImportTextRules.isSupportedTextFile("notes.MD", "application/octet-stream"))
    assertTrue(ImportTextRules.isSupportedTextFile("notes", "text/markdown"))
    assertFalse(ImportTextRules.isSupportedTextFile("scan.pdf", "application/pdf"))
    assertFalse(ImportTextRules.isSupportedTextFile("photo.jpg", "image/jpeg"))
  }

  @Test
  fun pickerTypesIncludeMarkdownMimeVariantsAndGenericMdProviders() {
    val types = ImportTextRules.pickerMimeTypes().toSet()

    assertTrue("text/*" in types)
    assertTrue("image/*" in types)
    assertTrue("application/markdown" in types)
    assertTrue("application/x-markdown" in types)
    assertTrue("application/octet-stream" in types)
  }

  @Test
  fun acceptsMarkdownWhenAProviderReportsPlainTextMimeWithCharset() {
    assertTrue(ImportTextRules.isSupportedTextFile("notes.md", "text/plain; charset=utf-8"))
  }

  @Test
  fun decodesUtf8AndPreservesOriginalLineBreaks() {
    val bytes = "第一行\r\n\r\n第二行\n末行".toByteArray(StandardCharsets.UTF_8)

    val imported = ImportTextRules.readUtf8("笔记.md", "text/markdown", ByteArrayInputStream(bytes))

    assertEquals("第一行\r\n\r\n第二行\n末行", imported)
  }

  @Test
  fun removesUtf8BomWithoutChangingTheFirstLine() {
    val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "标题\n正文".toByteArray()

    val imported = ImportTextRules.readUtf8("note.txt", "text/plain", ByteArrayInputStream(bytes))

    assertEquals("标题\n正文", imported)
  }

  @Test
  fun readsAllBytesWhenTheProviderReturnsShortChunksAcrossUtf8Characters() {
    val expected = "标题😀\r\n正文"
    val bytes = expected.toByteArray(StandardCharsets.UTF_8)
    val shortReadInput = object : FilterInputStream(ByteArrayInputStream(bytes)) {
      override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        super.read(buffer, offset, minOf(length, 2))
    }

    assertEquals(expected, ImportTextRules.readUtf8("notes.md", "text/markdown", shortReadInput))
  }

  @Test
  fun acceptsExactlyTheFiveMegabyteLimit() {
    val expected = "a".repeat(ImportTextRules.MAX_TEXT_BYTES)

    val imported = ImportTextRules.readUtf8(
      "limit.md",
      "text/markdown",
      ByteArrayInputStream(expected.toByteArray(StandardCharsets.UTF_8)),
    )

    assertEquals(expected, imported)
  }

  @Test
  fun rejectsUtf8BomWithoutAnyDocumentText() {
    val bomOnly = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    val error = assertThrows(IOException::class.java) {
      ImportTextRules.readUtf8("empty.md", "text/markdown", ByteArrayInputStream(bomOnly))
    }

    assertEquals("文本文件没有内容", error.message)
  }

  @Test
  fun rejectsTruncatedUtf8InsteadOfImportingReplacementCharacters() {
    val truncatedCharacter = byteArrayOf(0xE4.toByte(), 0xB8.toByte())

    assertThrows(IOException::class.java) {
      ImportTextRules.readUtf8(
        "broken.md",
        "text/markdown",
        ByteArrayInputStream(truncatedCharacter),
      )
    }
  }

  @Test
  fun rejectsUnsupportedFilesAndOversizedDocumentsInsteadOfImportingPartialText() {
    assertThrows(IOException::class.java) {
      ImportTextRules.readUtf8("book.pdf", "application/pdf", ByteArrayInputStream(byteArrayOf(1)))
    }
    val oversized = ByteArray(ImportTextRules.MAX_TEXT_BYTES + 1)
    assertThrows(IOException::class.java) {
      ImportTextRules.readUtf8("large.txt", "text/plain", ByteArrayInputStream(oversized))
    }
  }
}
