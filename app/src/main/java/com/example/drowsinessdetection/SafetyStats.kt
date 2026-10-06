package com.example.drowsinessdetection

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SafetySession(
    val startedAt: Long,
    val endedAt: Long,
    val drowsinessEvents: Int,
    val yawns: Int,
    val criticalAlerts: Int,
    val eyesClosedMillis: Long
)

data class SafetySummary(
    val drowsinessEvents: Int = 0,
    val yawns: Int = 0,
    val criticalAlerts: Int = 0,
    val eyesClosedMillis: Long = 0,
    val drivingMillis: Long = 0
) {
    val averageAlertSeconds: Double
        get() = if (drowsinessEvents == 0) 0.0 else eyesClosedMillis / 1000.0 / drowsinessEvents

    val safetyScore: Int
        get() = (100 - drowsinessEvents * 6 - yawns * 2 - criticalAlerts * 15 - (eyesClosedMillis / 5000).toInt())
            .coerceIn(0, 100)

    val riskLevel: String
        get() = when {
            safetyScore >= 85 -> "LOW"
            safetyScore >= 60 -> "MODERATE"
            else -> "HIGH"
        }
}

object SafetyStats {
    private const val PREFS_NAME = "DrowsinessSafetyStats"
    private const val SESSIONS_KEY = "sessions"
    private const val MAX_SAVED_SESSIONS = 30

    fun saveSession(context: Context, session: SafetySession) {
        val sessions = getSessions(context).toMutableList()
        sessions.add(0, session)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(SESSIONS_KEY, Gson().toJson(sessions.take(MAX_SAVED_SESSIONS)))
            .apply()
    }

    fun getToday(context: Context): SafetySummary {
        val today = dateKey(System.currentTimeMillis())
        val sessions = getSessions(context).filter { dateKey(it.startedAt) == today }
        return SafetySummary(
            drowsinessEvents = sessions.sumOf { it.drowsinessEvents },
            yawns = sessions.sumOf { it.yawns },
            criticalAlerts = sessions.sumOf { it.criticalAlerts },
            eyesClosedMillis = sessions.sumOf { it.eyesClosedMillis },
            drivingMillis = sessions.sumOf { (it.endedAt - it.startedAt).coerceAtLeast(0) }
        )
    }

    fun getMostRecent(context: Context): SafetySession? = getSessions(context).firstOrNull()

    fun summaryFor(session: SafetySession): SafetySummary = SafetySummary(
        drowsinessEvents = session.drowsinessEvents,
        yawns = session.yawns,
        criticalAlerts = session.criticalAlerts,
        eyesClosedMillis = session.eyesClosedMillis,
        drivingMillis = (session.endedAt - session.startedAt).coerceAtLeast(0)
    )

    private fun getSessions(context: Context): List<SafetySession> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(SESSIONS_KEY, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<SafetySession>>() {}.type
            Gson().fromJson<List<SafetySession>>(raw, type).orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun dateKey(time: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(time))
}
