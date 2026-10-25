package com.anan.anannotes.feature.backup

import org.junit.Assert.assertEquals
import org.junit.Test

class PersistedTreeGrantLeaseTest {
  @Test
  fun replacingDirectoryReleasesOldGrantAndClosingReleasesCurrentGrant() {
    val released = mutableListOf<String>()
    val lease = PersistedTreeGrantLease { uri -> released += uri }

    lease.record("content://provider/tree/first")
    lease.record("content://provider/tree/second")

    assertEquals(listOf("content://provider/tree/first"), released)

    lease.close()

    assertEquals(
      listOf("content://provider/tree/first", "content://provider/tree/second"),
      released,
    )
  }

  @Test
  fun recordingSameDirectoryDoesNotReleaseItUntilCloseAndCloseIsIdempotent() {
    val released = mutableListOf<String>()
    val lease = PersistedTreeGrantLease { uri -> released += uri }

    lease.record("content://provider/tree/first")
    lease.record("content://provider/tree/first")
    assertEquals(emptyList<String>(), released)

    lease.close()
    lease.close()

    assertEquals(listOf("content://provider/tree/first"), released)
  }
}
