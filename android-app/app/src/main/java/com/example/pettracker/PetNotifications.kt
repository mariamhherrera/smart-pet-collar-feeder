package com.example.pettracker

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * COM-6.1: phone notifications. One place for every alert (safe zone, low battery, offline,
 * failed meal), so they all look the same and open the app when tapped.
 */
object PetNotifications {

    private const val CHANNEL_ID = "pet_alerts"
    private const val PERMISSION_REQUEST = 7001

    /** One id per kind of alert: a new alert of the same kind replaces the old one. */
    const val ID_SAFE_ZONE = 1001

    /** Android 13 and newer ask the owner once. Called from Home, the first page after login. */
    fun askPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= 33 && !allowed(activity)) {
            activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), PERMISSION_REQUEST)
        }
    }

    fun allowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // checked just above with allowed()
    fun show(context: Context, id: Int, title: String, text: String) {
        if (!allowed(context)) return
        createChannel(context)

        // Tapping the notification opens Home
        val openApp = PendingIntent.getActivity(
            context, id,
            Intent(context, HomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_pets)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(id, notification)
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val channel = NotificationChannel(CHANNEL_ID, "Pet alerts", NotificationManager.IMPORTANCE_HIGH)
        channel.description = "Safe zone, battery and feeder alerts"
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
