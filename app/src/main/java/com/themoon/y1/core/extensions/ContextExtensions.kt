package com.themoon.y1.core.extensions

import android.content.Context
import android.os.Vibrator
import android.widget.Toast
import com.themoon.y1.views.Notice

fun Context.showToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
    Notice.show(this, message, long = duration == Toast.LENGTH_LONG)
}

fun Context.vibrateFeedback(milliseconds: Long = 25L) {
    try {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        vibrator?.vibrate(milliseconds)
    } catch (_: Exception) {
    }
}
