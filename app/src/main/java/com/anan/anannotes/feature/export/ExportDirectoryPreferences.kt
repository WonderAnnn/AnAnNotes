package com.anan.anannotes.feature.export

import android.content.Context
import androidx.core.content.edit

object ExportDirectoryPreferences {
  private const val PREFERENCES_NAME = "ordinary_export_preferences"
  private const val DIRECTORY_URI_KEY = "directory_tree_uri"

  fun read(context: Context): String? =
    context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).getString(DIRECTORY_URI_KEY, null)

  fun write(context: Context, uriString: String) {
    context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).edit {
      putString(DIRECTORY_URI_KEY, uriString)
    }
  }
}
