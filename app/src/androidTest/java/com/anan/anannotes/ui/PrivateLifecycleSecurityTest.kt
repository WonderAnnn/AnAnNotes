package com.anan.anannotes.ui

import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anan.anannotes.MainActivity
import com.anan.anannotes.feature.privatemodule.restoreWindowFlag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivateLifecycleSecurityTest {
  @get:Rule
  val composeRule = createAndroidComposeRule<MainActivity>()

  @Test
  fun backgroundReenablesScreenshotProtectionAndLeavingPrivateModuleRestoresWindowFlags() {
    val scenario = composeRule.activityRule.scenario
    val window = composeRule.activity.window
    val secureFlag = WindowManager.LayoutParams.FLAG_SECURE
    val keepScreenOnFlag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
    val originalFlags = window.attributes.flags

    try {
      composeRule.onNodeWithTag("private-entry-button").performClick()
      composeRule.onNodeWithTag("private-access-form").assertIsDisplayed()
      composeRule.waitForIdle()

      composeRule.runOnUiThread {
        window.clearFlags(secureFlag)
        window.addFlags(keepScreenOnFlag)
      }

      scenario.moveToState(Lifecycle.State.CREATED)
      scenario.onActivity { activity ->
        assertTrue(
          "私密页进入后台时必须重新启用 FLAG_SECURE",
          activity.window.attributes.flags and secureFlag != 0,
        )
      }

      scenario.moveToState(Lifecycle.State.RESUMED)
      composeRule.waitForIdle()
      composeRule.onNodeWithTag("private-access-form").assertIsDisplayed()
      composeRule.onNodeWithTag("private-back-button").performClick()
      composeRule.onNodeWithTag("home-title-header").assertIsDisplayed()

      composeRule.runOnIdle {
        val flags = window.attributes.flags
        assertEquals(
          "离开私密模块后应恢复进入前的 FLAG_SECURE 状态",
          originalFlags and secureFlag,
          flags and secureFlag,
        )
        assertTrue(
          "恢复 FLAG_SECURE 时不能覆盖其他窗口标志",
          flags and keepScreenOnFlag != 0,
        )
      }
    } finally {
      scenario.onActivity { activity ->
        var flags = activity.window.attributes.flags
        flags = restoreWindowFlag(flags, originalFlags, secureFlag)
        flags = restoreWindowFlag(flags, originalFlags, keepScreenOnFlag)
        activity.window.setFlags(flags, secureFlag or keepScreenOnFlag)
      }
    }
  }
}
