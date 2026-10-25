package com.anan.anannotes.feature.backup

/** Owns one persistable SAF tree grant for the lifetime of the screen that selected it. */
internal class PersistedTreeGrantLease(
  private val releaseGrant: (String) -> Unit,
) {
  private var currentUri: String? = null

  fun record(uri: String) {
    require(uri.isNotBlank())
    val previousUri = currentUri
    if (previousUri == uri) return
    currentUri = uri
    previousUri?.let(::releaseSafely)
  }

  fun close() {
    val uri = currentUri ?: return
    currentUri = null
    releaseSafely(uri)
  }

  private fun releaseSafely(uri: String) {
    runCatching { releaseGrant(uri) }
  }
}
