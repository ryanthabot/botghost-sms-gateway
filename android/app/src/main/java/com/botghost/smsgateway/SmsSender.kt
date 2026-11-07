package com.botghost.smsgateway

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.telephony.SmsManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class SmsSender(private val context: Context) {

    sealed class Result {
        data object Sent : Result()
        data class Failed(val reason: String) : Result()
    }

    companion object {
        private const val ACTION = "com.botghost.smsgateway.SMS_SENT"
        private const val EXTRA_IDX = "part_index"
        private const val CONFIRM_TIMEOUT_MS = 25_000L
    }

    /**
     * Sends [message] to [to] from the default SIM and waits for the radio's
     * confirmation for every part. Returns [Result.Sent] only when all parts
     * report RESULT_OK.
     */
    suspend fun send(to: String, message: String): Result {
        val outcome = withTimeoutOrNull(CONFIRM_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                @Suppress("DEPRECATION")
                val sm = SmsManager.getDefault()
                val parts = sm.divideMessage(message)
                val partResults = IntArray(parts.size) { Int.MIN_VALUE }
                var settled = false

                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, intent: Intent?) {
                        val idx = intent?.getIntExtra(EXTRA_IDX, -1) ?: -1
                        if (idx in partResults.indices) partResults[idx] = resultCode
                        if (settled || partResults.any { it == Int.MIN_VALUE }) return
                        settled = true
                        runCatching { context.unregisterReceiver(this) }
                        val failure = partResults.firstOrNull { it != -1 } // -1 = RESULT_OK
                        if (failure == null) {
                            cont.resume(Result.Sent)
                        } else {
                            cont.resume(Result.Failed(describe(failure)))
                        }
                    }
                }
                val filter = IntentFilter(ACTION)
                if (Build.VERSION.SDK_INT >= 33) {
                    context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    context.registerReceiver(receiver, filter)
                }
                cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }

                val piFlags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val baseRequest = REQUEST_CODES.getAndIncrement()
                val sentIntents = ArrayList<PendingIntent>(parts.size)
                for (i in parts.indices) {
                    val intent = Intent(ACTION).setPackage(context.packageName).putExtra(EXTRA_IDX, i)
                    sentIntents.add(PendingIntent.getBroadcast(context, baseRequest + i, intent, piFlags))
                }
                try {
                    if (parts.size == 1) {
                        sm.sendTextMessage(to, null, message, sentIntents[0], null)
                    } else {
                        sm.sendMultipartTextMessage(to, null, parts, sentIntents, null)
                    }
                } catch (e: Exception) {
                    if (!settled) {
                        settled = true
                        runCatching { context.unregisterReceiver(receiver) }
                        cont.resume(Result.Failed("send error: ${e.message ?: e.javaClass.simpleName}"))
                    }
                }
            }
        }
        return outcome ?: Result.Failed("no send confirmation within ${CONFIRM_TIMEOUT_MS / 1000}s")
    }

    private fun describe(code: Int): String = when (code) {
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "generic failure"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "radio off"
        SmsManager.RESULT_ERROR_NULL_PDU -> "null PDU"
        SmsManager.RESULT_ERROR_NO_SERVICE -> "no service"
        else -> "error code $code"
    }
}

/** Unique PendingIntent request codes so consecutive sends don't collide. */
private val REQUEST_CODES = java.util.concurrent.atomic.AtomicInteger((System.currentTimeMillis() % 1_000_000L).toInt())
