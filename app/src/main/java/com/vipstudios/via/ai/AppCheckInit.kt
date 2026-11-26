package com.vipstudios.via.ai

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.recaptcha.RecaptchaAppCheckProviderFactory
import com.vipstudios.via.BuildConfig
import com.vipstudios.via.R

private const val TAG = "AppCheckInit"

private const val DEBUG_TOKEN_EXTRA = "FIREBASE_APPCHECK_DEBUG_TOKEN"
private const val DEBUG_STORE_PREFIX = "com.google.firebase.appcheck.debug.store."
private const val DEBUG_SECRET_KEY = "com.google.firebase.appcheck.debug.DEBUG_SECRET"

@SuppressLint("ApplySharedPref")
fun configureAppCheck(context: Context, intent: Intent?) {
    try {
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(context)
        }

        if (BuildConfig.DEBUG) {
            val debugToken = intent?.getStringExtra(DEBUG_TOKEN_EXTRA)

            if (!debugToken.isNullOrEmpty()) {
                val persistenceKey = FirebaseApp.getInstance().persistenceKey
                val prefs = context.getSharedPreferences(
                    "$DEBUG_STORE_PREFIX$persistenceKey",
                    Context.MODE_PRIVATE
                )
                // Synchronous commit is required so DebugAppCheckProviderFactory reads the secret immediately.
                prefs.edit()
                    .putString(DEBUG_SECRET_KEY, debugToken)
                    .commit()
                Log.d(TAG, "App Check debug secret configured.")
            }

            FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                DebugAppCheckProviderFactory.getInstance()
            )
            Log.d(TAG, "DebugAppCheckProviderFactory installed.")
        } else {
            val siteKey = context.getString(R.string.recaptcha_site_key)
            if (siteKey.isNotBlank()) {
                FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                    RecaptchaAppCheckProviderFactory.getInstance(siteKey)
                )
            } else {
                Log.e(TAG, "recaptcha_site_key resource string is blank.")
            }
        }
    } catch (e: Exception) {
        if (BuildConfig.DEBUG) {
            Log.e(TAG, "Failed to initialize Firebase App Check", e)
        } else {
            Log.e(TAG, "Failed to initialize Firebase App Check: ${e.javaClass.simpleName}")
        }
    }
}
