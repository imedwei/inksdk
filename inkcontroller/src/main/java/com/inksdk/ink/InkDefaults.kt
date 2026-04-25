package com.inksdk.ink

import android.graphics.Color

/** Defaults used by ink controllers when the host has not called
 *  [InkController.setStrokeStyle]. Sized for ~2.6dp at density 2 (≈5px) to
 *  match the Mokke production stroke width. Hosts that care should call
 *  setStrokeStyle with a density-scaled value. */
object InkDefaults {
    const val DEFAULT_STROKE_WIDTH_PX: Float = 5f
    const val DEFAULT_STROKE_COLOR: Int = Color.BLACK
}
