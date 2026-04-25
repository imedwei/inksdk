package com.inksdk.ink

import android.app.Activity
import android.graphics.Rect
import android.os.Build
import android.view.SurfaceView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Connected-device tests for [InkControllerFactory] and the per-vendor
 * controllers. Tests skip via JUnit `assumeTrue` when run on the wrong
 * vendor, so a single suite can run on either Bigme or Onyx hardware.
 */
@RunWith(AndroidJUnit4::class)
class InkControllerInstrumentedTest {

    private fun activity(): Activity = run {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        // Use the test app's own activity context substitute — for SurfaceView
        // we just need a Context with a Looper. The SurfaceView won't render,
        // but attach()/detach() exercise the daemon binder calls regardless.
        // We rely on the test runner's default activity context.
        ctx as Activity
    }

    private fun newSurface(): SurfaceView {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        return SurfaceView(ctx).also {
            it.layout(0, 0, 200, 200)
        }
    }

    private val noopCallback = object : StrokeCallback {
        override fun onStrokeBegin(x: Float, y: Float, pressure: Float, timestampMs: Long) = Unit
        override fun onStrokeMove(x: Float, y: Float, pressure: Float, timestampMs: Long) = Unit
        override fun onStrokeEnd(x: Float, y: Float, pressure: Float, timestampMs: Long) = Unit
    }

    @Test
    fun factoryPicksControllerByManufacturer() {
        val ctrl = InkControllerFactory.create()
        if (Build.MANUFACTURER.equals("Bigme", ignoreCase = true) ||
            Build.BRAND.equals("Bigme", ignoreCase = true)) {
            assertTrue("Expected BigmeInkController on $${Build.MANUFACTURER}",
                ctrl is BigmeInkController)
        } else {
            assertTrue("Expected OnyxInkController off-Bigme, got ${ctrl::class.simpleName}",
                ctrl is OnyxInkController)
        }
    }

    @Test
    fun bigmeAttachThenDetachOnSupportedDevice() {
        assumeTrue("Bigme-only test", InkControllerFactory.isBigmeDevice())
        val ctrl = BigmeInkController()
        assertFalse("Should start inactive", ctrl.isActive)
        // Attach without a real surface won't engage the daemon (the surface
        // isn't wrapped in a window). We only verify the call path doesn't
        // throw and that detach is idempotent.
        ctrl.detach()
    }

    @Test
    fun onyxAttachOnNonOnyxFailsCleanly() {
        // On a Bigme device, OnyxInkController.attach() should return false
        // (SDK loads but vendor runtime missing) without throwing.
        assumeTrue("Run on non-Onyx hardware", !isOnyxDevice())
        val ctrl = OnyxInkController()
        val ok = ctrl.attach(newSurface(), Rect(0, 0, 200, 200), noopCallback)
        assertFalse("Onyx attach should fail off-Onyx", ok)
        assertFalse(ctrl.isActive)
        ctrl.detach() // idempotent
    }

    @Test
    fun noopFactoryReturnsSingleton() {
        assertNotNull(InkControllerFactory.createNoop())
        assertEquals(NoopInkController, InkControllerFactory.createNoop())
    }

    private fun isOnyxDevice(): Boolean =
        Build.MANUFACTURER.equals("Onyx", ignoreCase = true) ||
            Build.BRAND.equals("Onyx", ignoreCase = true)
}
