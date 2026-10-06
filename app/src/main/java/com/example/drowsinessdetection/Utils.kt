package com.example.drowsinessdetection

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.Intent
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.os.Handler
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object Utils {
    private const val PREFS_NAME = "DrowsinessDetectionPrefs"
    private const val CONTACTS_KEY = "emergency_contacts"
    private const val DRIVER_NAME_KEY = "driver_name"

    private fun getSharedPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun saveContacts(context: Context, contacts: List<Contact>) {
        val prefs = getSharedPreferences(context)
        val gson = Gson()
        val json = gson.toJson(contacts)
        prefs.edit().putString(CONTACTS_KEY, json).apply()
    }

    fun getContacts(context: Context): List<Contact> {
        val prefs = getSharedPreferences(context)
        val gson = Gson()
        val json = prefs.getString(CONTACTS_KEY, null) ?: return emptyList()

        val type = object : TypeToken<List<Contact>>() {}.type
        return try {
            gson.fromJson(json, type)
        } catch (e: Exception) {
            Log.e("Utils", "Error parsing contacts", e)
            emptyList()
        }
    }

    fun saveDriverName(context: Context, name: String) {
        getSharedPreferences(context).edit().putString(DRIVER_NAME_KEY, name.trim()).apply()
    }

    fun getDriverName(context: Context): String =
        getSharedPreferences(context).getString(DRIVER_NAME_KEY, "Driver")
            ?.ifBlank { "Driver" }
            ?: "Driver"

    fun sendSMS(context: Context, phoneNumber: String, message: String, requestCodeBase: Int) {
        try {
            smsReadinessProblem(context)?.let { throw IllegalStateException(it) }

            val defaultSubscriptionId = SubscriptionManager.getDefaultSmsSubscriptionId()
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
                    .createForSubscriptionId(defaultSubscriptionId)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getSmsManagerForSubscriptionId(defaultSubscriptionId)
            }

            val messageParts = smsManager.divideMessage(message)
            val sentIntents = messageParts.mapIndexed { index, _ ->
                val statusIntent = Intent(context, SmsSentReceiver::class.java).apply {
                    action = SmsSentReceiver.ACTION_SMS_SENT
                    putExtra(SmsSentReceiver.EXTRA_PHONE_NUMBER, phoneNumber)
                }
                PendingIntent.getBroadcast(
                    context,
                    requestCodeBase + index,
                    statusIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }

            smsManager.sendMultipartTextMessage(
                phoneNumber,
                null,
                messageParts,
                ArrayList(sentIntents),
                null
            )

            Log.d("Utils", "SMS send request submitted to Android for $phoneNumber")

        } catch (e: Exception) {
            Log.e("Utils", "Failed to send SMS to $phoneNumber", e)
            throw e
        }
    }

    fun smsReadinessProblem(context: Context): String? {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING)) {
            return "This device cannot send SMS. A phone with SMS service is required."
        }
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return "SMS permission is off. Allow SMS for DrowzeFree in Android settings."
        }
        return try {
            if (SubscriptionManager.getDefaultSmsSubscriptionId() == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                "No default SMS SIM is selected. In Android Settings, select a SIM for SMS messages."
            } else {
                null
            }
        } catch (_: Exception) {
            "The default SMS SIM could not be read. Check the SIM is active and selected for SMS."
        }
    }

    // Helper function to format timestamps in a user-friendly way
    fun formatTimestamp(timestamp: Long): String {
        val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(timestamp))
    }

    // Track the last known location
    private var lastKnownLocation: Location? = null

    // Location client for the whole application
    private var fusedLocationClient: FusedLocationProviderClient? = null

    // Initialize location services
    fun initLocationServices(context: Context) {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
        startLocationUpdates(context)

        // Get initial location
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient?.lastLocation?.addOnSuccessListener { location ->
                if (location != null) {
                    lastKnownLocation = location
                }
            }
        }
    }

    // Start receiving location updates
    private fun startLocationUpdates(context: Context) {
        // Check if we have permission
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e("Utils", "Location permissions not granted")
            return
        }

        // Location request settings
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000) // 5 seconds
            .setMinUpdateIntervalMillis(2000) // 2 seconds
            .setMaxUpdateDelayMillis(10000) // 10 seconds
            .setWaitForAccurateLocation(true)
            .build()

        // Location callback
        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    lastKnownLocation = location
                    Log.d("Utils", "Location updated: ${location.latitude}, ${location.longitude}")
                }
            }
        }

        // Request location updates
        try {
            fusedLocationClient?.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        } catch (e: Exception) {
            Log.e("Utils", "Error requesting location updates", e)
        }
    }

    // Get the current location as a formatted string for SOS messages
    private lateinit var context: Context

    fun initContext(appContext: Context) {
        context = appContext
        initLocationServices(context)
    }

    fun getLocationInfo(callback: (String) -> Unit) {
        if (!::context.isInitialized) {
            callback("Location information unavailable")
            return
        }

        val hasFine = ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) {
            callback("Location unavailable (permissions not granted)")
            return
        }
        val client = fusedLocationClient ?: LocationServices.getFusedLocationProviderClient(context)
        val completed = java.util.concurrent.atomic.AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())
        val finish: (Location?) -> Unit = { location ->
            if (completed.compareAndSet(false, true)) {
                if (location != null) {
                    lastKnownLocation = location
                    callback("Location: https://maps.google.com/maps?q=${location.latitude},${location.longitude}")
                } else {
                    callback("Location unavailable. Check device location settings and try again.")
                }
            }
        }
        handler.postDelayed({ finish(lastKnownLocation) }, 10000)
        try {
            val priority = if (hasFine) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
            client.getCurrentLocation(priority, CancellationTokenSource().token)
                .addOnSuccessListener { location -> finish(location ?: lastKnownLocation) }
                .addOnFailureListener {
                    client.lastLocation.addOnSuccessListener { cached -> finish(cached ?: lastKnownLocation) }
                        .addOnFailureListener { finish(lastKnownLocation) }
                }
        } catch (e: SecurityException) {
            finish(null)
        }
    }
}
