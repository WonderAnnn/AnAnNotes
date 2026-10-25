package com.anan.anannotes.feature.privatemodule

import android.view.Window
import android.view.WindowManager

internal fun restoreWindowFlag(currentFlags: Int, originalFlags: Int, flag: Int): Int =
  (currentFlags and flag.inv()) or (originalFlags and flag)

internal fun shouldKeepPrivateWindowSecure(
  isDebuggable: Boolean,
): Boolean = !isDebuggable

internal fun applyPrivateScreenshotPolicy(
  window: Window?,
  isDebuggable: Boolean,
) {
  if (window == null) return
  val secureFlag = WindowManager.LayoutParams.FLAG_SECURE
  if (shouldKeepPrivateWindowSecure(isDebuggable)) {
    window.addFlags(secureFlag)
  } else {
    window.clearFlags(secureFlag)
  }
}
