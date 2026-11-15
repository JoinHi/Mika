package com.wangzan101.mika.personal.live

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps Mika Live voice conversation alive across app switches and screen lock
 * using Android's microphone foreground service type.
 */
class PersonalLiveService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as MikaApp
        when (intent?.action) {
            ACTION_TOGGLE_MUTE -> {
                app.personal.live.toggleMute()
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                app.personal.live.stop()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val liveState = app.personal.live.uiState.value
        if (!liveState.phase.keepServiceAlive) {
            stopSelf()
            return START_NOT_STICKY
        }

        ensureChannel(this)
        val notification = buildNotification(this, liveState)
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } catch (t: Throwable) {
            app.personal.live.onForegroundServiceFailed(t)
            stopSelf()
            return START_NOT_STICKY
        }

        if (!observing) {
            observing = true
            scope.launch {
                app.personal.live.uiState.collectLatest { state ->
                    if (!state.phase.keepServiceAlive) {
                        ServiceCompat.stopForeground(this@PersonalLiveService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        val nm = getSystemService(NotificationManager::class.java)
                        nm?.notify(NOTIFICATION_ID, buildNotification(this@PersonalLiveService, state))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "mika_personal_live"
        private const val NOTIFICATION_ID = 4108
        const val ACTION_START = "com.wangzan101.mika.personal.live.START"
        const val ACTION_TOGGLE_MUTE = "com.wangzan101.mika.personal.live.TOGGLE_MUTE"
        const val ACTION_STOP = "com.wangzan101.mika.personal.live.STOP"

        fun startOrUpdate(context: Context) {
            val appContext = context.applicationContext
            ensureChannel(appContext)
            runCatching {
                appContext.startForegroundService(Intent(appContext, PersonalLiveService::class.java).setAction(ACTION_START))
            }.onFailure { t ->
                (appContext as? MikaApp)?.personal?.live?.onForegroundServiceFailed(t)
            }
        }

        fun stop(context: Context) {
            val appContext = context.applicationContext
            runCatching { appContext.stopService(Intent(appContext, PersonalLiveService::class.java)) }
        }

        private fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                tr(R.string.live_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = tr(R.string.live_channel_desc)
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }

        private fun buildNotification(context: Context, state: PersonalLiveUiState): Notification {
            val openIntent = PendingIntent.getActivity(
                context,
                4109,
                Intent(context, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_OPEN_CONVERSATION, "personal-main")
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val muteIntent = PendingIntent.getService(
                context,
                4110,
                Intent(context, PersonalLiveService::class.java).setAction(ACTION_TOGGLE_MUTE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val stopIntent = PendingIntent.getService(
                context,
                4111,
                Intent(context, PersonalLiveService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

            val subtitle = when (state.phase) {
                LiveSessionPhase.Connecting -> tr(R.string.live_status_connecting)
                LiveSessionPhase.Reconnecting -> tr(R.string.live_status_reconnecting)
                LiveSessionPhase.SuspendedByCall -> tr(R.string.live_status_suspended_call)
                LiveSessionPhase.Active -> when {
                    state.muted -> tr(R.string.live_status_muted)
                    state.speaking -> tr(R.string.live_status_speaking)
                    state.activeTaskCount > 0 -> tr(R.string.live_status_listening_tasks, state.activeTaskCount)
                    else -> tr(R.string.live_status_listening)
                }
                LiveSessionPhase.Idle, LiveSessionPhase.Error -> tr(R.string.live_status_listening)
            }
            val muteLabel = if (state.muted) tr(R.string.live_action_unmute) else tr(R.string.live_action_mute)

            return Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(tr(R.string.live_notification_title))
                .setContentText(subtitle)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_CALL)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(openIntent)
                .addAction(Notification.Action.Builder(null, muteLabel, muteIntent).build())
                .addAction(Notification.Action.Builder(null, tr(R.string.live_action_hangup), stopIntent).build())
                .build()
        }
    }
}
