package com.inksdk.demo

import android.app.Application
import android.os.Build
import android.util.Log

class DemoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("L")
                Log.i(TAG, "HiddenApiBypass enabled")
            } catch (t: Throwable) {
                Log.w(TAG, "HiddenApiBypass failed: ${t.message}")
            }
        }
    }
    companion object { private const val TAG = "DemoApp" }
}
