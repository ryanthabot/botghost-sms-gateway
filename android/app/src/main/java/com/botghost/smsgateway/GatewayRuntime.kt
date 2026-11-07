package com.botghost.smsgateway

import androidx.compose.runtime.mutableStateOf

/** Observable runtime state shared between the foreground service and the UI. */
object GatewayRuntime {
    val running = mutableStateOf(false)
    val lastPoll = mutableStateOf<String?>(null)
    val lastError = mutableStateOf<String?>(null)
    val sentCount = mutableStateOf(0)
    val failedCount = mutableStateOf(0)

    fun resetCounters() {
        sentCount.value = 0
        failedCount.value = 0
    }
}
