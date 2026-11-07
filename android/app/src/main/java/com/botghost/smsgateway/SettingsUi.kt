package com.botghost.smsgateway

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AdvancedCard(
    settings: GatewaySettings,
    onSave: (GatewaySettings) -> Unit,
    onRotated: (GatewaySettings) -> Unit,
    onUnregistered: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf(settings.serverUrl) }
    var poll by remember { mutableStateOf(settings.pollSeconds.toString()) }
    var rate by remember { mutableStateOf(settings.maxPerMinute.toString()) }
    var dryRun by remember { mutableStateOf(settings.dryRun) }
    var boot by remember { mutableStateOf(settings.autoStartOnBoot) }
    var awake by remember { mutableStateOf(settings.keepAwake) }
    var busy by remember { mutableStateOf(false) }
    var confirmUnregister by remember { mutableStateOf(false) }

    Card {
        Column(Modifier.padding(16.dp), Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { open = !open }) { Text(if (open) "Hide advanced settings" else "Advanced settings") }
            if (!open) return@Column

            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Relay server URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = poll,
                    onValueChange = { poll = it.filter(Char::isDigit).take(3) },
                    label = { Text("Poll seconds") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = rate,
                    onValueChange = { rate = it.filter(Char::isDigit).take(2) },
                    label = { Text("Max per min") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
            SwitchRow("Dry-run (no real SMS sent)", dryRun) { dryRun = it }
            SwitchRow("Auto-start on boot", boot) { boot = it }
            SwitchRow("Keep device awake (battery cost)", awake) { awake = it }
            Button(onClick = {
                onSave(
                    settings.copy(
                        serverUrl = url,
                        pollSeconds = poll.toIntOrNull() ?: 5,
                        maxPerMinute = rate.toIntOrNull() ?: 10,
                        dryRun = dryRun,
                        autoStartOnBoot = boot,
                        keepAwake = awake,
                    )
                )
                Toast.makeText(ctx, "Saved", Toast.LENGTH_SHORT).show()
            }) { Text("Save settings") }

            OutlinedButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        val newKey = GatewayApi.generateApiKey()
                        try {
                            withContext(Dispatchers.IO) {
                                GatewayApi(settings.serverUrl, settings.deviceId, settings.apiKey).rotateKey(newKey)
                            }
                            onRotated(settings.copy(apiKey = newKey))
                            Toast.makeText(ctx, "API key rotated — update BotGhost with the new key", Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            Toast.makeText(ctx, "Rotate failed: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                        busy = false
                    }
                },
            ) { Text(if (busy) "…" else "Reset API key") }

            OutlinedButton(onClick = {
                if (!confirmUnregister) {
                    confirmUnregister = true
                    Toast.makeText(ctx, "Tap again to delete this gateway and its webhook", Toast.LENGTH_LONG).show()
                } else {
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                GatewayApi(settings.serverUrl, settings.deviceId, settings.apiKey).destroy()
                            }
                        }
                        onUnregistered()
                    }
                }
            }) { Text(if (confirmUnregister) "Really delete gateway?" else "Delete this gateway") }
        }
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun LogCard() {
    val ctx = LocalContext.current
    Card {
        Column(Modifier.padding(16.dp), Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Log", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { EventLog.clear(ctx) }) { Text("Clear") }
            }
            if (EventLog.entries.isEmpty()) {
                Text("No events yet.", color = MaterialTheme.colorScheme.outline)
            } else {
                EventLog.entries.take(100).forEach { e ->
                    Text(
                        "${e.timeText()}  ${e.kind.uppercase()}  ${e.to ?: ""}  ${e.detail}",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = when (e.kind) {
                            "sent" -> MaterialTheme.colorScheme.primary
                            "failed" -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }
}
