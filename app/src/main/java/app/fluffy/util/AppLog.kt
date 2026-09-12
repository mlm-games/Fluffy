package app.fluffy.util

import android.util.Log


object AppLog {
    // Set via init from Application if needed; defaults to debug detection below.
    @Volatile
    var debug: Boolean = true

    fun d(tag: String, msg: String, tr: Throwable? = null) {
        if (!debug) return
        if (tr != null) Log.d(tag, msg, tr) else Log.d(tag, msg)
    }

    fun w(tag: String, msg: String, tr: Throwable? = null) {
        if (tr != null) Log.w(tag, msg, tr) else Log.w(tag, msg)
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        if (tr != null) Log.e(tag, msg, tr) else Log.e(tag, msg)
    }
}
