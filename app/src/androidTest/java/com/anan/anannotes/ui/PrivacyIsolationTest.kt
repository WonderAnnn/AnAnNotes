package com.anan.anannotes.ui

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anan.anannotes.data.local.AnAnDatabase
import com.anan.anannotes.data.repository.SearchRepository
import com.anan.anannotes.feature.privatemodule.PrivateEditSnapshot
import com.anan.anannotes.feature.privatemodule.PrivateRepository
import com.anan.anannotes.feature.privatemodule.PrivateSaveResult
import com.anan.anannotes.feature.privatemodule.PrivateStore
import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.NoteBlockDraft
import com.anan.anannotes.security.CryptoBytes
import com.anan.anannotes.security.KeyDeriver
import com.anan.anannotes.security.PrivateCrypto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@RunWith(AndroidJUnit4::class)
class PrivacyIsolationTest {
  @Test
  fun privateContentStaysOutOfOrdinarySearchAndFiles() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val database = Room.inMemoryDatabaseBuilder(context, AnAnDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    val filesRoot = Files.createTempDirectory(context.filesDir.toPath(), "anan-privacy-test-").toFile()
    try {
      val repository = PrivateRepository(PrivateStore(filesRoot, testCrypto()), nowMillis = { 100L })
      val access = repository.setup("private-password".toCharArray(), null)
      val title = "private-only-title-938"
      val body = "private-only-body-274"
      val result = repository.save(
        access,
        PrivateEditSnapshot(
          draftId = "private-draft",
          noteId = null,
          folderId = "private-uncategorized",
          title = title,
          titleManuallySet = true,
          blocks = listOf(NoteBlockDraft(type = BlockType.TEXT, text = body)),
          skinId = "minimal-white",
          baseContentRevision = null,
          localRevision = 1L,
          savedAtMillis = 90L,
        ),
      )
      assertTrue(result is PrivateSaveResult.Saved)
      assertTrue(repository.search(access, "private-only-body").isNotEmpty())
      assertTrue(SearchRepository(database).search("private-only-title").isEmpty())
      assertTrue(SearchRepository(database).search("private-only-body").isEmpty())
      assertTrue(filesRoot.walkTopDown().filter(File::isFile).none { file ->
        val bytes = file.readBytes().toString(Charsets.ISO_8859_1)
        bytes.contains(title) || bytes.contains(body)
      })
      repository.lock()
      assertTrue(!access.isActive)
    } finally {
      database.close()
      filesRoot.deleteRecursively()
    }
  }

  private fun testCrypto(): PrivateCrypto {
    val kdf = KeyDeriver { credential, salt, _ ->
      val mac = Mac.getInstance("HmacSHA256")
      mac.init(SecretKeySpec(salt.copyBytes(), "HmacSHA256"))
      val bytes = String(credential).toByteArray(Charsets.UTF_8)
      try {
        mac.doFinal(bytes)
      } finally {
        bytes.fill(0)
      }
    }
    return PrivateCrypto(SecureRandom(), kdf)
  }
}
