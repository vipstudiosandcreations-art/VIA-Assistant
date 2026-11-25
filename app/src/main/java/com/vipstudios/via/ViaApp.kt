package com.vipstudios.via

import android.app.Application
import android.util.Log
import com.google.android.gms.ads.MobileAds

private const val TAG = "ViaApp"

class ViaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Safely initialize Google Mobile Ads SDK without risking startup crashes.
        try {
            MobileAds.initialize(this) { status ->
                Log.d(TAG, "MobileAds initialized: ${status.adapterStatusMap.keys}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "MobileAds initialization skipped safely", t)
        }
    }
}
