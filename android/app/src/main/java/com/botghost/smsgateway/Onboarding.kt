package com.botghost.smsgateway

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun WelcomeCard(serverUrl: String, onReady: (GatewaySettings) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var relayUrl by remember { mutableStateOf(serverUrl) }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Card {
        Column(Modifier.padding(16.dp), Arrangement.spacedBy(10.dp)) {
            Text("BotGhost SMS Gateway", style = MaterialTheme.typography.titleLarge)
            Text(
                "Turn this phone into an SMS gateway for your Discord bot. " +
                    "You'll deploy your own free relay (1 click on Cloudflare), then this app " +
                    "connects to it. Your traffic stays on your own account.",
            )

            Text("1. Deploy your relay (free, ~1 min)", style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = {
                runCatching {
                    ctx.startActivity(android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(GatewayServer.DEPLOY_URL)
                    ))
                }
            }) { Text("Open the deploy page →") }
            Text(
                "Sign in to Cloudflare (free) and click Deploy. When it finishes, " +
                    "copy your worker's URL (looks like https://botghost-sms-gateway.<your-name>.workers.dev).",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.outline,
            )

            Text("2. Paste your relay URL here", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = relayUrl,
                onValueChange = { relayUrl = it },
                label = { Text("https://…workers.dev") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text("3. Create your gateway", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(64) },
                label = { Text("Gateway name (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (busy) {
                CircularProgressIndicator(Modifier.padding(4.dp))
            } else {
                Button(
                    enabled = relayUrl.isNotBlank(),
                    onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            val apiKey = GatewayApi.generateApiKey()
                            val url = relayUrl.trim()
                            try {
                                val deviceId = withContext(Dispatchers.IO) {
                                    GatewayApi.register(url, apiKey, name)
                                }
                                val s = GatewaySettings(serverUrl = url, deviceId = deviceId, apiKey = apiKey, gatewayName = name)
                                GatewaySettings.save(ctx, s)
                                EventLog.add(ctx, "info", null, "gateway created: $deviceId")
                                onReady(s)
                            } catch (e: Exception) {
                                error = "Registration failed: ${e.message}"
                                busy = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Create my gateway") }
            }
            if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
fun CredentialsCard(settings: GatewaySettings) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var showKey by remember { mutableStateOf(false) }

    fun copy(text: String, label: String) {
        clipboard.setText(AnnotatedString(text))
        Toast.makeText(ctx, "$label copied", Toast.LENGTH_SHORT).show()
    }

    Card {
        Column(Modifier.padding(16.dp), Arrangement.spacedBy(10.dp)) {
            Text("Your BotGhost webhook", style = MaterialTheme.typography.titleMedium)
            Text("Paste these into a \"Send an API Request\" block in BotGhost:", color = MaterialTheme.colorScheme.outline)

            CredentialRow(
                label = "URL",
                value = settings.endpoint,
                mono = true,
                onCopy = { copy(settings.endpoint, "URL") },
            )
            CredentialRow(
                label = "Header",
                value = "X-Api-Key: ${if (showKey) settings.apiKey else "•".repeat(24)}",
                mono = true,
                onCopy = { copy(settings.apiKey, "API key") },
                extra = {
                    TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide" else "Show") }
                },
            )
            Text(
                "Body: {\"to\": \"+1555…\", \"code\": \"123456\"}  —  the phone number and code your bot generates.",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun CredentialRow(
    label: String,
    value: String,
    mono: Boolean,
    onCopy: () -> Unit,
    extra: (@Composable () -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                value,
                modifier = Modifier.weight(1f),
                fontSize = 12.sp,
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            OutlinedButton(onClick = onCopy) { Text("Copy") }
            extra?.invoke()
        }
    }
}

@Composable
fun TestCard(settings: GatewaySettings) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var number by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    Card {
        Column(Modifier.padding(16.dp), Arrangement.spacedBy(8.dp)) {
            Text("Test it", style = MaterialTheme.typography.titleMedium)
            Text(
                "Enter this phone's own number. The gateway will send itself a real SMS " +
                    "through the webhook — exactly what BotGhost will do.",
                color = MaterialTheme.colorScheme.outline,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = number,
                    onValueChange = { number = it },
                    label = { Text("This phone's number (+1555…)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    enabled = !busy && number.isNotBlank(),
                    onClick = {
                        busy = true
                        result = null
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    GatewayApi(settings.serverUrl, settings.deviceId, settings.apiKey)
                                        .sendTo(number.trim(), "BotGhost SMS Gateway works! Sent from your own phone.")
                                }
                                result = "Queued — the SMS should arrive in a few seconds."
                            } catch (e: Exception) {
                                result = "Failed: ${e.message}"
                            }
                            busy = false
                        }
                    },
                ) { Text(if (busy) "…" else "Send") }
            }
            if (result != null) Text(result!!, color = MaterialTheme.colorScheme.tertiary)
        }
    }
}
