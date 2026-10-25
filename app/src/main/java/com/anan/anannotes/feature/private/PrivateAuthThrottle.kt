package com.anan.anannotes.feature.privatemodule

import android.content.Context

data class PrivateAuthThrottleState(
  val consecutiveFailures: Int = 0,
  val blockedUntilMillis: Long = 0L,
)

interface PrivateAuthThrottleStore {
  fun read(): PrivateAuthThrottleState
  fun write(state: PrivateAuthThrottleState)
}

/** A small local backoff counter; it stores no credential or private note data. */
class PrivateAuthThrottle(private val store: PrivateAuthThrottleStore) {
  fun remainingMillis(nowMillis: Long): Long =
    (store.read().blockedUntilMillis - nowMillis).coerceAtLeast(0L)

  fun recordFailure(nowMillis: Long) {
    val failures = (store.read().consecutiveFailures + 1).coerceAtMost(MAX_FAILURE_COUNT)
    val delay = when {
      failures < FIRST_DELAY_FAILURE -> 0L
      else -> (INITIAL_DELAY_MILLIS shl (failures - FIRST_DELAY_FAILURE).coerceAtMost(MAX_SHIFT))
        .coerceAtMost(MAX_DELAY_MILLIS)
    }
    store.write(PrivateAuthThrottleState(failures, nowMillis + delay))
  }

  fun recordSuccess() = store.write(PrivateAuthThrottleState())

  private companion object {
    const val FIRST_DELAY_FAILURE = 5
    const val INITIAL_DELAY_MILLIS = 30_000L
    const val MAX_DELAY_MILLIS = 5 * 60_000L
    const val MAX_FAILURE_COUNT = 32
    const val MAX_SHIFT = 10
  }
}

class SharedPreferencesPrivateAuthThrottleStore(context: Context) : PrivateAuthThrottleStore {
  private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

  override fun read(): PrivateAuthThrottleState = PrivateAuthThrottleState(
    consecutiveFailures = preferences.getInt(KEY_FAILURES, 0).coerceIn(0, MAX_FAILURE_COUNT),
    blockedUntilMillis = preferences.getLong(KEY_BLOCKED_UNTIL, 0L).coerceAtLeast(0L),
  )

  override fun write(state: PrivateAuthThrottleState) {
    check(
      preferences.edit()
        .putInt(KEY_FAILURES, state.consecutiveFailures.coerceIn(0, MAX_FAILURE_COUNT))
        .putLong(KEY_BLOCKED_UNTIL, state.blockedUntilMillis.coerceAtLeast(0L))
        .commit(),
    ) { "Could not persist local authentication backoff" }
  }

  private companion object {
    const val PREFERENCES_NAME = "private-auth-rate-limit"
    const val KEY_FAILURES = "consecutive-failures"
    const val KEY_BLOCKED_UNTIL = "blocked-until-millis"
    const val MAX_FAILURE_COUNT = 32
  }
}
