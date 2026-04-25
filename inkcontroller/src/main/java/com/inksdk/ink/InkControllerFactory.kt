package com.inksdk.ink

import android.os.Build

/**
 * Picks the best [InkController] for this device.
 *
 * Selection at construction time:
 *  1. Bigme — if `Build.MANUFACTURER == "Bigme"`, use the xrz daemon path.
 *  2. Onyx — for everything else; fails cleanly on non-Onyx devices.
 *
 * The decision is finalised inside [InkController.attach]: each candidate's
 * first attach call either succeeds (returns true) or logs the failure and
 * returns false. The host is expected to fall back to its MotionEvent +
 * Canvas path when [InkController.isActive] is false post-attach.
 *
 * [NoopInkController] is reachable via [createNoop] for test rigs that want
 * to short-circuit the daemon path without the SDK side effects.
 */
object InkControllerFactory {
    fun create(): InkController =
        if (BigmeInkController.isBigmeDevice()) BigmeInkController()
        else OnyxInkController()

    fun createNoop(): InkController = NoopInkController

    /** True iff the current device is identified as a Bigme device. */
    fun isBigmeDevice(): Boolean =
        Build.MANUFACTURER.equals("Bigme", ignoreCase = true) ||
            Build.BRAND.equals("Bigme", ignoreCase = true)
}
