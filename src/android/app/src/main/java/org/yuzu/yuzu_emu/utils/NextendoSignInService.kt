// SPDX-FileCopyrightText: 2026 DEN
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.yuzu.yuzu_emu.NativeLibrary
import org.yuzu.yuzu_emu.R

// Keeps the process alive while the browser sign-in is pending, so Android doesn't freeze the
// local callback server.
class NextendoSignInService : Service() {
    companion object {
        private const val NOTIFICATION_ID = 0x4601

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, NextendoSignInService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NextendoSignInService::class.java))
        }
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification =
            NotificationCompat.Builder(this, getString(R.string.app_notification_channel_id))
                .setSmallIcon(R.drawable.ic_stat_notification_logo)
                .setContentTitle("Connexion à Nextendo")
                .setContentText("Termine la connexion dans ton navigateur")
                .setOngoing(true)
                .build()
        startForeground(NOTIFICATION_ID, notification)
        NativeLibrary.nextendoSignIn()
        return START_NOT_STICKY
    }
}
