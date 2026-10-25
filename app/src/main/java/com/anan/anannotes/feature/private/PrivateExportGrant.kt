package com.anan.anannotes.feature.privatemodule

import com.anan.anannotes.core.model.ExportFormat
import com.anan.anannotes.security.PrivateAccess

data class PrivateExportScope(
  val noteId: PrivateNoteId,
  val format: ExportFormat,
  val destination: String,
  val includeAttribution: Boolean = false,
)

/** Short-lived, one-shot capability minted only after password reauthentication. */
class PrivateExportGrant internal constructor(
  access: PrivateAccess,
  private val scope: PrivateExportScope,
  private val issuedAtMillis: Long,
  private val lifetimeMillis: Long = DEFAULT_LIFETIME_MILLIS,
) {
  private var boundAccess: PrivateAccess? = access
  private var consumed = false

  init {
    require(issuedAtMillis >= 0L)
    require(lifetimeMillis in 1L..MAX_LIFETIME_MILLIS)
  }

  @Synchronized
  fun consume(
    access: PrivateAccess,
    requestScope: PrivateExportScope,
    nowMillis: Long = System.currentTimeMillis(),
  ): Boolean {
    val sameSession = boundAccess === access
    if (consumed || !sameSession || requestScope != scope || !access.isActive) return false
    if (nowMillis < issuedAtMillis || nowMillis - issuedAtMillis >= lifetimeMillis) {
      boundAccess = null
      consumed = true
      return false
    }
    boundAccess = null
    consumed = true
    return true
  }

  companion object {
    const val DEFAULT_LIFETIME_MILLIS = 60_000L
    private const val MAX_LIFETIME_MILLIS = DEFAULT_LIFETIME_MILLIS

    internal fun issue(
      access: PrivateAccess,
      scope: PrivateExportScope,
      issuedAtMillis: Long = System.currentTimeMillis(),
      lifetimeMillis: Long = DEFAULT_LIFETIME_MILLIS,
    ): PrivateExportGrant = PrivateExportGrant(access, scope, issuedAtMillis, lifetimeMillis)
  }
}
