package com.anan.anannotes.feature.backup

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.NoteBlockDraft
import com.anan.anannotes.feature.privatemodule.PrivateNoteDocument
import com.anan.anannotes.feature.privatemodule.PrivateNoteId
import com.anan.anannotes.feature.privatemodule.PrivateStoreInstances
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicitly phase-gated fixture for an end-to-end SAF backup/restore on a device. */
@RunWith(AndroidJUnit4::class)
class PrivateSafRoundTripFixtureTest {
  @Test
  fun seedEncryptedPrivateImageBeforeSafBackup() = runBlocking {
    assumePhase("seed")
    val context = ApplicationProvider.getApplicationContext<Context>()
    val store = PrivateStoreInstances.get(context.filesDir)
    val fixturePassword = requiredArgument("c5FixturePassword")
    val access = if (store.isConfigured()) {
      store.unlockWithPassword(fixturePassword.toCharArray())
    } else {
      store.create(fixturePassword.toCharArray(), null)
    }
    try {
      var snapshot = store.readSnapshot(access)
      var noteIndex = snapshot.notes.indexOfFirst { it.title == PRIVATE_NOTE_TITLE }
      if (noteIndex < 0) {
        val now = System.currentTimeMillis()
        val note = PrivateNoteDocument(
          id = PrivateNoteId(java.util.UUID.randomUUID().toString()),
          title = PRIVATE_NOTE_TITLE,
          blocks = listOf(NoteBlockDraft(type = BlockType.TEXT, text = "API 29 private SAF seed")),
          createdAtMillis = now,
          updatedAtMillis = now,
          contentRevision = 1L,
        )
        store.writeSnapshot(access, snapshot.copy(notes = snapshot.notes + note))
        snapshot = store.readSnapshot(access)
        noteIndex = snapshot.notes.indexOfFirst { it.title == PRIVATE_NOTE_TITLE }
      }
      assertTrue("SAF fixture note must be available after seed setup", noteIndex >= 0)

      val png = createFixturePng()
      val reference = store.writePrivateAsset(access, png)
      val note = snapshot.notes[noteIndex]
      val imageBlock = NoteBlockDraft(
        type = BlockType.IMAGE,
        assetPath = reference,
        widthPercent = 64f,
        aspectRatio = 1f,
      )
      store.writeSnapshot(
        access,
        snapshot.copy(notes = snapshot.notes.toMutableList().also { notes ->
          notes[noteIndex] = note.copy(blocks = note.blocks + imageBlock)
        }),
      )

      val encryptedFile = context.filesDir.resolve("private/assets/${reference.removePrefix("private-asset:")}.bin")
      assertTrue("Private attachment must be stored in the private asset vault", encryptedFile.isFile)
      assertTrue("Private attachment must not be stored as plaintext PNG", !encryptedFile.readBytes().contentEquals(png))
    } finally {
      store.lock(access)
    }
  }

  @Test
  fun verifyPrivateImageAfterSafRestoreWithNewPassword() = runBlocking {
    assumePhase("verify")
    val context = ApplicationProvider.getApplicationContext<Context>()
    val store = PrivateStoreInstances.get(context.filesDir)
    val access = store.unlockWithPassword(requiredArgument("c5FixturePassword").toCharArray())
    try {
      val snapshot = store.readSnapshot(access)
      val note = snapshot.notes.firstOrNull { it.title == PRIVATE_NOTE_TITLE }
      assertNotNull("Restored private note must be present", note)
      val image = note!!.blocks.firstOrNull { it.type == BlockType.IMAGE }
      assertNotNull("Restored note must keep its image block", image)
      val decoded = store.openPrivateAssetForExport(access, image!!.assetPath!!).use(BitmapFactory::decodeStream)
      assertNotNull("Restored private image must decrypt and decode", decoded)
      assertEquals(8, decoded!!.width)
      assertEquals(8, decoded.height)
      assertEquals(Bitmap.Config.ARGB_8888, decoded.config)
      assertEquals("Restored attachment pixels must remain intact", 0xff25a7a0.toInt(), decoded.getPixel(0, 0))
      decoded.recycle()
    } finally {
      store.lock(access)
    }
  }

  private fun assumePhase(expected: String) {
    val actual = InstrumentationRegistry.getArguments().getString("c5SafPhase")
    assumeTrue("Run this device fixture with c5SafPhase=$expected", actual == expected)
  }

  private fun requiredArgument(name: String): String =
    requireNotNull(InstrumentationRegistry.getArguments().getString(name)) { "Instrumentation argument $name is required" }

  private fun createFixturePng(): ByteArray {
    val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff25a7a0.toInt()) }
    return ByteArrayOutputStream().use { output ->
      check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
      bitmap.recycle()
      output.toByteArray()
    }
  }

  private companion object {
    const val PRIVATE_NOTE_TITLE = "C5_Private_SAF"
  }
}
