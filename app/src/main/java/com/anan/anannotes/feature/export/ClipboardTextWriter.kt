package com.anan.anannotes.feature.export

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle

/** Writes plain-text note content without exposing the note title as clipboard metadata. */
object ClipboardTextWriter {
  fun copy(context: Context, text: String, sensitive: Boolean) {
    val clip = createClip(text, sensitive)
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(clip)
  }

  /** Builds metadata before handing the clip to Android, whose SystemUI may add or rewrite extras. */
  internal fun createClip(text: String, sensitive: Boolean): ClipData {
    require(text.isNotEmpty()) { "复制内容不能为空" }

    val clip = ClipData.newPlainText("安安简记", text)
    if (sensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      clip.description.extras = PersistableBundle().apply {
        putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
      }
    }
    return clip
  }
}
