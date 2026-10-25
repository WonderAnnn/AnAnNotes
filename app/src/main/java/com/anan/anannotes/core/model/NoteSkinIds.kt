package com.anan.anannotes.core.model

const val BAMBOO_SHADOW_SKIN_ID = "bamboo-shadow"
const val MISTY_TIDE_SKIN_ID = "misty-tide"
const val OSMANTHUS_NOTE_SKIN_ID = "osmanthus-note"
const val MOONLIT_SKY_SKIN_ID = "moonlit-sky"
const val WISTERIA_CLOUD_SKIN_ID = "wisteria-cloud"
const val CAMELLIA_LETTER_SKIN_ID = "camellia-letter"
const val SOUTHERN_GINKGO_SKIN_ID = "southern-ginkgo"

/** IDs selectable in the current skin picker and accepted for new notes/settings. */
val ACTIVE_NOTE_SKIN_IDS = setOf(
  DEFAULT_NOTE_SKIN_ID,
  "warm-paper",
  "mint-green",
  SAKURA_PETAL_SKIN_ID,
  BAMBOO_SHADOW_SKIN_ID,
  MISTY_TIDE_SKIN_ID,
  OSMANTHUS_NOTE_SKIN_ID,
  MOONLIT_SKY_SKIN_ID,
  WISTERIA_CLOUD_SKIN_ID,
  CAMELLIA_LETTER_SKIN_ID,
  SOUTHERN_GINKGO_SKIN_ID,
)

/** Preserve notes created with retired skins by resolving their appearance to the closest successor. */
val RETIRED_NOTE_SKIN_REPLACEMENTS = mapOf(
  "deep-sea" to MISTY_TIDE_SKIN_ID,
  "night-ink" to MOONLIT_SKY_SKIN_ID,
)

fun canonicalNoteSkinId(id: String): String =
  RETIRED_NOTE_SKIN_REPLACEMENTS[id] ?: id.takeIf { it in ACTIVE_NOTE_SKIN_IDS } ?: DEFAULT_NOTE_SKIN_ID
