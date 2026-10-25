package com.anan.anannotes.feature.privatemodule

import android.content.Context
import com.anan.anannotes.feature.export.ClipboardTextWriter

/** Keeps the private-module clipboard payload marked sensitive on supported Android versions. */
object PrivateClipboardWriter {
  fun copy(context: Context, text: String) {
    ClipboardTextWriter.copy(context, text, sensitive = true)
  }
}
