package com.anan.anannotes.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.Scene
import androidx.navigationevent.NavigationEvent

private const val PAGE_TRANSITION_DURATION_MILLIS = 220

internal fun pageForwardTransition(): AnimatedContentTransitionScope<Scene<NavKey>>.() -> ContentTransform = {
  slideInHorizontally(
    initialOffsetX = { fullWidth -> fullWidth },
    animationSpec = tween(PAGE_TRANSITION_DURATION_MILLIS, easing = FastOutSlowInEasing),
  ) togetherWith slideOutHorizontally(
    targetOffsetX = { fullWidth -> -fullWidth },
    animationSpec = tween(PAGE_TRANSITION_DURATION_MILLIS, easing = FastOutSlowInEasing),
  )
}

internal fun pagePopTransition(): AnimatedContentTransitionScope<Scene<NavKey>>.() -> ContentTransform = {
  slideInHorizontally(
    initialOffsetX = { fullWidth -> -fullWidth },
    animationSpec = tween(PAGE_TRANSITION_DURATION_MILLIS, easing = FastOutSlowInEasing),
  ) togetherWith slideOutHorizontally(
    targetOffsetX = { fullWidth -> fullWidth },
    animationSpec = tween(PAGE_TRANSITION_DURATION_MILLIS, easing = FastOutSlowInEasing),
  )
}

internal fun pagePredictivePopTransition():
  AnimatedContentTransitionScope<Scene<NavKey>>.(Int) -> ContentTransform = { edge ->
  val startsFromRight = edge == NavigationEvent.EDGE_RIGHT
  slideInHorizontally(
    initialOffsetX = { fullWidth -> if (startsFromRight) fullWidth else -fullWidth },
    animationSpec = tween(PAGE_TRANSITION_DURATION_MILLIS, easing = FastOutSlowInEasing),
  ) togetherWith slideOutHorizontally(
    targetOffsetX = { fullWidth -> if (startsFromRight) -fullWidth else fullWidth },
    animationSpec = tween(PAGE_TRANSITION_DURATION_MILLIS, easing = FastOutSlowInEasing),
  )
}
