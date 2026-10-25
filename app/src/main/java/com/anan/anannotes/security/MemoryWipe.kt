package com.anan.anannotes.security

/** Best-effort clearing for mutable secret buffers; managed runtimes may retain copies. */
internal object MemoryWipe {
  fun bytes(value: ByteArray?) {
    value?.fill(0)
  }

  fun chars(value: CharArray?) {
    value?.fill('\u0000')
  }
}

/** Revocable in-memory capability; callers can use a key only inside a scoped callback. */
class PrivateAccess internal constructor(dataKey: ByteArray) {
  private val monitor = Any()
  private val key = dataKey.clone()
  @Volatile private var revoked = false

  val isActive: Boolean get() = !revoked

  internal fun <T> withDataKey(block: (ByteArray) -> T): T = synchronized(monitor) {
    check(!revoked) { "Private access has been revoked" }
    block(key)
  }

  fun revoke() = synchronized(monitor) {
    if (!revoked) {
      revoked = true
      MemoryWipe.bytes(key)
    }
  }
}

/** Immutable-by-interface byte value with defensive copies and content equality. */
class CryptoBytes private constructor(private val value: ByteArray) {
  val size: Int get() = value.size

  fun copyBytes(): ByteArray = value.clone()

  override fun equals(other: Any?): Boolean = other is CryptoBytes && value.contentEquals(other.value)

  override fun hashCode(): Int = value.contentHashCode()

  override fun toString(): String = "CryptoBytes(size=$size)"

  companion object {
    fun of(value: ByteArray): CryptoBytes = CryptoBytes(value.clone())
  }
}
