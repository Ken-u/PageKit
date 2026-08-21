package com.kenjc.pagekit.profile

import android.app.Application
import android.os.Build
import android.webkit.WebView

object ProfileProcess {
    private const val SLOT_PREFIX = ":profile"

    fun currentSlot(packageName: String): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val process = Application.getProcessName()
        return process.removePrefix(packageName).takeIf { it.startsWith(SLOT_PREFIX) }
            ?.removePrefix(SLOT_PREFIX)
            ?.toIntOrNull()
            ?.takeIf { it in 1..3 }
    }

    fun prepareWebViewDataDirectory(packageName: String): Int? {
        val slot = currentSlot(packageName) ?: return null
        WebView.setDataDirectorySuffix("pagekit_profile_$slot")
        return slot
    }
}
