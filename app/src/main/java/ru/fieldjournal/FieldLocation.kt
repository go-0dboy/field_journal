package ru.fieldjournal

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock

internal data class LocationFix(
    val point: Coordinate, val accuracy: Double, val altitude: Double?,
    val elapsedMs: Long, val measuredAt: Long = System.currentTimeMillis()
)

internal class FieldLocation(private val context: Context, private val onFix: (LocationFix) -> Unit) {
    private val manager = context.getSystemService(LocationManager::class.java)
    private var best: Location? = null
    private val watcher = object : LocationListener {
        override fun onLocationChanged(location: Location) = accept(location)
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
    }

    private fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun permitted() = granted(Manifest.permission.ACCESS_COARSE_LOCATION) || granted(Manifest.permission.ACCESS_FINE_LOCATION)

    @SuppressLint("MissingPermission")
    fun start(useCachedFix: Boolean = true, intervalMs: Long = 2000L, distanceMeters: Float = 1f): Boolean {
        if (!permitted()) return false
        stop()
        best = null
        val allowed = buildList {
            if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) add(LocationManager.GPS_PROVIDER)
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }.filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        var registered = false
        for (provider in allowed) {
            runCatching {
                manager.requestLocationUpdates(provider, intervalMs, distanceMeters, watcher, Looper.getMainLooper())
                registered = true
                if (useCachedFix) manager.getLastKnownLocation(provider)?.let(::accept)
            }
        }
        return registered
    }

    fun stop() {
        manager.removeUpdates(watcher)
    }

    private fun accept(location: Location) {
        val elapsed = location.elapsedRealtimeNanos / 1_000_000L
        val age = SystemClock.elapsedRealtime() - elapsed
        if (age !in 0..45_000 || !location.hasAccuracy() || !location.accuracy.isFinite() || location.accuracy <= 0) return
        val previous = best
        if (previous != null) {
            val gap = (location.elapsedRealtimeNanos - previous.elapsedRealtimeNanos) / 1_000_000L
            if (gap < -2_000 || (gap < 12_000 && location.accuracy > previous.accuracy * 1.25f)) return
        }
        best = location
        onFix(LocationFix(Coordinate(location.latitude, location.longitude), location.accuracy.toDouble(),
            location.altitude.takeIf { location.hasAltitude() && it.isFinite() }, elapsed,
            location.time.takeIf { it > 0 } ?: System.currentTimeMillis()))
    }
}
