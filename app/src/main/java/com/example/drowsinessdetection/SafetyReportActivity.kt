package com.example.drowsinessdetection

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SafetyReportActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_safety_report)

        val latestSession = SafetyStats.getMostRecent(this)
        val summary = latestSession?.let(SafetyStats::summaryFor) ?: SafetySummary()
        val scope = if (latestSession == null) {
            "No completed driving session saved yet"
        } else {
            "Completed ${SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(latestSession.endedAt))}"
        }
        findViewById<TextView>(R.id.report_scope).text = scope
        findViewById<TextView>(R.id.stats_title).text = "LATEST COMPLETED SESSION"
        findViewById<TextView>(R.id.score_value).text = "${summary.safetyScore}/100"
        findViewById<TextView>(R.id.risk_value).text = summary.riskLevel
        findViewById<TextView>(R.id.drowsiness_value).text = summary.drowsinessEvents.toString()
        findViewById<TextView>(R.id.yawns_value).text = summary.yawns.toString()
        findViewById<TextView>(R.id.critical_value).text = summary.criticalAlerts.toString()
        findViewById<TextView>(R.id.average_alert_value).text =
            String.format(Locale.getDefault(), "%.1f sec", summary.averageAlertSeconds)
        findViewById<TextView>(R.id.drive_duration_value).text = formatDuration(summary.drivingMillis)

        val color = when (summary.riskLevel) {
            "LOW" -> R.color.success_green
            "MODERATE" -> R.color.warning_yellow
            else -> R.color.error_red
        }
        findViewById<TextView>(R.id.risk_value).setTextColor(ContextCompat.getColor(this, color))
    }

    private fun formatDuration(durationMs: Long): String {
        val seconds = durationMs / 1000
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60)
    }
}
