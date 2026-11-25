package com.vipstudios.via

import android.app.Application
import com.google.android.gms.ads.MobileAds

class ViaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialises the Google Mobile Ads SDK (test IDs are set in app/build.gradle.kts).
        try {
            MobileAds.initialize(this) {}
        } catch (_: Exception) {
            // Ads must never crash the assistant.
        }
    }
}
