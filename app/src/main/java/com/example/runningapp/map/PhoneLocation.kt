package com.example.runningapp.map

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.runningapp.analysis.MapFix
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Where the phone is, asked once, for the offline map's centre (#42).
 *
 * Not the Run's [com.example.runningapp.LocationTracker], which is a stream of fixes for recording a
 * Run. This is one answer, for a tap in Settings, with no Run in it.
 *
 * Any fix up to five minutes old is taken, and no accuracy bar is set. The area reaches 15 km in every
 * direction, so a fix a few hundred metres out still saves the ground the runner will run on; the
 * 30 m bar a Run's distance needs would only turn a good-enough answer into "couldn't find you".
 *
 * Null when there is no answer — no permission, location switched off, or no fix inside thirty
 * seconds. Never throws.
 */
class PhoneLocation(private val context: Context) {

    suspend fun whereAmI(): MapFix? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) return null
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMaxUpdateAgeMillis(FIX_MAX_AGE_MILLIS)
            .setDurationMillis(WAIT_MILLIS)
            .build()
        val cancellation = CancellationTokenSource()
        return suspendCancellableCoroutine { done ->
            done.invokeOnCancellation { cancellation.cancel() }
            try {
                LocationServices.getFusedLocationProviderClient(context)
                    .getCurrentLocation(request, cancellation.token)
                    .addOnSuccessListener { fix -> done.resume(fix?.let { MapFix(it.latitude, it.longitude) }) }
                    .addOnFailureListener { error ->
                        Log.w(TAG, "No fix for the offline map", error)
                        done.resume(null)
                    }
                    .addOnCanceledListener { done.resume(null) }
            } catch (e: SecurityException) {
                // Permission taken away between the check above and the ask.
                done.resume(null)
            }
        }
    }

    private companion object {
        const val TAG = "OfflineMap"
        const val FIX_MAX_AGE_MILLIS = 5 * 60_000L
        const val WAIT_MILLIS = 30_000L
    }
}
