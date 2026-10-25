package com.anan.anannotes.data.repository

import com.anan.anannotes.core.DisplaySettingsRules
import com.anan.anannotes.core.model.DisplaySettings
import com.anan.anannotes.core.model.ThemeMode
import com.anan.anannotes.data.local.AppDisplaySettingsDao
import com.anan.anannotes.data.local.AppDisplaySettingsEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DisplaySettingsRepository(private val dao: AppDisplaySettingsDao) {
  fun observe(): Flow<DisplaySettings> = dao.observe().map { entity -> entity?.toModel() ?: DisplaySettings() }

  suspend fun save(settings: DisplaySettings) {
    val normalized = DisplaySettingsRules.normalize(settings)
    dao.upsert(
      AppDisplaySettingsEntity(
        defaultFontSizeSp = normalized.defaultFontSizeSp,
        lineSpacingMultiplier = normalized.lineSpacingMultiplier,
        themeMode = normalized.themeMode.name,
        defaultSkinId = normalized.defaultSkinId,
      ),
    )
  }

  private fun AppDisplaySettingsEntity.toModel(): DisplaySettings = DisplaySettingsRules.normalize(
    DisplaySettings(
      defaultFontSizeSp = defaultFontSizeSp,
      lineSpacingMultiplier = lineSpacingMultiplier,
      themeMode = runCatching { ThemeMode.valueOf(themeMode) }.getOrDefault(ThemeMode.SYSTEM),
      defaultSkinId = defaultSkinId,
    ),
  )
}
