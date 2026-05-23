package com.myra.assistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.provider.ContactsContract
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import com.myra.assistant.R
import com.myra.assistant.ui.main.MainActivity

class CallMonitorService : Service() {

    companion object {
        const val CHANNEL_ID = "myra_call_channel"
        const val ACTION_CALL_ENDED = "com.myra.CALL_ENDED"
        const val EXTRA_INCOMING_CALL = "INCOMING_CALL"
        const val EXTRA_CALLER_NAME = "CALLER_NAME"
    }

    private var telephonyManager: TelephonyManager? = null
    private var phoneStateListener: PhoneStateListener? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(2, buildNotification())
        startListening()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        telephonyManager?.listen(phoneStateListener,
            PhoneStateListener.LISTEN_NONE)
    }

    // ─── Start Listening ──────────────────────────
    private fun startListening() {
        telephonyManager = getSystemService(TELEPHONY_SERVICE)
                as TelephonyManager

        phoneStateListener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                when (state) {
                    TelephonyManager.CALL_STATE_RINGING -> {
                        val number = phoneNumber ?: ""
                        val name = resolveCallerName(number) ?: number
                        val intent = Intent(
                            this@CallMonitorService,
                            MainActivity::class.java
                        ).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                            putExtra(EXTRA_INCOMING_CALL, true)
                            putExtra(EXTRA_CALLER_NAME, name)
                        }
                        startActivity(intent)
                    }
                    TelephonyManager.CALL_STATE_IDLE -> {
                        sendBroadcast(Intent(ACTION_CALL_ENDED))
                    }
                    TelephonyManager.CALL_STATE_OFFHOOK -> {
                        // Call in progress
                    }
                }
            }
        }

        telephonyManager?.listen(
            phoneStateListener,
            PhoneStateListener.LISTEN_CALL_STATE
        )
    }

    // ─── Resolve Caller Name ──────────────────────
    private fun resolveCallerName(number: String): String? {
        if (number.isEmpty()) return null
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val cursor = contentResolver.query(
            uri, projection, null, null, null
        )
        cursor?.use {
            while (it.moveToNext()) {
                val name = it.getString(0) ?: continue
                val contactNumber = it.getString(1)
                    ?.replace(" ", "")
                    ?.replace("-", "") ?: continue
                val cleanInput = number
                    .replace(" ", "")
                    .replace("-", "")
                if (contactNumber.endsWith(cleanInput.takeLast(7)) ||
                    cleanInput.endsWith(contactNumber.takeLast(7))) {
                    return name
                }
            }
        }
        return null
    }

    // ─── Notification ─────────────────────────────
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "MYRA Call Monitor",
            NotificationManager.IMPORTANCE_LOW
        )
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MYRA Call Monitor")
            .setContentText("Incoming call detection active")
            .setSmallIcon(R.drawable.ic_myra_notif)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
