package com.example.drowsinessdetection

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsManager
import android.util.Log

class SmsSentReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_SMS_SENT = "com.example.drowsinessdetection.SMS_SENT"
        const val ACTION_SMS_STATUS = "com.example.drowsinessdetection.SMS_STATUS"
        const val EXTRA_PHONE_NUMBER = "phone_number"
        const val EXTRA_STATUS_MESSAGE = "status_message"
        const val EXTRA_SUCCESS = "success"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val phoneNumber = intent.getStringExtra(EXTRA_PHONE_NUMBER).orEmpty()
        Log.i("SmsSentReceiver", "SMS result for $phoneNumber: $resultCode")
        val success = resultCode == Activity.RESULT_OK
        val message = if (success) {
            "SMS accepted by the mobile network for $phoneNumber"
        } else {
            "SMS failed for $phoneNumber: ${describeFailure(resultCode)}"
        }
        context.sendBroadcast(Intent(ACTION_SMS_STATUS).setPackage(context.packageName).apply {
            putExtra(EXTRA_SUCCESS, success)
            putExtra(EXTRA_STATUS_MESSAGE, message)
        })
    }

    private fun describeFailure(code: Int): String = when (code) {
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "generic carrier failure (check SMS balance, plan, SIM and signal)"
        SmsManager.RESULT_ERROR_NO_SERVICE -> "no mobile service"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "mobile radio is off"
        SmsManager.RESULT_ERROR_NULL_PDU -> "Android could not format the message"
        SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "SMS sending limit reached"
        SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE -> "SIM fixed-dialling restriction blocked this number"
        else -> describeModernFailure(code)
    }

    private fun describeModernFailure(code: Int): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return when (code) {
                SmsManager.RESULT_NETWORK_REJECT -> "the mobile network rejected the SMS"
                SmsManager.RESULT_RADIO_NOT_AVAILABLE -> "mobile radio is unavailable"
                SmsManager.RESULT_INVALID_ARGUMENTS -> "invalid SMS request"
                SmsManager.RESULT_INVALID_STATE -> "SIM is not ready for SMS"
                SmsManager.RESULT_MODEM_ERROR -> "phone modem error"
                SmsManager.RESULT_NETWORK_ERROR -> "mobile network error"
                SmsManager.RESULT_INVALID_SMSC_ADDRESS -> "carrier SMS-centre setting is invalid"
                SmsManager.RESULT_OPERATION_NOT_ALLOWED -> "carrier or SIM does not allow this SMS"
                SmsManager.RESULT_NO_DEFAULT_SMS_APP -> "no default SMS app is configured"
                else -> "unknown Android SMS result ($code)"
            }
        }
        return "unknown Android SMS result ($code)"
    }
}
