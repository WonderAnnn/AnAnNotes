package com.anan.anannotes.feature.privatemodule

import java.io.File

/** Shares one mutex-owning private store instance across private UI and backup operations. */
internal object PrivateStoreInstances {
  private val stores = mutableMapOf<String, PrivateStore>()

  fun get(filesDirectory: File): PrivateStore {
    val key = filesDirectory.canonicalPath
    return synchronized(stores) {
      stores.getOrPut(key) { PrivateStore(filesDirectory) }
    }
  }
}
