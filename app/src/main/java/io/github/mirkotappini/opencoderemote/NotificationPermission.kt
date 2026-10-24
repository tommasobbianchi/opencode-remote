package io.github.mirkotappini.opencoderemote

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Helper minimo per `POST_NOTIFICATIONS` (Android 13+).
 *
 * Non viene chiamato automaticamente: il layer UI lo collegherà quando mostra
 * per la prima volta lo stato "connesso". Su API < 33 è sempre concesso.
 */
object NotificationPermission {

    const val REQUEST_CODE = 4301

    fun isGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun requestIfNeeded(activity: Activity) {
        if (isGranted(activity)) return
        activity.requestPermissions(
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_CODE,
        )
    }
}
