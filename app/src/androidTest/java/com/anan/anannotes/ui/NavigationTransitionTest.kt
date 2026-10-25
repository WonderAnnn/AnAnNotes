package com.anan.anannotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.captureToImage
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anan.anannotes.ui.navigation.HomeRoute
import com.anan.anannotes.ui.navigation.TodoRoute
import com.anan.anannotes.ui.navigation.pageForwardTransition
import com.anan.anannotes.ui.navigation.pagePopTransition
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationTransitionTest {
  @get:Rule
  val composeRule = createComposeRule()

  @Test
  fun switchingPagesDoesNotBlendThePreviousPageOverTheDestination() {
    lateinit var backStack: MutableList<NavKey>

    composeRule.setContent {
      backStack = rememberNavBackStack(HomeRoute)
      NavDisplay(
        backStack = backStack,
        modifier = Modifier.fillMaxSize().testTag("navigation-transition-root"),
        onBack = { backStack.removeLastOrNull() },
        transitionSpec = pageForwardTransition(),
        popTransitionSpec = pagePopTransition(),
        entryProvider = entryProvider {
          entry<HomeRoute> {
            Box(Modifier.fillMaxSize().background(Color.Red))
          }
          entry<TodoRoute> {
            Box(Modifier.fillMaxSize().background(Color.Blue))
          }
        },
      )
    }
    composeRule.waitForIdle()
    composeRule.mainClock.autoAdvance = false

    composeRule.runOnIdle {
      backStack.clear()
      backStack.add(TodoRoute)
    }
    composeRule.mainClock.advanceTimeBy(110L)
    composeRule.waitForIdle()

    val center = composeRule.onNodeWithTag("navigation-transition-root")
      .captureToImage()
      .toPixelMap()
      .let { pixels -> pixels[pixels.width / 2, pixels.height / 2] }

    assertEquals("destination should be opaque instead of crossfading with the old page", Color.Blue, center)
  }

  @Test
  fun returningToPreviousPageDoesNotBlendThePageBeingRemoved() {
    lateinit var backStack: MutableList<NavKey>

    composeRule.setContent {
      backStack = rememberNavBackStack(HomeRoute, TodoRoute)
      NavDisplay(
        backStack = backStack,
        modifier = Modifier.fillMaxSize().testTag("navigation-transition-root"),
        onBack = { backStack.removeLastOrNull() },
        transitionSpec = pageForwardTransition(),
        popTransitionSpec = pagePopTransition(),
        entryProvider = entryProvider {
          entry<HomeRoute> {
            Box(Modifier.fillMaxSize().background(Color.Red))
          }
          entry<TodoRoute> {
            Box(Modifier.fillMaxSize().background(Color.Blue))
          }
        },
      )
    }
    composeRule.waitForIdle()
    composeRule.mainClock.autoAdvance = false

    composeRule.runOnIdle { backStack.removeLastOrNull() }
    composeRule.mainClock.advanceTimeBy(110L)
    composeRule.waitForIdle()

    val center = composeRule.onNodeWithTag("navigation-transition-root")
      .captureToImage()
      .toPixelMap()
      .let { pixels -> pixels[pixels.width / 2, pixels.height / 2] }

    assertEquals("previous destination should be opaque instead of crossfading", Color.Red, center)
  }
}
