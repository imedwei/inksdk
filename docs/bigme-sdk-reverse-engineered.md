# Reverse-engineering Bigme's undocumented ink SDK

*How an afternoon with `dexdump`, `app_process`, and a lot of stubborn curiosity uncovered the xrz framework that powers the Bigme HiBreak Plus — and how this library makes it accessible to any Android app.*

---

This document is the long-form story behind `inksdk`. The library you find in this repo is the cleanly-extracted output of an investigation into how Bigme's e-ink stylus pipeline actually works. Bigme publishes no developer docs for their xrz framework. As of April 2026 there is, as far as I can tell, zero public information about the API anywhere on the internet.

The post that follows is the missing manual, plus a tutorial in how to extract a vendor SDK from an Android device when the vendor has given you nothing to go on. The end product — the Kotlin module under `inkcontroller/` in this repo — is what you'd write after spending a week working through the phases below.

## The problem

Onyx has a documented Pen SDK (`com.onyx.android.sdk.pen.*`). Every serious third-party e-ink notes app on Android uses it. Its centerpiece is `TouchHelper`, which binds to a system-side "raw drawing" overlay that paints visible ink directly to the EPD framebuffer while `MotionEvent`s route to your app on the normal pipeline. The visible ink and the logical ink race; the EPD composites. You get sub-20 ms perceived latency.

On Bigme devices, `TouchHelper.create()` throws internally. A handwriting app catches it, logs a warning, and falls back to a generic `View` + `Canvas` draw loop. That loop goes through `SurfaceFlinger` and whatever default refresh mode the panel is in. On e-ink, that's slow:

```
I TouchHelper      : create(): vendor service unavailable
W InkController    : falling back to Canvas draw path
```

So: on Bigme, there must be a low-latency path — the stock notes app clearly uses one, because it doesn't lag — but it's not Onyx's. It's something else, and we have to find it.

## Step 1: what's in the box

First, find out what vendor code is on the device:

```bash
$ adb shell getprop | grep -iE 'bigme|xrz|hct'
[persist.sys.hctmodel]: [Bigme]
[ro.product.manufacturer]: [Bigme]
[ro.vendor.xrz.default_refresh_mode]: [178]
[sys.comic_refresh_mode]: [180]
```

Two things jump out. The manufacturer is "Bigme" but the internal namespace is "xrz" — some OEM/ODM relationship, presumably. And there's already a global default refresh mode of `178` in a system property, which means somewhere there's a table mapping integers to e-ink waveforms. Good.

```bash
$ adb shell pm list packages -f | grep -iE 'bigme|xrz' | wc -l
32

$ adb shell pm list packages -f | grep -iE 'xrz' | head
package:/system/app/xNote/xNote.apk=com.xrz.note
package:/system/app/xreaderV3/xreaderV3.apk=com.xrz.xreaderV3
package:/system/app/standby/standby.apk=com.xrz.standby
package:/system/app/bookself/bookself.apk=com.xrz.bookself
package:/system/app/recognize/recognize.apk=com.xrz.recognize
...
```

Thirty-plus system apps in the `com.xrz.*` namespace. The stock notes app, `com.xrz.note` ("xNote"), is the obvious study target. Whatever the low-latency ink API is, xNote uses it.

## Step 2: is the target app privileged?

Before spending any time disassembling xNote, find out whether it's getting access via some signature- or UID-gated privilege a third-party app could never replicate. If xNote is platform-signed, or runs as `system`, or holds a vendor-only signature permission, then reverse-engineering the API is a dead end for a third-party app.

```bash
$ adb shell dumpsys package com.xrz.note | grep -E 'userId|signatures|requestedPermission' | head -20
    userId=10078
    [27459ff9]   <- platform signature for comparison
    signatures=PackageSignatures{… [2f6f6cdb]}
    requestedPermissions:
      android.permission.INTERNET
      android.permission.ACCESS_NETWORK_STATE
      android.permission.WRITE_EXTERNAL_STORAGE
      com.xrz.note.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
```

Three things from that dump make the case optimistic:

1. **UID 10078 = `u0_a78`.** A regular user-app UID. Not `system` (1000), not any vendor UID. Same trust level a third-party app would run under.
2. **Signature hash `2f6f6cdb`.** Compare it to `dumpsys package android`, which shows the platform signature as `27459ff9`. Different. **xNote is not platform-signed.**
3. The only xrz-prefixed permission xNote declares is `com.xrz.note.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, which is the innocuous per-package stub Android 13+ synthesizes for dynamic broadcast receivers. It's not a privilege; it's a safety default.

A check of `/system/etc/permissions/` and `/vendor/etc/permissions/` for any signature-gated shared libraries under the xrz prefix returns empty. No `privapp-permissions-*.xml` entries either.

This is the single most important moment of the investigation. Before looking at a byte of bytecode there's now strong evidence that **whatever API xNote uses, an ordinary app can reach it too**. Proof by example.

## Step 3: what does xNote actually call?

Time to pull the APK and disassemble.

```bash
$ adb pull /system/app/xNote/xNote.apk
/system/app/xNote/xNote.apk: 1 file pulled. 12.4 MB
$ unzip -p xNote.apk classes.dex > xNote.classes.dex
$ unzip -p xNote.apk classes2.dex > xNote.classes2.dex
```

Use `dexdump` from the Android SDK build-tools. (If you haven't touched it before: `dexdump -d` gives a human-readable Smali-ish listing with per-instruction invoke sites.)

```bash
$ dexdump -d xNote.classes.dex > xNote.dex.txt
$ dexdump -d xNote.classes2.dex > xNote.dex2.txt
$ grep -E 'invoke.*xrz\.framework' xNote.dex.txt xNote.dex2.txt | head -30
```

The grep surfaces call sites like these:

```
invoke-virtual {v0, v1, v2}, Lxrz/framework/manager/XrzEinkManager;
    ->setRefreshModeForSurfaceView(Landroid/view/SurfaceView;I)V
invoke-static {v3}, Lxrz/framework/manager/XrzEinkManager;
    ->forceGlobalRefresh(I)V
invoke-virtual {v0, v2, v3}, Lxrz/framework/manager/XrzEinkManager;
    ->setRefreshModeByView(Landroid/view/View;I)V
invoke-virtual {v0}, Lxrz/framework/manager/XrzEinkManager;
    ->getDisplayPolicyManager()Lxrz/framework/manager/DisplayPolicyManager;
```

There's the API surface. `XrzEinkManager`, `DisplayPolicyManager`. A `setRefreshModeForSurfaceView(SurfaceView, int)` method that is almost certainly the handwriting hook — it takes a `SurfaceView`, which is what you'd use for low-latency drawing, and an integer refresh mode, matching the `ro.vendor.xrz.default_refresh_mode = 178` property from earlier.

The package namespace is `xrz.framework.manager.*`, not `com.xrz.*`. That tells us these classes live in a framework jar, not in an app.

## Step 4: find the framework jar

```bash
$ adb shell ls /system/framework/ | grep -i xrz
xrz.framework.server.jar
$ adb shell ls -l /system/framework/xrz.framework.server.jar
-rw-r--r-- 1 root root 124586 2024-03-11 19:02 /system/framework/xrz.framework.server.jar
$ adb pull /system/framework/xrz.framework.server.jar
```

World-readable, 124 KB. Unzip it and run `dexdump -l plain` to list classes:

```
xrz.framework.server.AppFreezeService
xrz.framework.server.DatabaseHelper
xrz.framework.server.DisplayPolicyController
xrz.framework.server.DisplayPolicyService
xrz.framework.server.SplitScreenService
xrz.framework.server.XrzSystemProxyService
xrz.framework.manager.EinkRefreshMode
xrz.framework.manager.XrzEinkManagerInternal
```

Now, an obvious question: is this jar on `BOOTCLASSPATH`?

```bash
$ adb shell echo $BOOTCLASSPATH | tr ':' '\n' | grep -i xrz
(nothing)
$ adb shell echo $SYSTEMSERVERCLASSPATH | tr ':' '\n' | grep -i xrz
(nothing)
```

Neither. A reasonable (but ultimately wrong) assumption at this point: you'd have to instantiate a `PathClassLoader` pointed at `/system/framework/xrz.framework.server.jar` to even load `XrzEinkManager`, because otherwise a plain `Class.forName("xrz.framework.manager.XrzEinkManager")` would fail with `ClassNotFoundException`.

That turns out not to be true. We'll get to why in a minute. The `PathClassLoader` fallback ends up in the shipping code anyway, as a defensive belt-and-suspenders measure, but it isn't the primary path.

## Step 5: what binder services does the vendor expose?

```bash
$ adb shell service list | grep -iE 'xrz|handwritten'
handwrittenservice: [com.xrz.IHandwrittenService]
xrz_display_policy_service: [xrz.framework.manager.IDisplayPolicyManager]
xrz_app_freeze_service: [xrz.framework.manager.IAppFreezeManager]
xrz_split_screen_service: [xrz.framework.manager.ISplitScreenManager]
xrz_system_proxy_service: [xrz.framework.manager.IXrzSystemProxy]
```

Five services. Four of them are obvious Java-side system services hosted somewhere in `system_server` or a sibling process. The fifth, `handwrittenservice`, is the one that should make you sit up.

```bash
$ adb shell ps -ef | grep handwritten
root           975  1 0 02:15:00 ?  00:00:12 /system/bin/handwrittenservice -p /dev/input/event0
```

A native daemon. Running as **root**. Launched with `-p /dev/input/event0`.

That's an input event node. This daemon is reading the raw Linux input device directly, before Android's `InputDispatcher` even sees it. That is exactly, architecturally, the same pattern Onyx uses for `TouchHelper`: a privileged side-channel that rasterizes stroke pixels to the framebuffer while the Android input pipeline carries on delivering the logical events to your app. The two drawings race, the EPD composites, and the user sees the ink land before the `onTouchEvent` callback chain finishes.

This is the moment to understand the architecture. It isn't that Bigme has no low-latency ink. It's that they deliver it via a root-daemon-plus-HWC-flag mechanism, and the only thing an app has to do to opt in is flip a refresh mode on the right `SurfaceView`. There is no Java SDK that swallows `MotionEvent`s the way Onyx's does — you keep getting them normally. You just additionally get fast visible ink.

## Step 6: the dumpsys freebie

A technique always worth trying early on vendor services that pays off immediately:

```bash
$ adb shell dumpsys xrz_display_policy_service
DisplayPolicy[com.android.chrome]: refreshMode=178 refreshFrequency=60 appDpi=420 ...
DisplayPolicy[com.google.android.youtube]: refreshMode=179 refreshFrequency=60 ...
DisplayPolicy[com.android.dialer]: refreshMode=179 ...
DisplayPolicy[com.android.camera2]: refreshMode=179 ...
DisplayPolicy[com.google.android.apps.maps]: refreshMode=178 ...
...
```

No auth, no permission, just works from a shell UID. Hundreds of apps' display policies dumped with their refresh modes. A pattern emerges at a glance:

- Default across the board is `refreshMode=178`.
- Video/camera/phone-dialer apps are `refreshMode=179`.
- Neither xNote (`com.xrz.note`) nor any third-party ink app is in the list at all.

That last point is telling. The apps that *do* draw ink don't persist a policy in this service. They set their refresh mode dynamically, per-view or per-window, at runtime. That's consistent with what we saw in xNote's dex: `setRefreshModeForSurfaceView` is called every time a canvas becomes visible.

## Step 7: the reflection probe

Static disassembly has given us call sites. Now we want the *complete* API surface of `XrzEinkManager`, `DisplayPolicyManager`, `DisplayPolicy`, and especially the constants class `EinkRefreshMode`, because the integer `178` doesn't mean anything without a name.

We also want to confirm we can actually call these APIs from an unprivileged process. That's where `app_process` comes in. It's Android's equivalent of the JVM launcher — it's how `system_server` and Zygote bootstrap, but you can also use it from a shell to run any Java class in the normal Android runtime, with a normal classloader, but at **shell UID** (2000). Shell UID is strictly *less* privileged than a normal app UID in most respects. If a reflection call works from `app_process`, it will work from your app.

The probe is about 40 lines of boring Java:

```java
public class Probe {
    public static void main(String[] args) throws Exception {
        Class<?> c = Class.forName("xrz.framework.manager.XrzEinkManager");
        System.out.println("Loaded: " + c.getName());
        System.out.println("ClassLoader: " + c.getClassLoader());
        for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
            System.out.println("  method: " + m);
        }
        for (java.lang.reflect.Field f : c.getDeclaredFields()) {
            System.out.println("  field: " + f);
        }
        Class<?> k = Class.forName("xrz.framework.manager.EinkRefreshMode");
        for (java.lang.reflect.Field f : k.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                f.setAccessible(true);
                System.out.println("  const: " + f.getName() + " = " + f.get(null));
            }
        }
        // Try a benign read call.
        System.out.println("isStylusDisabled=" +
            c.getMethod("isStylusDisabled").invoke(null));
    }
}
```

Compile, dex, jar, push:

```bash
$ javac -cp $ANDROID_HOME/platforms/android-34/android.jar \
    -source 11 -target 11 Probe.java
$ $ANDROID_HOME/build-tools/34.0.0/d8 Probe.class --output probe-dex/
$ (cd probe-dex && zip ../probe.jar classes.dex)
$ adb push probe.jar /data/local/tmp/
```

Then run it in the Android runtime, as shell UID:

```bash
$ adb shell "CLASSPATH=/data/local/tmp/probe.jar app_process /system/bin Probe"
Loaded: xrz.framework.manager.XrzEinkManager
ClassLoader: java.lang.BootClassLoader@...
  method: public static void xrz.framework.manager.XrzEinkManager.disableStylus(boolean)
  method: public static void xrz.framework.manager.XrzEinkManager.disableTouch(boolean)
  ...
  method: public void xrz.framework.manager.XrzEinkManager.setRefreshModeForSurfaceView(android.view.SurfaceView,int)
  ...
isStylusDisabled=false
```

Three things to note.

First, **the class loaded from `BootClassLoader` without a `PathClassLoader`**. That's surprising. Even though `xrz.framework.server.jar` is not on `BOOTCLASSPATH` or `SYSTEMSERVERCLASSPATH` as exposed to shell, *something* in the system integration has added these classes into the boot classloader at runtime. Probably a system property or a zygote preload list. Either way: plain `Class.forName` works from any process. The `PathClassLoader` fallback is unnecessary in the common case — but the shipping code keeps it because undocumented APIs and silent OTAs are a bad combination.

Second, `isStylusDisabled()` is a static method that goes out over Binder to `xrz_display_policy_service` and returns cleanly. No `SecurityException`. From UID 2000. Which strongly implies no privilege gate on any of these calls.

Third, the probe pulls out the full constant table from `EinkRefreshMode`. This is the thing you cannot get from `dexdump` easily — the constants are inlined at call sites as literal ints. You need reflection at runtime against the real class file to recover the names.

```
const: EINK_INIT_MODE = 1
const: EINK_DU_MODE = 2
const: EINK_GC16_MODE = 4
const: EINK_GC4_MODE = 8
const: EINK_A2_MODE = 16
const: EINK_GL16_MODE = 32
const: EINK_GLR16_MODE = 64
const: EINK_GLD16_MODE = 128
const: EINK_GU16_MODE = 132
const: EINK_GU4_MODE = 136
const: EINK_INPUT_MODE = 137
const: EINK_CLEAN_MODE = 176
const: EINK_HD_MODE = 177
const: EINK_DEFAULT_MODE = 178
const: EINK_NORMAL_MODE = 178
const: EINK_FAST_MODE = 179
const: EINK_REGAL_MODE = 180
const: EINK_HYPER_MODE = 183
const: EINK_RECT_MODE = 1024
const: EINK_HANDWRITE_MODE = 1029
const: EINK_RUBBER_MODE = 1030
const: EINK_AUTO_MODE = 32768
const: EINK_HD_256_MODE = -2147483471
```

There it is: `EINK_HANDWRITE_MODE = 1029`. That is the integer to pass to `setRefreshModeForSurfaceView` when the SurfaceView is for handwriting.

The probe also recovers the HWC layer-flag bits that get OR'd into the mode at the HAL level:

```
const: HWC_EINK_SURFACEVIEW_FLAG     = 0x00010000
const: HWC_EINK_FORCE_REFRESH_FLAG   = 0x00020000
const: HWC_EINK_HANDWRITTEN_FLAG     = 0x00040000
const: HWC_EINK_HOME_FLAG            = 0x00080000
const: HWC_EINK_HANDWRITTEN_BG_FLAG  = 0x00100000
const: HWC_EINK_MULTI_DAMAGE_FLAG    = 0x00200000
const: HWC_EINK_INPUT_METHOD_FLAG    = 0x00400000
const: HWC_EINK_AUTO_DITHER_FLAG     = 0x00800000
const: HWC_EINK_DISABLE_LAYER_FLAG   = 0x01000000
const: HWC_EINK_OVERLAY_FLAG         = 0x02000000
const: HWC_EINK_LOGO_FLAG            = 0x04000000
const: HWC_EINK_CURSOR_FLAG          = 0x08000000
const: HWC_EINK_DITHER_TEXT_EFFECT   = 0x20000000
const: HWC_EINK_DITHER_PICTURE1_EFFECT = 0x40000000
const: HWC_EINK_DITHER_PICTURE2_EFFECT = 0x60000000
const: HWC_EINK_DITHER_256_EFFECT    = 0x80000000
const: HWC_EINK_SKIP_FRAME_FLAG      = -1
```

Only one method call crashes, and it crashes in a way that is clearly not a permission problem:

```
nativeIsAntialiasSupport() -> JNI: std::out_of_range
```

That's a native-impl bug in this particular firmware build, not a security gate. Every other method tested responds.

## Step 8: live-behavior correlation

Now there's an API surface and a constant table. Time to prove the model is right. Launch xNote, start `logcat -v time` in a separate terminal, and use `adb shell input swipe` to simulate drawing a line.

```bash
$ adb shell logcat -v time | grep -iE 'hwchal|eink|setLayerRefreshMode'
03-11 14:22:03.118 HwcHal: setLayerRefreshMode mode=500b2
03-11 14:22:03.124 HwcHal: setLayerRefreshMode mode=500b2
03-11 14:22:03.131 HwcHal: setLayerRefreshMode mode=500b2
03-11 14:22:03.137 [eink_update] commit OK (7ms)
03-11 14:22:03.144 [eink_update] commit OK (6ms)
```

`mode=0x500B2`. Decode it by hand:

```
0x500B2
= 0x40000 | 0x10000 | 0xB2
= HWC_EINK_HANDWRITTEN_FLAG | HWC_EINK_SURFACEVIEW_FLAG | 178
= HWC_EINK_HANDWRITTEN_FLAG | HWC_EINK_SURFACEVIEW_FLAG | EINK_DEFAULT_MODE
```

Perfect match. When you call `setRefreshModeForSurfaceView(view, EINK_HANDWRITE_MODE=1029)` on a `SurfaceView`, the framework OR's in `HWC_EINK_HANDWRITTEN_FLAG` and `HWC_EINK_SURFACEVIEW_FLAG`, maps the mode to the base waveform (1029 resolves to 178 at the HAL level for this firmware), and pushes a composite int down to `HwcHal`, which forwards it to the panel driver. The daemon sees `HANDWRITTEN_FLAG` set on the layer and starts shoveling stroke pixels into the corresponding framebuffer region at ~6 ms cadence.

The model holds.

## The architecture, in plain words

The way low-latency ink works on Bigme is a three-lane race:

- **Lane 1 — the native daemon.** `/system/bin/handwrittenservice`, running as root, reads `/dev/input/event0` directly. It sees pen events before Android's `InputDispatcher` does. When a `SurfaceView` in the active window has the `HANDWRITTEN_FLAG | SURFACEVIEW_FLAG` bits set in its HWC layer metadata, the daemon rasterizes stroke pixels for that region and commits them to the EPD framebuffer. This is the visible ink.
- **Lane 2 — the Android input pipeline.** Your app continues to receive `MotionEvent`s through `onTouchEvent` as normal. Nothing is swallowed. Use those events for stroke-data capture, recognition, persistence — whatever the app actually needs to do with the ink.
- **Lane 3 — the EPD composite.** The panel controller is already integrating the daemon's pixels into the display waveform by the time your app thinks about drawing a frame. If your app also draws the stroke to its own canvas later, that draw layers on top of the daemon's pixels, but in practice the user has already seen their ink.

The app's sole job is to *tell the framework that a particular SurfaceView is a handwriting canvas*. One method call. That's it. All the heavy lifting happens below the Android framework.

Contrast this with Onyx. Onyx's `TouchHelper` ships a Java SDK (`com.onyx.android.sdk.pen.*`) that binds to a system service, and when you enable raw drawing mode it *swallows* `MotionEvent`s for the tracked region and re-surfaces them through a `RawInputCallback` you register. Bigme does not do this. You keep getting `MotionEvent`s normally. The only thing you need from the vendor SDK is the refresh-mode hint.

This is actually, for a third-party app, *easier* than Onyx's model. There's less to integrate. The only cost is that the API is undocumented and you have to reflect into it.

## The full API surface

Here's the reference material extracted from the reflection probe, organized by class.

### `xrz.framework.manager.XrzEinkManager`

Public constructor: `XrzEinkManager(android.content.Context)`. There is no `getInstance()` — you `new` it. It's cheap.

**Static methods:**

```
static void    disableStylus(boolean)
static void    disableTouch(boolean)
static void    enableColorRestoration(boolean)
static void    enableHomeAutoClean(boolean)
static void    forceGlobalRefresh(int)
static String  getGoogleId()
static boolean isColorRestorationEnabled()
static boolean isHomeAutoCleanEnabled()
static boolean isStylusDisabled()
static boolean isTouchDisabled()
static boolean needUpdateWaveform()
static void    setGoogleId(String)

// JNI-backed statics
static void    nativeForceGlobalRefresh(int)
static boolean nativeIsAntialiasEnable()
static boolean nativeIsAntialiasSupport()   // crashes on this firmware
static void    nativeSetAntiFlickerEnable(boolean)
static void    nativeSetAntialiasEnable(boolean)
static void    nativeSetAutoCleanEnable(boolean)
static void    nativeSetLayerRefreshMode(String, int)
```

**Instance methods:**

```
AppFreezeManager     getAppFreezeManager()
DisplayPolicyManager getDisplayPolicyManager()
SplitScreenManager   getSplitScreenManager()
Map                  getSystemInfo()
boolean              isAntialiasEnable()
boolean              isAntialiasSupport()
void                 registerGestureListener(GestureListener)
void                 setAntialiasEnable(boolean)
void                 setAntialiasEnableInternal(boolean)

// The refresh-mode setters — the important ones
void setRefreshModeByView(View, int)
void setRefreshModeByWindow(Window, int)
void setRefreshModeForPopupWindow(PopupWindow, int)
void setRefreshModeForSurfaceView(SurfaceView, int)   // ** THE ONE YOU WANT **

// OTA
void update(Context, int, RecoverySystem.ProgressListener)
```

**Constants:** `ACTION_LOGO=0`, `ACTION_WAVEFORM=1`, `VERSION="1.0.0"`. Nested: `XrzEinkManager$GestureListener`.

### `xrz.framework.manager.EinkRefreshMode`

Already reproduced above. The one you want for a handwriting `SurfaceView` is `EINK_HANDWRITE_MODE = 1029`. For an eraser overlay, `EINK_RUBBER_MODE = 1030`. To revert a view to the system default, `EINK_DEFAULT_MODE = 178`. Video playback canvases use `EINK_FAST_MODE = 179`.

### `xrz.framework.manager.DisplayPolicyManager`

Returned by `XrzEinkManager.getDisplayPolicyManager()`. Backed by the `xrz_display_policy_service` binder.

```
DisplayPolicy getPolicyByPackage(String)
DisplayPolicy getPolicyByPid(int)
DisplayPolicy getDefaultPolicyByPackage(String)
DisplayPolicy getTopAppPolicy()
void          forceSetOrientation(int)
void          onPackageIdle(String)

// Package-scoped tuning setters, all (String packageName, int value) -> boolean
boolean setRefreshModeForPackage
boolean setRefreshFrequencyForPackage
boolean setAnimFilterForPackage
boolean setAntiFlickerForPackage
boolean setAutoCleanForPackage
boolean setBleachModeForPackage
boolean setBleachBgColorForPackage
boolean setBleachCoverColorForPackage
boolean setBleachIconColorForPackage
boolean setBleachTextPlusForPackage
boolean setBrightnessLevelForPackage
boolean setColorEnhanceForPackage
boolean setColorModeForPackage
boolean setContrastForPackage
boolean setDarkLevelForPackage
boolean setDpiForPackage
boolean setScrollFlipForPackage
boolean setTextEnhanceForPackage
boolean setIsContrastSettingForPackage
boolean setIsDpiSettingForPackage
boolean setIsRefreshSettingForPackage

// Gesture
void registerGestureListener(IGestureListener)
void unregisterGestureListener(IGestureListener)

// Misc
void setNetworkMonitorEnable(boolean)
```

Constants: `SERVICE_NAME = "xrz_display_policy_service"`, `TAG = "DisplayPolicyManager"`.

### `xrz.framework.manager.DisplayPolicy` (Parcelable)

Per-app display-tuning record. The fields map 1:1 with the package-scoped setters above:

```
String  packageName
int     immutable
int     appDpi
float   appDpiScale
int     isDpiSetting
int     isRefreshSetting
int     isContrastSetting
int     refreshMode
int     refreshFrequency
int     appContrast
int     appAnimFilter
int     appBleachMode
int     appBleachTextPlus
int     appBleachIconColor
int     appBleachCoverColor
int     appBleachBgColor
int     appDarkLevel
int     appColorEnhance
int     appBrightnessLevel
int     appAutoClean
int     appAntiFlicker
int     appColorMode
int     appAntiAlias
int     appTextEnhance
int     appScrollFlip
```

### `xrz.framework.manager.XrzEinkManagerInternal`

A companion class, mostly static, presumably meant for system-internal callers but — as the probe confirmed — callable from anywhere.

```
static void    checkScroll(View, MotionEvent)
static int     getCleanFrequency()
static int     getGlobalRefreshMode()
static int     getScreenBrightnessLevel()
static int     getScreenColorEnhance()
static int     getScreenDarkLevel()
static int     getScrollFlipType()
static boolean isAntiFlickerEnable()
static boolean isAutoCleanCheckEnable()
static void    setAntiFlickerEnable(boolean)
static void    setAutoCleanEnable(boolean)
static void    setCleanFrequency(int)
static void    setGlobalRefreshMode(int)
static void    setLayerRefreshMode(String, int)
static void    setScreenBrightnessLevel(int)
static void    setScreenColorEnhance(int)
static void    setScreenDarkLevel(int)
static void    setScrollFlipType(int)

void updateDbValue(ContentValues)   // instance
```

### HWC layer-flag bits

Reproduced above. These are OR'd into the mode integer by the framework when it forwards to the HAL. You don't generally need to set them yourself — `setRefreshModeForSurfaceView` handles the flag bits for you — but the table is useful for decoding HWC log lines when debugging.

## Going deeper: the daemon, the ION buffer, and sync timing

The `XrzEinkManager.setRefreshModeForSurfaceView` recipe above is real. It helps. It's also a ceiling. It tells the HAL which waveform to use when it composes your surface — it does *not* get your ink pixels to the EPD any faster than your own draw loop can run. On a slow app thread, a cluttered SurfaceFlinger, or a `Canvas` fallback that locks the holder per-pointer-event, you still lose.

xNote doesn't stop at `XrzEinkManager`. Another dex dive into `/system/framework/framework.jar` — specifically `classes5.dex` of all places, the boot classloader stashes goodies in odd corners — surfaces the layer underneath:

```
Class descriptor  : 'Lcom/xrz/HandwrittenClient;'
  Interface #0 : 'Landroid/view/SurfaceHolder$Callback;'
  Direct methods -
    constructor(Context)
    bindView(View)
    connect(int, int)                        # width, height
    registerInputListener(InputListener)
    setInputEnabled(boolean)
    setOverlayEnabled(boolean)
    setBlendEnabled(boolean)
    setUseRawInputEvent(boolean)
    inValidate(Rect, int)                    # mode
    getCanvas()                              # Canvas
    getContent()                             # Bitmap
    updateLayout() / updateRotation()
    unBindView() / disconnect()

  InputListener (inner interface):
    int onInputTouch(action, x, y, pressure, tool[, time])
```

This is the real entry point, and it's the one `inksdk`'s `BigmeInkController` wraps. `HandwrittenClient` binds over binder to `IHandwrittenService` (the native daemon at `/system/bin/handwrittenservice`, version 1.4.0 on this firmware), opens an ION-backed shared-memory buffer sized to the client's view, and exposes two things:

1. A `Canvas` that draws *directly into the shared buffer* — zero copy from the app's perspective.
2. An `inValidate(Rect, mode)` call that tells the daemon to push that region to the EPD with a given waveform.

Meanwhile, the daemon intercepts `/dev/input/event*` at the kernel, runs the incoming pen stream through its internal `convertXY` coordinate transform, and dispatches to the app's registered `InputListener` on a binder thread. So the app gets *both* the raw pen events *and* direct write access to the overlay layer the EPD is compositing, in the same process.

The architecture that falls out of this:

```svg
<svg viewBox="0 0 900 640" xmlns="http://www.w3.org/2000/svg" style="font-family:ui-sans-serif,system-ui;font-size:12.5px;max-width:100%;height:auto" role="img" aria-label="HandwrittenClient architecture diagram">
  <defs>
    <marker id="arrA" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto">
      <path d="M0,0 L10,5 L0,10 z" fill="currentColor"/>
    </marker>
  </defs>

  <!-- APP BAND -->
  <rect x="20" y="20" width="860" height="170" rx="8" fill="#eaf3ff" stroke="#4171b4" stroke-width="1.5"/>
  <text x="36" y="42" font-weight="700">App process — your.app (regular UID)</text>

  <rect x="50" y="58" width="380" height="120" rx="5" fill="#ffffff" stroke="#335f9e"/>
  <text x="62" y="78" font-weight="600">Host SurfaceView (e.g. inksdk demo)</text>
  <rect x="66" y="90" width="350" height="80" rx="4" fill="#d7e5f7" stroke="#335f9e" stroke-dasharray="2,2"/>
  <text x="78" y="108" font-weight="600">contentBitmap — canonical ARGB_8888</text>
  <text x="78" y="126">mirrors completed strokes from the daemon's</text>
  <text x="78" y="142">ION buffer; posted to the SurfaceView holder</text>
  <text x="78" y="158">on stroke end — the persistent source of truth.</text>

  <rect x="460" y="58" width="400" height="120" rx="5" fill="#ffffff" stroke="#335f9e"/>
  <text x="472" y="78" font-weight="600">BigmeInkController.InputProxy (java.lang.reflect.Proxy)</text>
  <text x="472" y="98">• Canvas.drawLine(lastX,lastY,x,y,paint) → ION</text>
  <text x="472" y="116">• mainHandler.post(sink.onStrokeMove(...))</text>
  <text x="472" y="134">• inValidate(dirty, MODE_HANDWRITE = 1029)</text>
  <text x="472" y="160" fill="#a03530" font-weight="600">fires on binder thread; coalesced at 16 ms (vsync)</text>

  <!-- DAEMON BAND -->
  <rect x="20" y="220" width="860" height="180" rx="8" fill="#fff6de" stroke="#b89a32" stroke-width="1.5"/>
  <text x="36" y="242" font-weight="700">handwrittenservice (native daemon, UID 1000)</text>

  <rect x="50" y="258" width="230" height="120" rx="5" fill="#fffaec" stroke="#8a7a2f"/>
  <text x="62" y="278" font-weight="600">/dev/input/event* reader</text>
  <text x="62" y="298">intercepts pen/stylus events</text>
  <text x="62" y="316">below the Android input</text>
  <text x="62" y="334">dispatcher; applies internal</text>
  <text x="62" y="352">convertXY(panel → view-local).</text>

  <rect x="300" y="258" width="260" height="120" rx="5" fill="#fffaec" stroke="#8a7a2f"/>
  <text x="312" y="278" font-weight="600">IHandwrittenService (binder)</text>
  <text x="312" y="298">holds the registered</text>
  <text x="312" y="316">InputListener proxies;</text>
  <text x="312" y="334">forwards (action, x, y, pressure,</text>
  <text x="312" y="352">tool, time) per event.</text>

  <rect x="580" y="258" width="280" height="120" rx="5" fill="#fff1c8" stroke="#8a7a2f"/>
  <text x="592" y="278" font-weight="600">ION shared-memory buffer</text>
  <text x="592" y="298">size = connect(view.w, view.h);</text>
  <text x="592" y="316">daemon exposes a Canvas that</text>
  <text x="592" y="334">writes into this shmem directly.</text>
  <text x="592" y="352">Tagged with the HANDWRITTEN HWC flag.</text>

  <!-- COMPOSITOR BAND -->
  <rect x="20" y="430" width="860" height="190" rx="8" fill="#ededed" stroke="#666" stroke-width="1.5"/>
  <text x="36" y="452" font-weight="700">EPD compositor → e-ink panel</text>

  <rect x="80" y="470" width="340" height="130" rx="5" fill="#ffffff" stroke="#555"/>
  <text x="92" y="490" font-weight="600">Bottom layer — SurfaceView</text>
  <text x="92" y="510">= last commitToSurface() composition.</text>
  <text x="92" y="530">Contains the canonical contentBitmap</text>
  <text x="92" y="550">(the host's persistent stroke buffer).</text>
  <text x="92" y="570">Update cadence: app-side, on stroke end.</text>

  <rect x="480" y="470" width="340" height="130" rx="5" fill="#fff5f5" stroke="#555"/>
  <text x="492" y="490" font-weight="600">Top layer — ION overlay (HW flag)</text>
  <text x="492" y="510">daemon-painted strokes, live.</text>
  <text x="492" y="530">Wherever the ION pixel is white,</text>
  <text x="492" y="550">the SurfaceView shows through;</text>
  <text x="492" y="570">wherever it has ink, it occludes.</text>
  <text x="492" y="590">Update cadence: per inValidate(rect).</text>

  <!-- ARROWS -->

  <!-- 1. pen → /dev/input -->
  <g color="#8a7a2f" stroke="currentColor" fill="none" stroke-width="2">
    <path d="M 165 220 L 165 258" marker-end="url(#arrA)"/>
  </g>
  <text x="175" y="238" fill="#8a7a2f">pen events</text>

  <!-- 2. /dev/input → dispatcher -->
  <g color="#8a7a2f" stroke="currentColor" fill="none" stroke-width="2">
    <path d="M 280 318 L 298 318" marker-end="url(#arrA)"/>
  </g>

  <!-- 3. dispatcher → InputProxy (binder, dashed) -->
  <g color="#8a7a2f" stroke="currentColor" fill="none" stroke-width="2" stroke-dasharray="6,3">
    <path d="M 430 258 C 430 225 500 195 660 180" marker-end="url(#arrA)"/>
  </g>
  <text x="430" y="215" fill="#8a7a2f">binder callback — onInputTouch(a,x,y,p,t,time)</text>

  <!-- 4. InputProxy → ION canvas (drawLine) -->
  <g color="#335f9e" stroke="currentColor" fill="none" stroke-width="2">
    <path d="M 710 180 C 710 215 705 240 710 258" marker-end="url(#arrA)"/>
  </g>
  <text x="720" y="220" fill="#335f9e">drawLine → Canvas (ION)</text>

  <!-- 5. InputProxy → contentBitmap (main thread post, via completedStrokes) -->
  <g color="#335f9e" stroke="currentColor" fill="none" stroke-width="2">
    <path d="M 470 178 C 455 172 440 170 430 170" marker-end="url(#arrA)"/>
  </g>
  <text x="438" y="165" fill="#335f9e" text-anchor="end">sink.onStrokeMove (main thread)</text>

  <!-- 6. Sync flow: contentBitmap → ION canvas -->
  <g color="#a03530" stroke="currentColor" fill="none" stroke-width="2.5">
    <path d="M 240 180 C 225 205 540 200 720 260" marker-end="url(#arrA)"/>
  </g>
  <text x="470" y="202" fill="#a03530" font-weight="700" text-anchor="middle">syncOverlay(bitmap, region, force) — scroll / delete / snap / clear</text>

  <!-- 7. ION → EPD (inValidate) -->
  <g color="#8a7a2f" stroke="currentColor" fill="none" stroke-width="2">
    <path d="M 720 378 C 720 410 670 435 640 470" marker-end="url(#arrA)"/>
  </g>
  <text x="640" y="425" fill="#8a7a2f">inValidate(rect, mode)</text>

  <!-- 8. SurfaceView → compositor -->
  <g color="#555" stroke="currentColor" fill="none" stroke-width="1.6" stroke-dasharray="4,3">
    <path d="M 240 188 C 240 300 240 420 240 468" marker-end="url(#arrA)"/>
  </g>
  <text x="150" y="390" fill="#555">SurfaceFlinger</text>
  <text x="150" y="406" fill="#555">composite</text>

</svg>
```

The blue flow is what moves ink: the daemon's `InputListener` callback fires on a binder thread, the proxy writes directly into the ION buffer via the shared `Canvas`, and after `inValidate` the EPD reflects the new pixels *within the same frame*. The host's bitmap cache is updated later on the main thread, from the host's `StrokeCallback`, once the stroke is done.

The yellow flow is input and refresh — all on the daemon side, invisible to Android's normal input dispatcher.

The red flow is the one that took the longest to get right.

### Sync timing: when to re-rasterize

Two buffers displaying the same content in two separate codepaths is an eventual-consistency problem. The daemon's ION accumulates every painted stroke forever — it has no knowledge of the app's scroll state, its snap-to-shape replacements, or its "delete this stroke" commands. So they drift.

A naive first attempt is to wipe the ION to white over the stroke's bounding box on every `ACTION_UP`. Clean-slate for the next stroke, no residue accumulating. This breaks intersecting strokes: if the user draws an `H`, the second vertical crossbar's `ACTION_UP` wipes pixels at the intersection where the first vertical and the horizontal cross, and the horizontal disappears until something else causes a repaint. The daemon's ION is white at the crossing, the SurfaceView has the horizontal stroke under it, but the HWC layer order has the ION on top — so white wins. The `H` looks like `I I`.

So the overlay needs to *accumulate* during live drawing, and re-rasterize only when the host's canonical bitmap has diverged from what the daemon has been painting. The events that cause that divergence, and how to handle each:

- **Scroll.** Every pixel in the ION is at a stale y-offset after the viewport moves. Redraw from the bitmap. The host's commit triggers SurfaceFlinger, which recomposites all layers over the EPD — so just blitting the bitmap into the ION buffer is enough; SurfaceFlinger handles the panel refresh.

- **Delete, snap, diagram create/resize.** The bitmap no longer matches what the overlay has been painting. A bitmap blit alone isn't enough here: the overlay's *displayed* pixels (what's currently on the EPD) are still the pre-mutation ink, and a SurfaceView-only commit doesn't cause the daemon's HWC to recomposite its overlay layer. You have to explicitly poke the daemon with `inValidate(region, mode)` — `MODE_GU16` (16-level ghost-tolerant grey update) is the sweet spot: no flash, cleanly transitions both directions (unlike `MODE_HANDWRITE`, which is tuned for adding ink and leaves stale waveform state when clearing).

That's the same problem structure Onyx devices have, just solved with different vendor APIs — Onyx exposes `setRawDrawingEnabled(false)` / `setRawDrawingEnabled(true)` to cycle the raw-drawing layer and force the EPD to recomposite; Bigme exposes `inValidate(region, MODE_GU16)`. The host doesn't care which: it expresses the mutation in device-neutral terms and lets the controller translate. That's the whole point of the `InkController` interface in this library:

```kotlin
interface InkController {
    /** Catch the EPD up to [bitmap] over [region]. force=true when the
     *  SurfaceView commit alone won't reveal the change (delete/snap);
     *  false for scroll, where SurfaceFlinger drives the EPD refresh. */
    fun syncOverlay(bitmap: Bitmap, region: Rect? = null, force: Boolean = false) = Unit
    // …
}
```

The Bigme implementation — blit the bitmap into the ION, and if forced, push it to the EPD with a partial-refresh waveform:

```kotlin
override fun syncOverlay(bitmap: Bitmap, region: Rect?, force: Boolean) {
    val canvas = cls.getMethod("getCanvas").invoke(client) as? Canvas ?: return
    // SRC mode replaces every ION pixel in one blit, no composition with
    // the daemon's accumulated ink.
    val paint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
    canvas.drawBitmap(bitmap, 0f, 0f, paint)
    if (!force) return
    val rect = region ?: Rect(0, 0, view.width, view.height)
    cls.getMethod("inValidate", Rect::class.java, Int::class.javaPrimitiveType)
        .invoke(client, rect, MODE_GU16)
}
```

The Onyx implementation lives in a different file but speaks the same language:

```kotlin
override fun syncOverlay(bitmap: Bitmap, region: Rect?, force: Boolean) {
    if (!force || !isActive) return
    touchHelper.setRawDrawingEnabled(false)
    touchHelper.setRawDrawingEnabled(true)
}
```

The host calls `inkController.syncOverlay(...)` at attach time (seed), scroll UP (soft sync), and the force-true path at every stroke-list mutation. One moving part, two device backends, same semantics.

The other timing lesson: most host implementations Choreographer-coalesce their bitmap rebuilds, which means asking for a sync immediately after a mutation reads a *stale* bitmap — the Choreographer callback that rebuilds the bitmap hasn't fired yet. The symptom is that mutations appear to "not update until the next scroll" (because the next scroll's rebuild finally paints the correct content). Force a synchronous rebuild before the sync, or defer the sync one frame so the rebuild completes first.

## What we cannot fix: the touch-IC cold wake

There is one cost the library cannot eliminate.

The very first stroke after a cold app launch — or after a long idle period with no stylus proximity — pays **100–170 ms** in `pen.kernel_to_jvm` on the HiBreak Plus, even though every JVM-side metric is sub-frame. Subsequent strokes pay 3–8 ms. (See [`metrics.md`](metrics.md) for the full counter taxonomy.)

The wake cost is in the **touch IC sleep state**. When no stylus is near the screen for a while, the touch controller drops into a low-sample-rate / sleep state. The first physical capacitive presence wakes it, and the wake takes ~100 ms. We measured this empirically with the slim demo:

- The xrz daemon **never dispatches `ACTION_NEAR` (hover) events to the registered `InputListener`** under any configuration we tried — neither cooked dispatch nor `setUseRawInputEvent(true)`. The `ACTION_NEAR` pre-warm pattern that xNote's reverse-engineering history hinted at is dead code on this firmware.
- Therefore `inValidate(MODE_HANDWRITE)` on a 1×1 rect, `setOverlayEnabled` toggles, and any other JVM-driven daemon call **cannot warm the touch IC**. They reach the EPD waveform engine, which is the wrong subsystem.
- A physical hover (stylus held a centimeter above the panel) DOES wake the IC. But that wake is in the kernel input pipeline, never surfaced to user-space.

Real users almost always hover before touching, so the IC is warm by the time the pen contacts the screen. The cold case occurs only when the pen is already in contact at app launch, or after a long idle with the pen put down elsewhere. The library exposes no `prewarm()` API — anything we could call from the JVM has been verified to not move this number — and the `ACTION_NEAR` handler in `InputProxy` is intentionally absent.

## Using the library

If you've made it this far you don't need to repeat the investigation. Drop in the `inkcontroller` module and call:

```kotlin
val ink = InkControllerFactory.create()  // Bigme on Bigme, Onyx elsewhere
ink.attach(surfaceView, Rect(0, 0, surfaceView.width, surfaceView.height), strokeCallback)
ink.setStrokeStyle(widthPx = 6f, color = Color.BLACK)
// ... user writes; daemon paints into ION and fires onStrokeBegin/Move/End ...
ink.syncOverlay(contentBitmap, force = true)  // after any host-side mutation
ink.detach()  // before activity destroy
```

The `:demo` module in this repo is a working ~250-line app that runs benchmarks against the ink path and dumps the perf counters into a UI table, so you can validate the integration on your own device before adopting it. It also serves as the reference for the ION-buffer mirror pattern (mirror at stroke end via either `mirrorOverlay` or coordinate replay, never wipe the daemon buffer between strokes — see the comments in `InkSurfaceView.kt`).

Onyx's path is gated on having the Onyx vendor runtime, so on non-Onyx hardware `OnyxInkController.attach` returns false silently and the host falls back to its own MotionEvent + Canvas loop. On Bigme hardware, `BigmeInkController.attach` succeeds and the daemon path is engaged.

## The technique, generalized

This whole investigation was a pattern you can apply to any undocumented Android vendor SDK. The seven phases, in order:

**Phase 1 — enumerate.** Get the lay of the land. Use `pm list packages -f | grep -i <vendor>` to find vendor-namespace packages. Use `service list` to find vendor binder services. Use `ls /system/framework /vendor/framework /system/priv-app` to find vendor jars and privileged APKs. Use `getprop | grep -i <vendor>` to capture vendor system properties — these often hint at configuration knobs that the SDK exposes.

**Phase 2 — recon the privilege model.** Before you spend time disassembling, figure out whether the vendor's own apps are using a privileged path your app can't reach. `dumpsys package <stock_app>` gives you the UID (`u0_a*` or `system`?), the signature hash (compare it to `dumpsys package android` for the platform signature), and the declared permissions. If the stock app is regular-UID, not platform-signed, and declares no signature-gated vendor permissions, the SDK is reachable to any app. If not, you're probably blocked and you should stop here.

**Phase 3 — disassemble.** `adb pull` the stock APK, `unzip` it, run `dexdump -d` on each `classes*.dex`. Grep the output for `invoke.*<VendorNamespace>` to find call sites, and for capability keywords like `setRefresh`, `forceRefresh`, or whatever matches your target. This gives you the API surface the stock app *actually uses* — which is a much smaller, more relevant set than the SDK's full surface.

**Phase 4 — dumpsys shortcut.** For every vendor binder service, run `dumpsys <service>`. Vendor services are very rarely hardened against reading. You'll often get production configuration values, live state, and internal field names for free. This alone is sometimes enough to solve the problem.

**Phase 5 — reflection probe.** Write a small Java class that `Class.forName`s the vendor classes, enumerates their methods and fields via reflection, dumps static-final fields (constant tables!), and tries benign read-only calls. Compile with `javac`, dex with `d8`, jar with `classes.dex` at the root, push to `/data/local/tmp/`, run via `CLASSPATH=... app_process /system/bin YourClass`. This runs under the Android runtime with UID 2000 (shell). If your reflection calls succeed there — and especially if the IPC-backed ones succeed — they will succeed from your own regular-UID app. This is also the easiest way to recover constant tables: `dexdump` inlines int constants at call sites, but reflection on the real class file gives you back the names.

**Phase 6 — live-behavior correlation.** Start `adb logcat -v time`. Launch the stock app. Exercise the capability (drive it with `input swipe`, `input tap`, or `monkey`). Grep the log for vendor class names, HAL-level mode strings, and bit patterns. Decode the bit patterns against the constant tables you recovered in phase 5. When a HAL log line cleanly decomposes into the OR of a handful of documented flags and a documented base mode, you know your model is right.

**Phase 7 — native daemons.** If `service list` surfaces a suspiciously-named native binder service — something that's not registered by a Java system service — look at `ps -ef` and `/proc/<pid>/cmdline`. A daemon launched with `-p /dev/input/event*` is almost certainly intercepting raw input events at the kernel level, which is the standard vendor pattern for sub-frame latency on slow-panel devices. That tells you the capability is delivered at a layer below the Android framework and the Java SDK is just a hint-dispatch mechanism.

## The reusable prompt

Distilled into a prompt you can hand to a coding agent with a device on the other end of `adb`:

```
Reverse-engineer an undocumented Android vendor SDK for <CAPABILITY> on <DEVICE MODEL>.

Setup:
- Device connected via ADB. ADB path: <ADB PATH>
- Know the target capability, e.g. "low-latency pen ink", "front-light control",
  "hardware key remapping", "raw sensor access".
- Identify the stock app that uses this capability (e.g. the vendor's own Notes /
  Reader / Camera app).

Phase 1 — Enumerate:
1. `adb shell pm list packages -f | grep -iE '<vendor tags>'`. Write down every
   vendor-namespace package.
2. `adb shell service list` — look for binder services with the vendor prefix.
3. `adb shell ls /system/framework/ /system/priv-app/ /vendor/framework/` — find
   vendor jars and APKs.
4. `adb shell getprop | grep -i <vendor>` — capture every vendor system property.

Phase 2 — Recon privilege model:
5. `adb shell dumpsys package <stock_app>` — note the UID (u0_a* = regular app,
   system = privileged), the signature hash, and declared permissions. Compare
   the signature against `dumpsys package android` (platform signature). If the
   stock app is a regular UID and not platform-signed and declares no special
   permissions, the SDK is likely accessible to ordinary apps.
6. `adb shell ls /system/etc/permissions/ /vendor/etc/permissions/` + grep for
   the vendor prefix — find any signature-gated shared libraries.

Phase 3 — Disassemble:
7. `adb pull <stock_apk_path>` and `<vendor_framework_jar>`.
8. Unzip; `dexdump -d classes*.dex` on each dex file.
9. `grep -iE 'invoke.*<VendorClass>|setRefresh|forceRefresh|<capability keywords>'`
   in the dexdump output to find call sites and invocation patterns.
10. `grep -E "Class descriptor" dexdump.txt | grep -iE '<vendor prefix>'` to
    catalog class namespaces.

Phase 4 — Dumpsys shortcut:
11. For every vendor binder service, `adb shell dumpsys <service>` and see if it
    leaks state without auth. Vendor services are rarely hardened for readers.

Phase 5 — Reflection probe:
12. Write a Java file that does `Class.forName("<vendor.class>")`, enumerates its
    declared fields (filter for static + final for constant tables) and methods,
    tries benign read calls via reflection, and if relevant, constructs an
    instance with a Context obtained via `android.app.ActivityThread.systemMain()
    .getSystemContext()`.
13. Compile with `javac -cp <android.jar>`; dex with `d8` (from Android
    build-tools/<ver>/lib/d8.jar); zip into a jar with classes.dex at the root;
    `adb push` to `/data/local/tmp`.
14. Run it via `adb shell "CLASSPATH=/data/local/tmp/probe.jar app_process
    /system/bin ProbeMain"`. The process runs with UID 2000 (shell) — strictly
    not privileged. If your reflection calls succeed here, they will succeed in
    your regular-UID app.

Phase 6 — Live-behavior correlation:
15. Launch the stock app. Start `adb logcat -v time` in the background.
16. Exercise the capability (use `adb shell input swipe`, `input tap`, or drive
    with `monkey`).
17. Grep the log for the vendor-namespace class names, the binder-service names,
    HAL-level mode words, and any bit patterns. Decode bit patterns against the
    constant tables you extracted in Phase 5.

Phase 7 — Native daemons:
18. If `service list` shows a suspiciously-named native binder service, find its
    pid with `adb shell ps -ef | grep <svc>` and inspect `/proc/<pid>/cmdline`.
    A daemon launched with `-p /dev/input/event*` is almost certainly
    intercepting raw input, which is a common vendor pattern for sub-frame
    latency.

Output:
- Full API surface (every class, every method, every constant) as a code block.
- A minimal Kotlin integration snippet that reflects into the SDK with a
  device gate and a classloader fallback.
- A one-paragraph explanation of the *architecture*: how the vendor actually
  delivers the capability (daemon? HAL flag? system service?).
- Known limitations: which firmware versions were probed, what's untested, and
  the risk that undocumented APIs change silently across OTA.
```

## Caveats and closing

A few things to be clear about.

**Firmware drift.** Everything in this post was derived from one device — a Bigme HiBreak Plus running Android 14, firmware timestamped early 2024. Undocumented APIs change silently. Class names move, constants get renumbered, fields get added. The reflection wrapper in `BigmeInkController` is deliberately defensive: `Class.forName` is wrapped in try/catch, method lookup is wrapped in try/catch, every call path can fail without taking the app down. If a future OTA breaks the API, `attach` returns false and the host falls back to its generic `MotionEvent` + `Canvas` path with a warning.

**Other Bigme models.** The HiBreak Plus, the InkNote color line, the Galy series — they all seem to ship a flavor of the xrz framework, but only the HiBreak Plus has been personally probed. If you run through this technique on another Bigme device and find deltas (new constants, renamed methods, different daemon paths), file an issue or PR against this repo with a writeup.

**Legality and ethics.** Reflection against classes the platform loads into your process is not a privilege escalation. It's not rooting the device. It's not exploiting anything. It's using APIs that are, by construction, callable from any app — Bigme just hasn't documented them. That said, if you ship a product that depends on undocumented vendor APIs, you're making a voluntary commitment to maintain compatibility yourself across firmware updates. Be honest with yourself about that commitment before you take it on.

**Credit.** The architectural insight that Bigme would probably ship something Onyx-shaped — a root daemon plus HWC flags — came from having spent too much time in Onyx's SDK. Onyx documented their approach; that made it possible to recognize the pattern when its Bigme analogue showed up. Good reverse engineering builds on someone else's good documentation.

If you're building an e-ink app and want it to feel fast on Bigme hardware, drop the `inkcontroller` library into your project. If you're building a different kind of app against a different undocumented vendor SDK on Android, run through the seven phases. You'll be surprised how often the vendor's own binder services will just *tell* you what you need to know, if you remember to ask them with `dumpsys`.

Happy reversing.
