package com.csjotlab.cardashboard.nav.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.csjotlab.cardashboard.CarDashboardApplication
import com.csjotlab.cardashboard.MainActivity
import com.csjotlab.cardashboard.R
import com.csjotlab.cardashboard.ui.navigation.NavigationFormatter
import com.csjotlab.cardashboard.ui.navigation.formatEtaTime
import com.csjotlab.cardashboard.vehicle.domain.valueOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps turn-by-turn guidance alive while the screen is off or another app is in front.
 *
 * Android throttles background location to a few fixes an hour; a foreground service of type
 * `location` is the supported way to keep receiving fixes during an active trip. It is started by
 * [com.csjotlab.cardashboard.di.NavigationContainer] only while guidance is active, always from the
 * foreground (the driver just pressed Start), so `ACCESS_BACKGROUND_LOCATION` is not needed. The
 * notification shows the next instruction and offers **End**.
 */
class NavigationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val container get() = (application as CarDashboardApplication).navigation
    private var holdingSensors = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification(title = "Navigating", text = "Starting guidance…"),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
            )
        } catch (t: Throwable) {
            // No location permission (revoked mid-trip) or started from the background: the
            // platform refuses; guidance continues while the app is visible.
            Log.w(TAG, "Cannot run guidance in the foreground: ${t.message}")
            stopSelf()
            return
        }
        container.acquireSensors()
        holdingSensors = true

        scope.launch {
            container.repository.snapshot
                .map { snapshot ->
                    val ui = NavigationFormatter.toUiState(snapshot, ::formatEtaTime)
                    val title = listOfNotNull(ui.maneuverDistanceText, ui.instructionText ?: ui.statusLabel).joinToString(" · ")
                    val eta = snapshot.state.etaMs?.valueOrNull()?.let { "Arrive ${formatEtaTime(it)}" }
                    val text = listOfNotNull(ui.rerouteText ?: ui.gpsText, eta, ui.remainingDistanceText).joinToString(" · ")
                    title to text
                }
                .distinctUntilChanged()
                .collect { (title, text) ->
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(title, text))
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_END) {
            container.endTrip()
            stopSelf()
        }
        // Not sticky: after a process death there is no trip to resume.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Stop updating first: an update posted after the service has gone would leave an ongoing
        // notification nothing can remove.
        scope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        if (holdingSensors) container.releaseSensors()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        // Default importance so the next turn shows on the lock screen (Pixel hides low-importance
        // notifications there) — but without sound or vibration: it updates every few seconds.
        val channel = NotificationChannel(CHANNEL_ID, "Navigation", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Turn-by-turn guidance while navigating"
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val manager = getSystemService(NotificationManager::class.java)
        // A channel's importance cannot be raised after creation; the first release used "navigation".
        manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        manager.createNotificationChannel(channel)
    }

    private fun notification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val end = PendingIntent.getService(
            this, 1,
            Intent(this, NavigationService::class.java).setAction(ACTION_END),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title.ifBlank { "Navigating" })
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, "End", end)
            .build()
    }

    companion object {
        private const val TAG = "CarDash/Nav"
        private const val CHANNEL_ID = "navigation_guidance"
        private const val LEGACY_CHANNEL_ID = "navigation"
        private const val NOTIFICATION_ID = 42
        const val ACTION_END = "com.csjotlab.cardashboard.nav.END"
    }
}
