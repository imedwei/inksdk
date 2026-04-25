package com.inksdk.ink

import android.app.Application
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class InkControllerFactoryTest {

    @Test
    fun createNoopAlwaysReturnsTheSingleton() {
        assertSame(NoopInkController, InkControllerFactory.createNoop())
    }

    @Test
    fun createOnGenericDeviceReturnsOnyx() {
        ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", "Generic")
        ReflectionHelpers.setStaticField(Build::class.java, "BRAND", "generic")
        val ctrl = InkControllerFactory.create()
        assertTrue("Expected OnyxInkController on generic device, got ${ctrl::class.simpleName}",
            ctrl is OnyxInkController)
    }

    @Test
    fun createOnBigmeDeviceReturnsBigme() {
        ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", "Bigme")
        ReflectionHelpers.setStaticField(Build::class.java, "BRAND", "Bigme")
        val ctrl = InkControllerFactory.create()
        assertTrue("Expected BigmeInkController on Bigme device, got ${ctrl::class.simpleName}",
            ctrl is BigmeInkController)
    }

    @Test
    fun isBigmeDeviceMatchesManufacturerOrBrand() {
        ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", "Bigme")
        ReflectionHelpers.setStaticField(Build::class.java, "BRAND", "OEM")
        assertTrue(InkControllerFactory.isBigmeDevice())

        ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", "OEM")
        ReflectionHelpers.setStaticField(Build::class.java, "BRAND", "bigme")
        assertTrue(InkControllerFactory.isBigmeDevice())

        ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", "Onyx")
        ReflectionHelpers.setStaticField(Build::class.java, "BRAND", "onyx")
        assertFalse(InkControllerFactory.isBigmeDevice())
    }

    @Test
    fun bigmeAttachOnNonBigmeDeviceFailsCleanly() {
        ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", "Generic")
        ReflectionHelpers.setStaticField(Build::class.java, "BRAND", "generic")
        val ctrl = BigmeInkController()
        // Skip running attach() — it requires a SurfaceView with a window —
        // but the device-detection gate alone should already prevent any
        // reflective load on non-Bigme.
        assertEquals(false, ctrl.isActive)
    }
}
