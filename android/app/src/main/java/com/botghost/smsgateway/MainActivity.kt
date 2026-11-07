package com.botghost.smsgateway

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EventLog.ensureLoaded(this)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    GatewayApp()
                }
            }
        }
    }
}

@Composable
fun GatewayApp() {
    val ctx = LocalContext.current
    var settings by remember { mutableStateOf(GatewaySettings.load(ctx)) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants -> if (grants.values.all { it }) GatewayService.start(ctx) }

    fun requestPermsAndStart(current: GatewaySettings) {
        val missing = buildList {
            if (!current.dryRun) add(Manifest.permission.SEND_SMS)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) GatewayService.start(ctx) else permLauncher.launch(missing.toTypedArray())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (!settings.isConfigured()) {
            WelcomeCard(
                serverUrl = settings.serverUrl,
                onReady = { registered ->
                    settings = registered
                    requestPermsAndStart(registered)
                },
            )
        } else {
            StatusCard(
                settings = settings,
                onStart = { requestPermsAndStart(settings) },
                onStop = { GatewayService.stop(ctx) },
            )
            CredentialsCard(settings = settings)
            TestCard(settings = settings)
            AdvancedCard(
                settings = settings,
                onSave = { settings = it; GatewaySettings.save(ctx, it) },
                onRotated = { settings = it; GatewaySettings.save(ctx, it) },
                onUnregistered = {
                    GatewayService.stop(ctx)
                    GatewaySettings.clear(ctx)
                    settings = GatewaySettings.load(ctx)
                },
            )
            LogCard()
        }
    }
}

@Composable
fun StatusCard(settings: GatewaySettings, onStart: () -> Unit, onStop: () -> Unit) {
    val running = GatewayRuntime.running.value
    val lastPoll = GatewayRuntime.lastPoll.value
    val lastError = GatewayRuntime.lastError.value

    Card {
        Column(Modifier.padding(16.dp), Arrangement.spacedBy(8.dp)) {
            Text("BotGhost SMS Gateway", style = MaterialTheme.typography.titleLarge)
            Text(
                if (running) "Running" else "Stopped",
                color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.titleMedium,
            )
            if (settings.gatewayName.isNotBlank()) Text("Gateway: ${settings.gatewayName}")
            Text("Device: ${settings.deviceId}")
            if (lastPoll != null) Text("Last check: $lastPoll")
            if (lastError != null) Text("Last error: $lastError", color = MaterialTheme.colorScheme.error)
            Text("Sent: ${GatewayRuntime.sentCount.value}   Failed: ${GatewayRuntime.failedCount.value}")
            Button(
                onClick = { if (running) onStop() else onStart() },
                colors = if (running) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) { Text(if (running) "Stop gateway" else "Start gateway") }
        }
    }
}
