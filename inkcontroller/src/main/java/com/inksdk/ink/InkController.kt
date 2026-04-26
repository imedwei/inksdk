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

    /** True iff the overlay owns the host SurfaceView's surface while
     *  active — i.e. the host MUST NOT call `holder.lockCanvas()` /
     *  `unlockCanvasAndPost()` while writing.
     *
     *  Onyx's `TouchHelper` runs raw drawing directly on the SurfaceView,
     *  so any host-side `lockCanvas` blocks forever waiting for a lock
     *  TouchHelper never releases — wedging not just the host's draw thread
     *  but also SurfaceFlinger and the main thread (timers stop ticking,
     *  the activity becomes unresponsive).
     *
     *  Bigme's daemon paints into a separate ION buffer that floats above
     *  SurfaceFlinger — the host's surface and the daemon's overlay are
     *  independent compositors, so host commits coexist fine. Default false. */
    val ownsSurface: Boolean get() = false

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

    /** Copy the controller's current overlay contents into [target] so the
     *  host can retain ink across surface refreshes (e.g. system-driven UI
     *  composes that re-blit a stale SurfaceView buffer over the EPD region).
     *
     *  Pixel-perfect: there is no coordinate translation, so this is immune
     *  to view-local vs window-local frame-of-reference mismatches.
     *
     *  Returns true if pixels were copied. Default is false — implementations
     *  that don't expose their overlay buffer (Noop, Onyx, and Bigme on
     *  firmware that returns null from `getContent()` and a hardware-backed
     *  Canvas with no readable bitmap) leave this as a no-op and the host
     *  should fall back to its own bitmap-mirroring path. */
    fun mirrorOverlay(target: android.graphics.Bitmap): Boolean = false

    /** Wipe the controller's overlay buffer over [region], **without**
     *  triggering an EPD refresh.
     *
     *  Use case: the host has already painted the canonical post-stroke ink
     *  into its own SurfaceView bitmap, so it doesn't need the controller's
     *  transient overlay to keep showing the same stroke. Clearing the
     *  controller buffer prevents two issues:
     *
     *  1. **Position shift between host and controller-rendered ink.** The
     *     controller's overlay and the SurfaceView are composed through
     *     different pipelines on Bigme (direct EPD vs SurfaceFlinger). With
     *     both showing the same stroke they can land on slightly different
     *     panel pixels — the host's "after-refresh shift" symptom. Wiping
     *     the controller buffer leaves the SurfaceView as the sole source.
     *
     *  2. **Ghost accumulation.** The Bigme daemon's ION buffer never auto-
     *     clears; old strokes pile up forever, and post-erase reuses of the
     *     region show ghost trails of removed ink under fresh strokes.
     *
     *  This call must NOT call `inValidate` — the EPD's currently-displayed
     *  pixels (drawn from this same buffer earlier) remain on screen via
     *  the controller's prior commit. Default: no-op. */
    fun clearRegion(region: android.graphics.Rect) = Unit

    /** Reset any per-session diagnostic counters (stroke index, etc.) so
     *  first-N-of-session logging fires again without needing to detach
     *  and re-attach the controller. Default: no-op. */
    fun resetDiagnostics() = Unit

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
