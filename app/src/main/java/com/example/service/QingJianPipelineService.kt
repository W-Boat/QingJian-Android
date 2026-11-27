package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Android ForegroundService keeping long-running AI generation, review, and chapter handoff
 * tasks alive in the background with real-time progress notifications.
 */
class QingJianPipelineService : Service() {

  override fun onCreate() {
    super.onCreate()
    ensureChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_STOP -> {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        return START_NOT_STICKY
      }
      ACTION_PROGRESS, null -> {
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "青简 AI 流水线运行中"
        val body = intent?.getStringExtra(EXTRA_BODY) ?: "正在执行写作生成与复审任务"
        val progress = intent?.getIntExtra(EXTRA_PROGRESS, 0) ?: 0
        val notification = buildNotification(title, body, progress)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
          startForeground(NOTIFICATION_ID, notification)
        }
      }
    }
    return START_NOT_STICKY
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private fun ensureChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
        val channel = NotificationChannel(
          CHANNEL_ID,
          "青简 AI 长任务保活",
          NotificationManager.IMPORTANCE_LOW,
        ).apply {
          description = "用于长篇章节生成、自动复审与定稿交接任务后台保活与进度通知"
        }
        mgr.createNotificationChannel(channel)
      }
    }
  }

  private fun buildNotification(title: String, body: String, progress: Int): Notification {
    val builder = NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle(title)
      .setContentText(body)
      .setSmallIcon(android.R.drawable.ic_menu_edit)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
    if (progress in 1..99) {
      builder.setProgress(100, progress, false)
    } else {
      builder.setProgress(0, 0, true)
    }
    return builder.build()
  }

  companion object {
    private const val CHANNEL_ID = "qingjian_pipeline_channel"
    private const val NOTIFICATION_ID = 4091
    const val ACTION_PROGRESS = "com.example.qingjian.PIPELINE_PROGRESS"
    const val ACTION_STOP = "com.example.qingjian.PIPELINE_STOP"
    const val EXTRA_TITLE = "extra_title"
    const val EXTRA_BODY = "extra_body"
    const val EXTRA_PROGRESS = "extra_progress"

    fun updateProgress(context: Context, title: String, body: String, progress: Int) {
      runCatching {
        val intent = Intent(context, QingJianPipelineService::class.java).apply {
          action = ACTION_PROGRESS
          putExtra(EXTRA_TITLE, title)
          putExtra(EXTRA_BODY, body)
          putExtra(EXTRA_PROGRESS, progress)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          context.startForegroundService(intent)
        } else {
          context.startService(intent)
        }
      }
    }

    fun stop(context: Context) {
      runCatching {
        val intent = Intent(context, QingJianPipelineService::class.java).apply {
          action = ACTION_STOP
        }
        context.startService(intent)
      }
    }
  }
}
