package com.inksdk.ink

import android.graphics.Rect
import android.view.SurfaceView

/** Placeholder controller for environments without a hardware ink overlay
 *  (emulator, generic Android, Bigme without a vendor SDK). The host View
 *  should fall back to MotionEvent + Canvas rendering when this is the
 *  selected controller. */
object NoopInkController : InkController {
    override val isActive: Boolean = false
    override val consumesMotionEvents: Boolean = false
    override fun attach(view: SurfaceView, limit: Rect, callback: StrokeCallback): Boolean = false
    override fun setStrokeStyle(widthPx: Float, color: Int) = Unit
    override fun setEnabled(enabled: Boolean) = Unit
    override fun detach() = Unit
}
