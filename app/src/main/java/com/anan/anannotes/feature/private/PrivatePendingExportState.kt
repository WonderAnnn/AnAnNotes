package com.anan.anannotes.feature.privatemodule

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import com.anan.anannotes.core.model.ExportFormat
import com.anan.anannotes.feature.export.OutputTarget

internal data class PrivatePendingExportState(
  val format: ExportFormat? = null,
  val includeAttribution: Boolean = false,
  val noteId: PrivateNoteId? = null,
  val target: OutputTarget? = null,
) {
  companion object {
    val Saver: Saver<PrivatePendingExportState, Any> = listSaver(
      save = { state ->
        val (targetKind, targetUri) = when (val target = state.target) {
          is OutputTarget.DocumentTree -> "tree" to target.uriString
          OutputTarget.MediaStorePictures -> "media-store" to null
          null -> null to null
        }
        listOf<Any?>(
          state.format?.name,
          state.includeAttribution,
          state.noteId?.value,
          targetKind,
          targetUri,
        )
      },
      restore = { values ->
        val format = (values.getOrNull(0) as? String)
          ?.let { name -> ExportFormat.entries.firstOrNull { it.name == name } }
        val includeAttribution = values.getOrNull(1) as? Boolean ?: false
        val noteId = (values.getOrNull(2) as? String)
          ?.takeIf(String::isNotBlank)
          ?.let(::PrivateNoteId)
        val target = when (values.getOrNull(3) as? String) {
          "tree" -> (values.getOrNull(4) as? String)?.let(OutputTarget::DocumentTree)
          "media-store" -> OutputTarget.MediaStorePictures
          else -> null
        }
        PrivatePendingExportState(format, includeAttribution, noteId, target)
      },
    )
  }
}

@Composable
internal fun rememberPrivatePendingExportState(): MutableState<PrivatePendingExportState> =
  rememberSaveable(stateSaver = PrivatePendingExportState.Saver) {
    mutableStateOf(PrivatePendingExportState())
  }
