package com.vipstudios.via.device

import android.Manifest
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.vipstudios.via.BuildConfig
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

private const val TAG = "CallControlManager"

data class CallUiState(
    val monitoringActive: Boolean = false,
    val isRinging: Boolean = false,
    val callStateLabel: String = "Idle",
    val callerDisplay: String = "",
    val hasPhoneStatePermission: Boolean = false,
    val hasAnswerPermission: Boolean = false,
    val isDefaultDialer: Boolean = false,
    val statusNote: String = "Call monitoring is off."
)

data class CallActionResult(
    val succeeded: Boolean,
    val message: String,
    val offerSystemAction: Boolean = false
)

/**
 * Safe Android-supported incoming call detector and Accept/Decline controller.
 *
 * Uses modern TelephonyCallback (API 31+) or ACTION_PHONE_STATE_CHANGED (API 26-30),
 * never bypasses Android permissions/roles, and never records calls.
 */
class CallControlManager(context: Context) {
    private val ctx = context.applicationContext

    val state = MutableStateFlow(CallUiState())
    val incomingAnnouncements = MutableSharedFlow<String>(extraBufferCapacity = 4)

    private var registered = false
    private var lastAnnouncedRinging = false

    @Suppress("NewApi")
    private var telephonyCallback: Any? = null

    private val legacyPhoneReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
            val stateExtra = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            @Suppress("DEPRECATION")
            val numberExtra = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            val callState = when (stateExtra) {
                TelephonyManager.EXTRA_STATE_RINGING -> TelephonyManager.CALL_STATE_RINGING
                TelephonyManager.EXTRA_STATE_OFFHOOK -> TelephonyManager.CALL_STATE_OFFHOOK
                else -> TelephonyManager.CALL_STATE_IDLE
            }
            handleCallStateChange(callState, numberExtra)
        }
    }

    fun hasPhoneStatePermission(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    fun hasAnswerPermission(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED

    fun isDefaultDialerApp(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val rm = ctx.getSystemService(RoleManager::class.java)
                rm?.isRoleHeld(RoleManager.ROLE_DIALER) == true
            } else {
                val tm = ctx.getSystemService(TelecomManager::class.java)
                tm?.defaultDialerPackage == ctx.packageName
            }
        } catch (_: Exception) {
            false
        }
    }

    fun refreshPermissions() {
        val phonePerm = hasPhoneStatePermission()
        val answerPerm = hasAnswerPermission()
        val dialer = isDefaultDialerApp()
        val cur = state.value
        val note = when {
            !phonePerm -> "Grant Phone State permission to detect incoming calls."
            !registered -> "Call monitoring is off."
            cur.isRinging -> "Incoming call detected."
            else -> "Monitoring incoming calls safely (Android permissions active)."
        }
        state.value = cur.copy(
            monitoringActive = registered && phonePerm,
            hasPhoneStatePermission = phonePerm,
            hasAnswerPermission = answerPerm,
            isDefaultDialer = dialer,
            statusNote = note
        )
    }

    fun startMonitoring(): Boolean {
        refreshPermissions()
        if (!hasPhoneStatePermission()) {
            state.value = state.value.copy(
                monitoringActive = false,
                statusNote = "Phone State permission is required to detect incoming calls."
            )
            return false
        }
        if (registered) return true

        return try {
            val tm = ctx.getSystemService(TelephonyManager::class.java)
            if (tm == null) {
                state.value = state.value.copy(statusNote = "Telephony service unavailable on this device.")
                return false
            }
            if (Build.VERSION.SDK_INT >= 31) {
                registerModernCallback(tm)
            } else {
                val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
                ContextCompat.registerReceiver(ctx, legacyPhoneReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
            }
            registered = true
            refreshPermissions()
            true
        } catch (e: SecurityException) {
            if (BuildConfig.DEBUG) Log.w(TAG, "SecurityException starting call monitor", e)
            else Log.w(TAG, "SecurityException starting call monitor")
            registered = false
            state.value = state.value.copy(
                monitoringActive = false,
                statusNote = "Android restricted phone state monitoring without permission."
            )
            false
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Unable to start call monitor", e)
            else Log.w(TAG, "Unable to start call monitor: ${e.javaClass.simpleName}")
            false
        }
    }

    @RequiresApi(31)
    private fun registerModernCallback(tm: TelephonyManager) {
        val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(callState: Int) {
                handleCallStateChange(callState, null)
            }
        }
        telephonyCallback = cb
        tm.registerTelephonyCallback(ctx.mainExecutor, cb)
    }

    fun stopMonitoring() {
        if (!registered) return
        registered = false
        lastAnnouncedRinging = false
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val tm = ctx.getSystemService(TelephonyManager::class.java)
                val cb = telephonyCallback as? TelephonyCallback
                if (tm != null && cb != null) {
                    tm.unregisterTelephonyCallback(cb)
                }
                telephonyCallback = null
            } else {
                ctx.unregisterReceiver(legacyPhoneReceiver)
            }
        } catch (_: Exception) {
        }
        state.value = state.value.copy(
            monitoringActive = false,
            isRinging = false,
            callStateLabel = "Idle",
            callerDisplay = "",
            statusNote = "Call monitoring stopped."
        )
    }

    private fun handleCallStateChange(callState: Int, numberFromBroadcast: String?) {
        val caller = numberFromBroadcast?.takeIf { it.isNotBlank() } ?: "an unknown caller"
        when (callState) {
            TelephonyManager.CALL_STATE_RINGING -> {
                state.value = state.value.copy(
                    isRinging = true,
                    callStateLabel = "Ringing",
                    callerDisplay = numberFromBroadcast?.takeIf { it.isNotBlank() } ?: "Incoming call (number hidden by Android)",
                    hasPhoneStatePermission = hasPhoneStatePermission(),
                    hasAnswerPermission = hasAnswerPermission(),
                    isDefaultDialer = isDefaultDialerApp(),
                    statusNote = "Incoming call from $caller."
                )
                if (!lastAnnouncedRinging) {
                    lastAnnouncedRinging = true
                    incomingAnnouncements.tryEmit("Incoming call from $caller.")
                }
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                lastAnnouncedRinging = false
                state.value = state.value.copy(
                    isRinging = false,
                    callStateLabel = "In call",
                    statusNote = "Call active."
                )
            }
            else -> {
                lastAnnouncedRinging = false
                state.value = state.value.copy(
                    isRinging = false,
                    callStateLabel = "Idle",
                    callerDisplay = "",
                    statusNote = if (registered) "Monitoring incoming calls safely." else "Call monitoring is off."
                )
            }
        }
    }

    fun answerIncomingCall(): CallActionResult {
        if (!hasAnswerPermission()) {
            return CallActionResult(
                succeeded = false,
                message = "Android requires the Answer Phone Calls permission (and on some devices, Default Phone app role) to answer calls automatically. Opening the Phone screen instead.",
                offerSystemAction = true
            )
        }
        val telecom = ctx.getSystemService(TelecomManager::class.java)
            ?: return CallActionResult(false, "Telecom service isn't available on this device.", offerSystemAction = true)

        return try {
            @Suppress("MissingPermission")
            telecom.acceptRingingCall()
            CallActionResult(true, "Answering the incoming call.")
        } catch (e: SecurityException) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Android denied acceptRingingCall", e)
            else Log.w(TAG, "Android denied acceptRingingCall")
            CallActionResult(
                succeeded = false,
                message = "This Android device restricts answering calls unless VIA is set as the Default Phone app. Opening Phone settings/dialer.",
                offerSystemAction = true
            )
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "acceptRingingCall failed", e)
            else Log.w(TAG, "acceptRingingCall failed: ${e.javaClass.simpleName}")
            CallActionResult(
                succeeded = false,
                message = "Could not answer the call automatically (${e.message ?: "unsupported"}). Please use your phone's call screen.",
                offerSystemAction = true
            )
        }
    }

    fun declineIncomingCall(): CallActionResult {
        if (!hasAnswerPermission()) {
            return CallActionResult(
                succeeded = false,
                message = "Android requires the Answer Phone Calls permission to decline calls. Please use your phone's call notification.",
                offerSystemAction = true
            )
        }
        if (Build.VERSION.SDK_INT < 28) {
            return CallActionResult(
                succeeded = false,
                message = "Declining calls via app requires Android 9 or newer. Please decline from the system call screen.",
                offerSystemAction = true
            )
        }
        val telecom = ctx.getSystemService(TelecomManager::class.java)
            ?: return CallActionResult(false, "Telecom service isn't available on this device.", offerSystemAction = true)

        return try {
            @Suppress("DEPRECATION", "MissingPermission")
            val ended = telecom.endCall()
            if (ended) {
                CallActionResult(true, "Call declined.")
            } else {
                CallActionResult(
                    succeeded = false,
                    message = "Android did not allow ending the call from VIA. Please decline from the system call screen.",
                    offerSystemAction = true
                )
            }
        } catch (e: SecurityException) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Android denied endCall", e)
            else Log.w(TAG, "Android denied endCall")
            CallActionResult(
                succeeded = false,
                message = "This Android device restricts declining calls unless VIA holds the Default Phone role.",
                offerSystemAction = true
            )
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "endCall failed", e)
            else Log.w(TAG, "endCall failed: ${e.javaClass.simpleName}")
            CallActionResult(false, "Could not decline the call automatically.", offerSystemAction = true)
        }
    }

    fun openSupportedPhoneFallback() {
        val intent = Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(intent)
        } catch (_: Exception) {
            try {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
            }
        }
    }

    fun openDefaultPhoneRoleSettings() {
        try {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }
}
