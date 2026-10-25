package com.anan.anannotes.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anan.anannotes.feature.privatemodule.PrivateHomeScreen
import com.anan.anannotes.feature.privatemodule.PrivateNoteId
import com.anan.anannotes.feature.privatemodule.PrivateNotePreview
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivateHomeScreenTest {
  @get:Rule
  val composeRule = createComposeRule()

  @Test
  fun privateNoteCardHasQuietOutlineAgainstItsDarkSurface() {
    composeRule.setContent {
      MaterialTheme(
        colorScheme = darkColorScheme(
          primary = Color(0xFFA6B1BB),
          surface = Color(0xFF22272C),
          onSurface = Color(0xFFE8EAEC),
          onSurfaceVariant = Color(0xFFA8B0B7),
          outline = Color(0xFF69747E),
        ),
      ) {
        PrivateHomeScreen(
          notes = listOf(
            PrivateNotePreview(
              id = PrivateNoteId("outline-test"),
              title = "夜间随记",
              updatedAtMillis = 1_790_700_000_000L,
              folderId = "private-uncategorized",
            ),
          ),
          onCreateNote = {},
          onOpenNote = {},
        )
      }
    }

    val pixels = composeRule.onNodeWithTag("private-note-outline-test").captureToImage().toPixelMap()
    val edge = pixels[pixels.width / 2, 0]
    val fill = pixels[pixels.width / 2, 4]
    val contrast = maxOf(
      kotlin.math.abs(edge.red - fill.red),
      kotlin.math.abs(edge.green - fill.green),
      kotlin.math.abs(edge.blue - fill.blue),
    )

    assertTrue("私密笔记卡片应以克制的细描边与深色背景区分", contrast >= 0.08f)
  }
}
