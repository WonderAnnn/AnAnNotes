package com.anan.anannotes.feature.privatemodule

import com.anan.anannotes.security.PrivateAccess
import com.anan.anannotes.core.model.ExportFormat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateExportGrantTest {
  @Test
  fun grantCanOnlyBeConsumedOnceByTheAccessThatWasReauthenticated() {
    val access = PrivateAccess(ByteArray(32) { 7 })
    val otherAccess = PrivateAccess(ByteArray(32) { 9 })
    val scope = exportScope()
    val grant = PrivateExportGrant.issue(access, scope, issuedAtMillis = 1_000L)

    assertFalse(grant.consume(otherAccess, scope, nowMillis = 1_001L))
    assertFalse(grant.consume(access, scope.copy(format = ExportFormat.PDF), nowMillis = 1_001L))
    assertFalse(grant.consume(access, scope.copy(includeAttribution = true), nowMillis = 1_001L))
    assertTrue(grant.consume(access, scope, nowMillis = 1_001L))
    assertFalse(grant.consume(access, scope, nowMillis = 1_002L))

    access.revoke()
    otherAccess.revoke()
  }

  @Test
  fun grantExpiresAfterItsShortAuthorizationWindow() {
    val access = PrivateAccess(ByteArray(32) { 3 })
    val scope = exportScope()
    val grant = PrivateExportGrant.issue(access, scope, issuedAtMillis = 1_000L, lifetimeMillis = 30_000L)

    assertFalse(grant.consume(access, scope, nowMillis = 31_001L))

    access.revoke()
  }

  @Test
  fun revokingPrivateSessionInvalidatesOutstandingGrant() {
    val access = PrivateAccess(ByteArray(32) { 5 })
    val grant = PrivateExportGrant.issue(access, exportScope(), issuedAtMillis = 1_000L)
    access.revoke()

    assertFalse(grant.consume(access, exportScope(), nowMillis = 1_001L))
  }

  private fun exportScope() = PrivateExportScope(
    noteId = PrivateNoteId("note-to-export"),
    format = ExportFormat.TXT,
    destination = "content://selected/tree",
  )
}
