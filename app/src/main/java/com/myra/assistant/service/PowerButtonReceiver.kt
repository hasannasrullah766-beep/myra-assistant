package com.myra.assistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class PowerButtonReceiver : BroadcastReceiver() {

    companion object {
        private var lastScreenOff = 0L
        private var lastScreenOn = 0L
        private const val DOUBLE_PRESS_DELAY = 600L
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SCREEN_OFF -> {
                val now = System.currentTimeMillis()
                if (now - lastScreenOff < DOUBLE_PRESS_DELAY) {
                    showOverlay(context)
                }
                lastScreenOff = now
            }
            Intent.ACTION_SCREEN_ON -> {
                val now = System.currentTimeMillis()
                if (now - lastScreenOn < DOUBLE_PRESS_DELAY) {
                    showOverlay(context)
                }
                lastScreenOn = now
            }
        }
    }

    private fun showOverlay(context: Context) {
        val intent = Intent(context, MyraOverlayService::class.java).apply {
            action = MyraOverlayService.ACTION_SHOW_OVERLAY
        }
        context.startForegroundService(intent)
    }
}
