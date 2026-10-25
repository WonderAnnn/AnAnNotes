package com.anan.anannotes.feature.imports

import android.content.Intent
import android.net.Uri
import java.util.UUID

data class ShareImportPayload(
  val requestId: String,
  val text: String?,
  val uris: List<Uri>,
)

object ShareImportHandler {
  private const val MAX_SHARED_ITEMS = 20

  fun parse(intent: Intent): ShareImportPayload? {
    if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return null
    val mimeType = intent.type?.substringBefore(';')?.trim()?.lowercase().orEmpty()
    if (!isSupportedShareMime(mimeType)) return null

    val uris = linkedSetOf<Uri>()
    var clipText: String? = null
    intent.clipData?.let { clip ->
      for (index in 0 until clip.itemCount) {
        val item = clip.getItemAt(index)
        item.uri?.let(uris::add)
        if (clipText == null) clipText = item.text?.toString()
      }
    }
    @Suppress("DEPRECATION")
    when (intent.action) {
      Intent.ACTION_SEND -> (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)?.let(uris::add)
      Intent.ACTION_SEND_MULTIPLE -> {
        val streams = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        uris.addAll(streams)
      }
    }

    val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() ?: clipText
    if (text.isNullOrEmpty() && uris.isEmpty()) return null
    if (text?.toByteArray(Charsets.UTF_8)?.size?.let { it > ImportTextRules.MAX_TEXT_BYTES } == true) return null

    return ShareImportPayload(
      requestId = UUID.randomUUID().toString(),
      text = text,
      uris = uris.take(MAX_SHARED_ITEMS),
    )
  }

  private fun isSupportedShareMime(mimeType: String): Boolean =
    mimeType == "text/plain" ||
      mimeType == "text/markdown" ||
      mimeType == "text/x-markdown" ||
      mimeType == "application/markdown" ||
      mimeType == "application/x-markdown" ||
      mimeType.startsWith("image/")
}
