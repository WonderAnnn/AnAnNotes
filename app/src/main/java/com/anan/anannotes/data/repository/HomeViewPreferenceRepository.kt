package com.anan.anannotes.data.repository

import com.anan.anannotes.core.model.NoteSortOrder
import com.anan.anannotes.data.local.HomeViewPreferenceDao
import com.anan.anannotes.data.local.HomeViewPreferenceEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class HomeViewPreferenceRepository(private val dao: HomeViewPreferenceDao) {
  fun observeSortOrder(viewId: String): Flow<NoteSortOrder> =
    dao.observeByView(viewId)
      .map { preference ->
        NoteSortOrder.entries.firstOrNull { it.name == preference?.sortOrder } ?: NoteSortOrder.RECENT_EDITED
      }
      .distinctUntilChanged()

  suspend fun setSortOrder(viewId: String, order: NoteSortOrder) {
    dao.upsert(HomeViewPreferenceEntity(viewId = viewId, sortOrder = order.name))
  }
}
