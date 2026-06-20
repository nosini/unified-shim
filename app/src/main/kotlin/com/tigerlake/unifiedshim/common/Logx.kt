package com.tigerlake.unifiedshim.common

import android.util.Log

/** Single tag prefix so logcat is easy to filter: `adb logcat -s ushim`. */
object Logx {
    private const val TAG = "ushim"

    fun d(where: String, msg: String) = Log.d(TAG, "[$where] $msg")
    fun i(where: String, msg: String) = Log.i(TAG, "[$where] $msg")
    fun w(where: String, msg: String) = Log.w(TAG, "[$where] $msg")
    fun e(where: String, msg: String, t: Throwable? = null) = Log.e(TAG, "[$where] $msg", t)
}
