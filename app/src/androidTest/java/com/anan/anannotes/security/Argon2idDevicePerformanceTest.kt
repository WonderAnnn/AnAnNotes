package com.anan.anannotes.security

import android.app.ActivityManager
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Focused, non-gating measurement for the V1 Argon2id profile on a connected Android device. */
@RunWith(AndroidJUnit4::class)
class Argon2idDevicePerformanceTest {
  @Test
  fun defaultV1ProfileReportsDerivationTimeAndPeakProcessPss() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val activityManager = context.getSystemService(ActivityManager::class.java)
    val deviceMemory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
    Log.i(
      TAG,
      "runtimeMaxBytes=${Runtime.getRuntime().maxMemory()} " +
        "memoryClassMiB=${activityManager.memoryClass} " +
        "largeMemoryClassMiB=${activityManager.largeMemoryClass} " +
        "lowRam=${activityManager.isLowRamDevice} " +
        "deviceAvailableBytes=${deviceMemory.availMem} " +
        "deviceTotalBytes=${deviceMemory.totalMem} " +
        "deviceLowMemory=${deviceMemory.lowMemory}",
    )

    val deriver = Argon2idKeyDeriver()
    val parameters = KdfParameters.DEFAULT
    val salt = CryptoBytes.of(ByteArray(16) { index -> (index * 13 + 7).toByte() })
    val password = "AnAn Notes local KDF measurement".toCharArray()
    val sampling = AtomicBoolean(true)
    val peakPssKiB = AtomicInteger(0)
    val sampler = Thread {
      while (sampling.get()) {
        val memoryInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memoryInfo)
        peakPssKiB.updateAndGet { previous -> maxOf(previous, memoryInfo.totalPss) }
        try {
          Thread.sleep(PSS_SAMPLE_INTERVAL_MILLIS)
        } catch (_: InterruptedException) {
          break
        }
      }
    }.apply {
      name = "AnAn-KdfPssSampler"
      isDaemon = true
    }

    val baselinePssKiB = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss
    sampler.start()
    val elapsedMillis: Long
    try {
      elapsedMillis = deriveAndMeasure(deriver, password, salt, parameters)
    } finally {
      sampling.set(false)
      sampler.interrupt()
      sampler.join(SAMPLER_JOIN_TIMEOUT_MILLIS)
      MemoryWipe.chars(password)
    }

    Log.i(
      TAG,
      "profile=Argon2id-v1.3 memoryKiB=${parameters.memoryKiB} " +
        "iterations=${parameters.iterations} parallelism=${parameters.parallelism}; " +
        "runMs=$elapsedMillis " +
        "baselinePssKiB=$baselinePssKiB peakPssKiB=${peakPssKiB.get()}",
    )
  }

  private fun deriveAndMeasure(
    deriver: Argon2idKeyDeriver,
    password: CharArray,
    salt: CryptoBytes,
    parameters: KdfParameters,
  ): Long {
    val startedAt = SystemClock.elapsedRealtimeNanos()
    val derived = deriver.derive(password, salt, parameters)
    val elapsedMillis = (SystemClock.elapsedRealtimeNanos() - startedAt) / NANOS_PER_MILLI
    try {
      assertEquals(parameters.outputBytes, derived.size)
      return elapsedMillis
    } finally {
      MemoryWipe.bytes(derived)
    }
  }

  private companion object {
    const val TAG = "AnAnKdfPerf"
    const val PSS_SAMPLE_INTERVAL_MILLIS = 5L
    const val SAMPLER_JOIN_TIMEOUT_MILLIS = 2_000L
    const val NANOS_PER_MILLI = 1_000_000L
  }
}
