package com.anan.anannotes.feature.backup

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anan.anannotes.MainActivity
import com.anan.anannotes.data.files.OriginalImageStore
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupRestoreColdStartRecoveryTest {
  @Test
  fun seedUncommittedRestoreForProcessRestart() {
    assumePhase("seed")
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val transactionId = "123e4567-e89b-12d3-a456-426614174010"
    val assetId = "123e4567-e89b-12d3-a456-426614174011"
    val journal = BackupRestoreJournal(context.noBackupFilesDir)
    val imageStore = OriginalImageStore(context.filesDir)
    val stagedGroup = File(context.filesDir, "${OriginalImageStore.ASSET_DIRECTORY}/restore-$transactionId")
    val image = byteArrayOf(1, 3, 3, 7, 9, 2, 4, 6)

    assertNull("Test requires no other pending restore journal", journal.read())
    journal.begin(transactionId)
    journal.advance(transactionId, BackupRestorePhase.PREPARED)
    imageStore.importRestore(
      transactionId = transactionId,
      assetId = assetId,
      input = ByteArrayInputStream(image),
      extension = "png",
      expectedByteCount = image.size.toLong(),
      expectedSha256 = MessageDigest.getInstance("SHA-256").digest(image),
    )
    assertTrue(stagedGroup.isDirectory)
  }

  @Test
  fun coldStartRollsBackPersistedRestoreBeforeShowingMainActivity() {
    assumePhase("verify")
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val transactionId = "123e4567-e89b-12d3-a456-426614174010"
    val journal = BackupRestoreJournal(context.noBackupFilesDir)
    val stagedGroup = File(context.filesDir, "${OriginalImageStore.ASSET_DIRECTORY}/restore-$transactionId")
    val intent = Intent(context, MainActivity::class.java)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    val activity = instrumentation.startActivitySync(intent)
    assertNotNull(activity)

    val deadline = android.os.SystemClock.uptimeMillis() + 15_000L
    while (journal.read() != null && android.os.SystemClock.uptimeMillis() < deadline) {
      android.os.SystemClock.sleep(50L)
    }

    assertNull("Startup recovery must finish the interrupted transaction", journal.read())
    assertFalse("Transaction-owned partial images must be removed", stagedGroup.exists())
  }

  private fun assumePhase(expected: String) {
    val actual = InstrumentationRegistry.getArguments().getString(PHASE_ARGUMENT)
    assumeTrue("Run this two-phase cold-start test with $PHASE_ARGUMENT=$expected", actual == expected)
  }

  private companion object {
    const val PHASE_ARGUMENT = "c5RestartPhase"
  }
}
