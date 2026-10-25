package com.anan.anannotes.feature.export

import android.content.res.Resources
import androidx.test.platform.app.InstrumentationRegistry

fun exportTestResources(): Resources =
  InstrumentationRegistry.getInstrumentation().targetContext.resources
