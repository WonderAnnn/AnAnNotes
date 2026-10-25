package com.anan.anannotes.feature.privatemodule

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivateWindowFlagPolicyTest {
  @Test
  fun restoringSecureFlagPreservesItsInitialStateAndOtherWindowFlags() {
    val secureFlag = 0x00002000
    val keepScreenOnFlag = 0x00000080

    assertEquals(
      secureFlag or keepScreenOnFlag,
      restoreWindowFlag(keepScreenOnFlag, secureFlag or keepScreenOnFlag, secureFlag),
    )
    assertEquals(
      keepScreenOnFlag,
      restoreWindowFlag(secureFlag or keepScreenOnFlag, keepScreenOnFlag, secureFlag),
    )
  }
}
