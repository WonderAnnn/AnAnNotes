package com.anan.anannotes.ui.theme

import androidx.compose.ui.graphics.Color
import com.anan.anannotes.core.model.BAMBOO_SHADOW_SKIN_ID
import com.anan.anannotes.core.model.CAMELLIA_LETTER_SKIN_ID
import com.anan.anannotes.core.model.DEFAULT_NOTE_SKIN_ID
import com.anan.anannotes.core.model.MISTY_TIDE_SKIN_ID
import com.anan.anannotes.core.model.MOONLIT_SKY_SKIN_ID
import com.anan.anannotes.core.model.OSMANTHUS_NOTE_SKIN_ID
import com.anan.anannotes.core.model.SAKURA_PETAL_SKIN_ID
import com.anan.anannotes.core.model.SOUTHERN_GINKGO_SKIN_ID
import com.anan.anannotes.core.model.WISTERIA_CLOUD_SKIN_ID
import com.anan.anannotes.core.model.canonicalNoteSkinId

enum class NoteSkinDecoration {
  NONE,
  SAKURA_PETALS,
  BAMBOO_SHADOWS,
  TIDE_RIPPLES,
  OSMANTHUS_BLOSSOMS,
  MOONLIT_STARS,
  WISTERIA_BLOOMS,
  CAMELLIA_BLOSSOMS,
  GINKGO_LEAVES,
}

data class NoteSkin(
  val id: String,
  val name: String,
  val background: Color,
  val surface: Color,
  val foreground: Color,
  val secondaryText: Color,
  val divider: Color,
  val accent: Color,
  val decoration: NoteSkinDecoration = NoteSkinDecoration.NONE,
  val decorationColor: Color = Color.Transparent,
)

object NoteSkinCatalog {
  val all = listOf(
    NoteSkin(
      id = DEFAULT_NOTE_SKIN_ID,
      name = "极简白",
      background = Color(0xFFF7F8F9),
      surface = Color(0xFFFFFFFF),
      foreground = Color(0xFF202428),
      secondaryText = Color(0xFF69717A),
      divider = Color(0xFFD9DDE1),
      accent = Color(0xFF718292),
    ),
    NoteSkin(
      id = "warm-paper",
      name = "米白纸张",
      background = Color(0xFFF5F1E9),
      surface = Color(0xFFFCFAF5),
      foreground = Color(0xFF393833),
      secondaryText = Color(0xFF777268),
      divider = Color(0xFFE1DBCF),
      accent = Color(0xFF7B8578),
    ),
    NoteSkin(
      id = "mint-green",
      name = "薄荷清新",
      background = Color(0xFFEEF5F1),
      surface = Color(0xFFF9FCFA),
      foreground = Color(0xFF26372F),
      secondaryText = Color(0xFF62766C),
      divider = Color(0xFFD4E1D9),
      accent = Color(0xFF5E806F),
    ),
    NoteSkin(
      id = SAKURA_PETAL_SKIN_ID,
      name = "纸笺散花",
      background = Color(0xFFFFF7F8),
      surface = Color(0xFFFFFCFC),
      foreground = Color(0xFF352D31),
      secondaryText = Color(0xFF766970),
      divider = Color(0xFFE8D6DC),
      accent = Color(0xFFB9657C),
      decoration = NoteSkinDecoration.SAKURA_PETALS,
      decorationColor = Color(0x15D88399),
    ),
    NoteSkin(
      id = BAMBOO_SHADOW_SKIN_ID,
      name = "竹影清风",
      background = Color(0xFFF5F5EF),
      surface = Color(0xFFFCFCF9),
      foreground = Color(0xFF343931),
      secondaryText = Color(0xFF73796F),
      divider = Color(0xFFE5E5DB),
      accent = Color(0xFF758271),
      decoration = NoteSkinDecoration.BAMBOO_SHADOWS,
      decorationColor = Color(0x14778B72),
    ),
    NoteSkin(
      id = MISTY_TIDE_SKIN_ID,
      name = "雾蓝潮汐",
      background = Color(0xFFF2F6F7),
      surface = Color(0xFFFCFDFE),
      foreground = Color(0xFF303940),
      secondaryText = Color(0xFF6C7C81),
      divider = Color(0xFFE0E8EA),
      accent = Color(0xFF829AA3),
      decoration = NoteSkinDecoration.TIDE_RIPPLES,
      decorationColor = Color(0x126596A8),
    ),
    NoteSkin(
      id = OSMANTHUS_NOTE_SKIN_ID,
      name = "桂香笺",
      background = Color(0xFFFBF8EE),
      surface = Color(0xFFFFFDF8),
      foreground = Color(0xFF39372E),
      secondaryText = Color(0xFF787262),
      divider = Color(0xFFECE7D9),
      accent = Color(0xFFA99A78),
      decoration = NoteSkinDecoration.OSMANTHUS_BLOSSOMS,
      decorationColor = Color(0x158D7742),
    ),
    NoteSkin(
      id = MOONLIT_SKY_SKIN_ID,
      name = "月下星河",
      background = Color(0xFF252B38),
      surface = Color(0xFF2C3442),
      foreground = Color(0xFFF1F2F4),
      secondaryText = Color(0xFFBDC3CC),
      divider = Color(0xFF3C4553),
      accent = Color(0xFFADB7C1),
      decoration = NoteSkinDecoration.MOONLIT_STARS,
      decorationColor = Color(0x1796A1AB),
    ),
    NoteSkin(
      id = WISTERIA_CLOUD_SKIN_ID,
      name = "云栖紫藤",
      background = Color(0xFFF7F5FA),
      surface = Color(0xFFFFFEFF),
      foreground = Color(0xFF37323C),
      secondaryText = Color(0xFF736C78),
      divider = Color(0xFFE8E4ED),
      accent = Color(0xFF9A8FA3),
      decoration = NoteSkinDecoration.WISTERIA_BLOOMS,
      decorationColor = Color(0x14876FA1),
    ),
    NoteSkin(
      id = CAMELLIA_LETTER_SKIN_ID,
      name = "茶花信笺",
      background = Color(0xFFF8F4EE),
      surface = Color(0xFFFFFCF7),
      foreground = Color(0xFF3A3633),
      secondaryText = Color(0xFF786F66),
      divider = Color(0xFFE8E1D7),
      accent = Color(0xFFAC8B84),
      decoration = NoteSkinDecoration.CAMELLIA_BLOSSOMS,
      decorationColor = Color(0x14A67C76),
    ),
    NoteSkin(
      id = SOUTHERN_GINKGO_SKIN_ID,
      name = "南国银杏",
      background = Color(0xFFFBF9F1),
      surface = Color(0xFFFFFEF9),
      foreground = Color(0xFF3B3932),
      secondaryText = Color(0xFF777267),
      divider = Color(0xFFE9E5D7),
      accent = Color(0xFFADA06D),
      decoration = NoteSkinDecoration.GINKGO_LEAVES,
      decorationColor = Color(0x14B19A61),
    ),
  )

  fun byId(id: String): NoteSkin {
    val canonicalId = canonicalNoteSkinId(id)
    return all.first { it.id == canonicalId }
  }
}
