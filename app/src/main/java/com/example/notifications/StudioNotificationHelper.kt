package com.example.notifications

import android.Manifest
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R

object StudioNotificationHelper {

    private const val CHANNEL_ID = "agent_completion_channel"
    private const val DOWNLOADS_CHANNEL_ID = "downloads_channel"
    private const val NOTIFICATION_ID_AGENT_DONE = 4102
    private const val NOTIFICATION_ID_DOWNLOAD_BASE = 4200

    fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = context.getString(R.string.notification_channel_description)
                    enableVibration(true)
                    enableLights(true)
                }
                manager.createNotificationChannel(channel)
            }
            if (manager.getNotificationChannel(DOWNLOADS_CHANNEL_ID) == null) {
                val downloadsChannel = NotificationChannel(
                    DOWNLOADS_CHANNEL_ID,
                    context.getString(R.string.notification_downloads_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = context.getString(R.string.notification_downloads_channel_description)
                }
                manager.createNotificationChannel(downloadsChannel)
            }
        }
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }

    fun showAgentFinishedNotification(
        context: Context,
        durationSeconds: Int,
        preview: String? = null
    ) {
        if (!hasNotificationPermission(context)) return
        ensureNotificationChannel(context)

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = context.getString(R.string.notification_agent_done_title)
        val cleanPreview = preview?.trim()?.takeIf { it.isNotBlank() }
        val body = when {
            cleanPreview != null && durationSeconds > 0 ->
                context.getString(R.string.notification_agent_done_body_with_time, durationSeconds) +
                    "\n$cleanPreview"
            durationSeconds > 0 ->
                context.getString(R.string.notification_agent_done_body_with_time, durationSeconds)
            else ->
                context.getString(R.string.notification_agent_done_body)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_spark)
            .setContentTitle(title)
            .setContentText(body.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_AGENT_DONE, notification)
    }

    fun showDownloadCompletedNotification(
        context: Context,
        fileName: String,
        contentUri: Uri?,
        mimeType: String?
    ) {
        if (!hasNotificationPermission(context)) return
        ensureNotificationChannel(context)

        val viewIntent = if (contentUri != null) {
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, mimeType ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        val fallbackIntent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val resolvedIntent = if (viewIntent.resolveActivity(context.packageManager) != null) {
            viewIntent
        } else {
            fallbackIntent
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            fileName.hashCode(),
            resolvedIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, DOWNLOADS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_spark)
            .setContentTitle(context.getString(R.string.notification_download_done_title))
            .setContentText(fileName)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val notifId = NOTIFICATION_ID_DOWNLOAD_BASE + (fileName.hashCode() and 0xFF)
        NotificationManagerCompat.from(context).notify(notifId, notification)
    }
}
