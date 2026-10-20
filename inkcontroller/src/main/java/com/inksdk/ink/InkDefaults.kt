package com.inksdk.ink

import android.graphics.Color

/** Defaults used by ink controllers when the host has not called
 *  [InkController.setStrokeStyle]. Sized for ~2.6dp at density 2 (≈5px) to
 *  match the Mokke production stroke width. Hosts that care should call
 *  setStrokeStyle with a density-scaled value. */
object InkDefaults {
    const val DEFAULT_STROKE_WIDTH_PX: Float = 5f
    const val DEFAULT_STROKE_COLOR: Int = Color.BLACK

    /** Shared live/replay pressure curve: 0.3x to 2x of the base width. */
    fun pressureToWidth(baseWidthPx: Float, pressure: Float): Float =
        baseWidthPx * (0.3f + 1.7f * pressure.coerceIn(0f, 1f))

    /** Surface-pixel dash pattern for selection trails. */
    fun dashIntervals(): FloatArray = floatArrayOf(16f, 24f)
}
