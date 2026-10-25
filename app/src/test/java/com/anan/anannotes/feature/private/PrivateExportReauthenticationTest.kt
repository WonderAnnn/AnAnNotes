package com.anan.anannotes.feature.privatemodule

import com.anan.anannotes.core.model.ExportFormat
import com.anan.anannotes.security.PrivateAccess
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateExportReauthenticationTest {
  @Test
  fun wrongPasswordsUseSharedCooldownAndBlockedAttemptSkipsPasswordVerification() = runBlocking {
    val throttleStore = MemoryThrottleStore()
    val throttle = PrivateAuthThrottle(throttleStore)
    val now = 10_000L
    val access = PrivateAccess(ByteArray(32) { 7 })
    val gate = PrivateExportReauthentication(throttle) { now }
    val scope = PrivateExportScope(PrivateNoteId("private-note"), ExportFormat.PDF, "content://tree")
    var verificationCalls = 0

    repeat(5) { attempt ->
      val result = gate.authorize(access, "wrong-$attempt".toCharArray()) {
        verificationCalls++
        null
      }
      assertTrue(result is PrivateExportAuthorizationResult.Rejected)
    }

    val blockedPassword = "correct-password".toCharArray()
    val blocked = gate.authorize(access, blockedPassword) {
      verificationCalls++
      null
    }

    assertEquals(5, verificationCalls)
    assertEquals(30_000L, (blocked as PrivateExportAuthorizationResult.Throttled).retryAfterMillis)
    assertTrue(blockedPassword.all { it == '\u0000' })
    access.revoke()
  }

  @Test
  fun successfulReauthenticationResetsTheSameFailureCounterUsedByVaultUnlock() = runBlocking {
    val store = MemoryThrottleStore()
    val throttle = PrivateAuthThrottle(store)
    val gate = PrivateExportReauthentication(throttle) { 20_000L }
    val access = PrivateAccess(ByteArray(32) { 9 })
    val scope = PrivateExportScope(PrivateNoteId("private-note"), ExportFormat.TXT, "content://tree")

    repeat(4) {
      gate.authorize(access, "wrong".toCharArray()) { null }
    }
    val grant = PrivateExportGrant.issue(access, scope, issuedAtMillis = 20_000L)
    val result = gate.authorize(access, "right".toCharArray()) { grant }

    assertTrue(result is PrivateExportAuthorizationResult.Authorized)
    assertEquals(0L, throttle.remainingMillis(20_000L))
    access.revoke()
  }

  private class MemoryThrottleStore : PrivateAuthThrottleStore {
    private var state = PrivateAuthThrottleState()

    override fun read(): PrivateAuthThrottleState = state

    override fun write(state: PrivateAuthThrottleState) {
      this.state = state
    }
  }
}
