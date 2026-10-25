package com.anan.anannotes.feature.privatemodule

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivateAuthThrottleTest {
  @Test
  fun startsTemporaryCooldownAfterFiveConsecutiveFailures() {
    val store = MemoryThrottleStore()
    val throttle = PrivateAuthThrottle(store)

    repeat(4) { throttle.recordFailure(nowMillis = 10_000L) }

    assertEquals(0L, throttle.remainingMillis(nowMillis = 10_000L))
    throttle.recordFailure(nowMillis = 10_000L)
    assertEquals(30_000L, throttle.remainingMillis(nowMillis = 10_000L))
    assertEquals(1L, throttle.remainingMillis(nowMillis = 39_999L))
    assertEquals(0L, throttle.remainingMillis(nowMillis = 40_000L))
  }

  @Test
  fun laterFailuresIncreaseCooldownAndCapItAtFiveMinutes() {
    val store = MemoryThrottleStore()
    val throttle = PrivateAuthThrottle(store)

    repeat(5) { throttle.recordFailure(nowMillis = 1_000L) }
    assertEquals(30_000L, throttle.remainingMillis(nowMillis = 1_000L))

    throttle.recordFailure(nowMillis = 31_000L)
    assertEquals(60_000L, throttle.remainingMillis(nowMillis = 31_000L))
    throttle.recordFailure(nowMillis = 91_000L)
    assertEquals(120_000L, throttle.remainingMillis(nowMillis = 91_000L))
    throttle.recordFailure(nowMillis = 211_000L)
    assertEquals(240_000L, throttle.remainingMillis(nowMillis = 211_000L))
    throttle.recordFailure(nowMillis = 451_000L)

    assertEquals(300_000L, throttle.remainingMillis(nowMillis = 451_000L))
  }

  @Test
  fun successfulAuthenticationClearsPersistedFailureState() {
    val store = MemoryThrottleStore()
    val firstInstance = PrivateAuthThrottle(store)
    repeat(5) { firstInstance.recordFailure(nowMillis = 5_000L) }

    val afterRecreation = PrivateAuthThrottle(store)
    assertEquals(30_000L, afterRecreation.remainingMillis(nowMillis = 5_000L))
    afterRecreation.recordSuccess()

    val nextInstance = PrivateAuthThrottle(store)
    assertEquals(0L, nextInstance.remainingMillis(nowMillis = 5_000L))
    nextInstance.recordFailure(nowMillis = 5_000L)
    assertEquals(0L, nextInstance.remainingMillis(nowMillis = 5_000L))
  }

  private class MemoryThrottleStore : PrivateAuthThrottleStore {
    private var state = PrivateAuthThrottleState()

    override fun read(): PrivateAuthThrottleState = state

    override fun write(state: PrivateAuthThrottleState) {
      this.state = state
    }
  }
}
