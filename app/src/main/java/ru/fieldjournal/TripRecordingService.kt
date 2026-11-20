package ru.fieldjournal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** Used by the activity and service on the main thread in the same process. */
internal object TripRecording {
    var tripId: String? = null
        private set
    private val observers = mutableSetOf<(String?) -> Unit>()

    fun observe(observer: (String?) -> Unit) { observers.add(observer) }
    fun forget(observer: (String?) -> Unit) { observers.remove(observer) }

    fun publish(id: String?, problem: String? = null) {
        tripId = id
        observers.toList().forEach { it(problem) }
    }
}

class TripRecordingService : Service() {
    private lateinit var location: FieldLocation
    private lateinit var trips: FieldTrips
    private var tripId: String? = null
    private var segment = 0
    private var lastFix: LocationFix? = null

    override fun onCreate() {
        super.onCreate()
        trips = JournalStore.get(this).trips
        location = FieldLocation(this, ::record)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(EXTRA_TRIP)
        if (intent?.action == STOP || id == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (tripId == id) return START_NOT_STICKY
        try {
            val notification = makeNotification()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            check(trips.active()?.id == id) { "Этот выезд уже завершён" }
            tripId = id
            segment = trips.nextSegment(id)
            lastFix = null
            check(location.start(useCachedFix = false, intervalMs = 5000, distanceMeters = 3f)) {
                "Включите геолокацию и проверьте разрешения для записи трека"
            }
            TripRecording.publish(id)
        } catch (e: Exception) {
            android.util.Log.w("FieldJournal", "Could not start trip recording", e)
            fail("Запись трека не запущена. Проверьте геолокацию и разрешения.")
        }
        return START_NOT_STICKY
    }

    private fun record(fix: LocationFix) {
        val id = tripId ?: return
        val previous = lastFix
        if (previous != null) {
            if (fix.elapsedMs <= previous.elapsedMs || fix.point == previous.point) return
            if (fix.elapsedMs - previous.elapsedMs > 120_000 || fix.measuredAt <= previous.measuredAt) segment++
        }
        try {
            if (trips.append(id, TrackPoint(fix.point, fix.measuredAt, fix.accuracy, fix.altitude, segment))) {
                lastFix = fix
                TripRecording.publish(id)
            } else if (trips.active()?.id != id) stopSelf()
        } catch (e: RuntimeException) {
            android.util.Log.w("FieldJournal", "Could not store a track point", e)
            fail("Запись трека остановлена. Не удалось сохранить точку.")
        }
    }

    private fun fail(message: String) {
        tripId = null
        TripRecording.publish(null, message)
        stopSelf()
    }

    override fun onDestroy() {
        runCatching(location::stop)
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (tripId != null) TripRecording.publish(null)
        super.onDestroy()
    }

    private fun makeNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Запись маршрута", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val pause = PendingIntent.getService(this, 1, Intent(this, TripRecordingService::class.java).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_journal)
            .setContentTitle("Полевой дневник")
            .setContentText("Записывается маршрут выезда")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Пауза", pause).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "field-trip-track"
        private const val NOTIFICATION_ID = 51
        private const val EXTRA_TRIP = "trip_id"
        private const val STOP = "ru.fieldjournal.PAUSE_TRACK"

        internal fun start(context: Context, tripId: String) {
            context.startForegroundService(Intent(context, TripRecordingService::class.java).putExtra(EXTRA_TRIP, tripId))
        }

        internal fun pause(context: Context) {
            context.stopService(Intent(context, TripRecordingService::class.java))
        }
    }
}
