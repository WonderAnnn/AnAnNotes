package com.anan.anannotes.feature.export

import android.content.ClipDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClipboardTextWriterTest {
  @Test
  fun createsPlainTextClipWithAnAppLabelRatherThanLeakingTheNoteTitle() {
    val clip = ClipboardTextWriter.createClip("敏感标题\n正文", sensitive = false)

    assertEquals("安安简记", clip.description.label)
    assertEquals("敏感标题\n正文", clip.getItemAt(0).text.toString())
    assertEquals(ClipDescription.MIMETYPE_TEXT_PLAIN, clip.description.getMimeType(0))
    assertFalse(clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true)
  }

  @Test
  @SdkSuppress(minSdkVersion = 33)
  fun marksPrivateClipSensitiveBeforePassingItToSystemClipboard() {
    val clip = ClipboardTextWriter.createClip("私密标题\n私密正文", sensitive = true)

    assertEquals("安安简记", clip.description.label)
    assertEquals("私密标题\n私密正文", clip.getItemAt(0).text.toString())
    assertTrue(
      "应用交给系统的私密 ClipData 必须携带 EXTRA_IS_SENSITIVE",
      clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true,
    )
  }
}
