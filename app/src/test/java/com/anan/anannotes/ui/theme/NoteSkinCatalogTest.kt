package com.anan.anannotes.ui.theme

import com.anan.anannotes.core.model.ACTIVE_NOTE_SKIN_IDS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteSkinCatalogTest {
  @Test
  fun catalogContainsEveryPreviewedAndExistingSkinWithStableIds() {
    val expectedIds = setOf(
      "minimal-white",
      "warm-paper",
      "mint-green",
      "sakura-petal-paper",
      "bamboo-shadow",
      "misty-tide",
      "osmanthus-note",
      "moonlit-sky",
      "wisteria-cloud",
      "camellia-letter",
      "southern-ginkgo",
    )

    assertEquals(expectedIds, ACTIVE_NOTE_SKIN_IDS)
    assertEquals(expectedIds, NoteSkinCatalog.all.map(NoteSkin::id).toSet())
    assertEquals(
      listOf(
        "纸笺散花",
        "竹影清风",
        "雾蓝潮汐",
        "桂香笺",
        "月下星河",
        "云栖紫藤",
        "茶花信笺",
        "南国银杏",
      ),
      NoteSkinCatalog.all.filter { it.decoration != NoteSkinDecoration.NONE }.map(NoteSkin::name),
    )
  }

  @Test
  fun decorativeInkUsesThePreviewedLowContrastTreatment() {
    val patternedSkins = NoteSkinCatalog.all.filter { it.decoration != NoteSkinDecoration.NONE }

    assertTrue("至少应有一款纹样皮肤", patternedSkins.isNotEmpty())
    patternedSkins.forEach { skin ->
      assertTrue("${skin.id} 的纹样应淡雅，不压正文", skin.decorationColor.alpha <= 0.10f)
    }
  }
}
