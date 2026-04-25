package com.inksdk.ink

import android.app.Application
import android.graphics.Rect
import android.view.SurfaceView
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NoopInkControllerTest {

    @Test
    fun isInactiveAndNonConsuming() {
        assertFalse(NoopInkController.isActive)
        assertFalse(NoopInkController.consumesMotionEvents)
    }

    @Test
    fun attachReturnsFalse() {
        val view = SurfaceView(RuntimeEnvironment.getApplication())
        val callback = object : StrokeCallback {
            override fun onStrokeBegin(x: Float, y: Float, pressure: Float, timestampMs: Long) = Unit
            override fun onStrokeMove(x: Float, y: Float, pressure: Float, timestampMs: Long) = Unit
            override fun onStrokeEnd(x: Float, y: Float, pressure: Float, timestampMs: Long) = Unit
        }
        assertFalse(NoopInkController.attach(view, Rect(0, 0, 100, 100), callback))
    }

    @Test
    fun setStrokeStyleSetEnabledDetachAreNoOps() {
        // Just verify they don't throw.
        NoopInkController.setStrokeStyle(2f, 0)
        NoopInkController.setEnabled(true)
        NoopInkController.detach()
    }
}
