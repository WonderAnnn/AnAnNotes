package com.anan.anannotes.feature.privatemodule

import com.anan.anannotes.security.MemoryWipe
import com.anan.anannotes.security.PrivateAccess

sealed interface PrivateExportAuthorizationResult {
  data class Authorized(val grant: PrivateExportGrant) : PrivateExportAuthorizationResult
  data class Rejected(val retryAfterMillis: Long) : PrivateExportAuthorizationResult
  data class Throttled(val retryAfterMillis: Long) : PrivateExportAuthorizationResult
  data object Inactive : PrivateExportAuthorizationResult
}

/** Applies the same persisted local failure backoff used by vault unlock to export reauthentication. */
class PrivateExportReauthentication(
  private val throttle: PrivateAuthThrottle,
  private val nowMillis: () -> Long = System::currentTimeMillis,
) {
  suspend fun authorize(
    access: PrivateAccess,
    password: CharArray,
    verify: suspend (CharArray) -> PrivateExportGrant?,
  ): PrivateExportAuthorizationResult {
    if (!access.isActive) {
      MemoryWipe.chars(password)
      return PrivateExportAuthorizationResult.Inactive
    }

    val startedAt = nowMillis()
    val remaining = throttle.remainingMillis(startedAt)
    if (remaining > 0L) {
      MemoryWipe.chars(password)
      return PrivateExportAuthorizationResult.Throttled(remaining)
    }

    return try {
      val grant = verify(password)
      if (!access.isActive) {
        PrivateExportAuthorizationResult.Inactive
      } else if (grant == null) {
        throttle.recordFailure(nowMillis())
        PrivateExportAuthorizationResult.Rejected(throttle.remainingMillis(nowMillis()))
      } else {
        throttle.recordSuccess()
        PrivateExportAuthorizationResult.Authorized(grant)
      }
    } finally {
      MemoryWipe.chars(password)
    }
  }
}
