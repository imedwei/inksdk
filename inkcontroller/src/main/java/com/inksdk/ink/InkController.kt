package com.inksdk.ink

import android.graphics.Bitmap
import android.graphics.Rect
import android.view.SurfaceView

/**
 * Abstracts a low-latency ink overlay — a hardware pen pipeline that paints
 * strokes directly at the e-ink controller's native refresh rate, bypassing
 * the Android view system.
 *
 * Implementations:
 *  - [BigmeInkController]: Bigme `com.xrz.HandwrittenClient` daemon.
 *  - [OnyxInkController]: Onyx Boox `TouchHelper` SDK.
 *  - [NoopInkController]: fallback when no hardware ink pipeline is available
 *    (emulator, generic Android). The host View should fall back to
 *    `MotionEvent` + `Canvas` rendering.
 */
interface InkController {

    /** True iff the overlay is attached and currently painting pen strokes.
     *  When true, the host View must NOT draw the in-progress stroke itself —
     *  the overlay owns that pixel budget. */
    val isActive: Boolean

    /** True iff the overlay swallows MotionEvents and delivers strokes via
     *  [StrokeCallback] instead. Onyx's TouchHelper does this; Bigme's xrz
     *  pipeline does not — on Bigme the daemon rasterises at the framebuffer
     *  while MotionEvents still flow through `View.onTouchEvent`. */
    val consumesMotionEvents: Boolean

    /** Attach the overlay to [view] with [limit] as the visible/allowed
     *  drawing rect (in view-local pixels). Returns true iff the overlay is
     *  now active. On false, the caller should use its MotionEvent fallback. */
    fun attach(view: SurfaceView, limit: Rect, callback: StrokeCallback): Boolean

    /** Pen stroke style — safe to call before or after [attach]. */
    fun setStrokeStyle(widthPx: Float, color: Int)

    /** Pause/resume delivery without detaching. Use before surface-repaint
     *  operations (finger scroll, full redraws, screen-mode refreshes). */
    fun setEnabled(enabled: Boolean)

    /**
     * Catch the EPD up to the host's [bitmap] over [region] (or the whole
     * view if region is null). Host calls this at mutation sites — scroll,
     * scratch-out, snap replacement, document load.
     *
     * [force] distinguishes two refresh cadences:
     *  - `false` (default) — "keep the overlay's shadow buffer in sync; the
     *    host's own SurfaceView commit will drive the EPD refresh."
     *    Appropriate for scroll: the SurfaceFlinger compose recomposites all
     *    layers, naturally reflecting the new bitmap.
     *  - `true` — "the host's SurfaceView commit alone won't make this
     *    visible; force a refresh." Appropriate for delete/snap where the
     *    overlay still shows pre-mutation ink on top of the freshly-composed
     *    SurfaceView and the host-side compose doesn't cycle the EPD.
     *
     * IMPORTANT: [bitmap] must already reflect the post-mutation state.
     * Force a synchronous rebuild before calling — syncing from an
     * un-rebuilt bitmap produces stale EPD content.
     */
    fun syncOverlay(bitmap: Bitmap, region: Rect? = null, force: Boolean = false) = Unit

    /** Detach — release the raw-drawing session. After this, [isActive] is
     *  false. [attach] must be called again to resume low-latency ink. */
    fun detach()
}

/** Pen-event sink, delivered in view-local coordinates. The host View owns
 *  scroll-offset translation to document space. */
interface StrokeCallback {
    fun onStrokeBegin(x: Float, y: Float, pressure: Float, timestampMs: Long)
    fun onStrokeMove(x: Float, y: Float, pressure: Float, timestampMs: Long)
    fun onStrokeEnd(x: Float, y: Float, pressure: Float, timestampMs: Long)
}
