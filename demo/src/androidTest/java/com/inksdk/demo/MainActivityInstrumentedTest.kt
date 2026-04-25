package com.inksdk.demo

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.inksdk.ink.PerfCounters
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test for the demo: launch the activity, inject a synthetic stroke,
 * verify the perf-counter rig is wired (counters object is reachable). The
 * actual perf values populate only on a Bigme device's daemon callbacks —
 * we don't gate on those here.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityInstrumentedTest {

    @Test
    fun launchAndInjectStroke() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val view = activity.findViewById<InkSurfaceView>(R.id.inkSurface)
                assertNotNull(view)
                val pts = listOf(
                    Triple(50f, 50f, 1L),
                    Triple(60f, 60f, 16L),
                    Triple(70f, 70f, 32L),
                )
                view.injectStrokeForTest(pts)
            }
        }
        // Counter snapshot is just smoke — no assertion on values, since the
        // synthetic injection path doesn't go through the daemon's binder.
        assertNotNull(PerfCounters.snapshot())
    }
}
