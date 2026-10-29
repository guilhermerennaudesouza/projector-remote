package com.projector.remote.adb

import android.util.Base64
import com.tananaev.adblib.AdbBase64

class AndroidBase64 : AdbBase64 {
    override fun encodeToString(b: ByteArray): String {
        return Base64.encodeToString(b, Base64.NO_WRAP)
    }
}
