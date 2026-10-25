package com.anan.anannotes.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class AnAnShapesTest {
  @Test
  fun standardInputFieldUsesTwelveDpCorners() {
    assertEquals(RoundedCornerShape(12.dp), AnAnInputFieldShape)
  }
}
