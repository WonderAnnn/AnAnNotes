package com.anan.anannotes.feature.privatemodule

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateScreenshotPolicyTest {
  @Test
  fun debugBuildAllowsCaptureThroughoutPrivateModuleWhileItIsForegrounded() {
    assertFalse(shouldKeepPrivateWindowSecure(isDebuggable = true))
  }

  @Test
  fun releaseBuildAlwaysKeepsPrivateContentProtectedOnEveryPrivatePage() {
    assertTrue(shouldKeepPrivateWindowSecure(isDebuggable = false))
  }
}
