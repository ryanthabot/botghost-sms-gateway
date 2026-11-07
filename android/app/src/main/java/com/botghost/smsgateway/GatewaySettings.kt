package com.botghost.smsgateway

import android.content.Context
import android.content.SharedPreferences

object GatewayServer {
    /**
     * Where users deploy their OWN free relay (one click, their Cloudflare
     * account). No shared relay is bundled: every gateway runs on the
     * deployer's own worker, keeping traffic and quota separate.
     */
    const val DEPLOY_URL =
        "https://deploy.workers.cloudflare.com/?url=https://github.com/ryanthabot/botghost-sms-gateway"
}

data class GatewaySettings(
    val serverUrl: String = "",
    val deviceId: String = "",
    val apiKey: String = "",
    val gatewayName: String = "",
    val pollSeconds: Int = 5,
    val maxPerMinute: Int = 10,
    val dryRun: Boolean = false,
    val autoStartOnBoot: Boolean = true,
    val keepAwake: Boolean = false,
) {
    val normalizedUrl: String
        get() {
            val t = serverUrl.trim()
            return (if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t").trimEnd('/')
        }

    /** Full webhook endpoint shown to the user for BotGhost. */
    val endpoint: String get() = "${normalizedUrl}/g/$deviceId/outbox"

    fun isConfigured(): Boolean = serverUrl.isNotBlank() && deviceId.isNotBlank() && apiKey.isNotBlank()

    companion object {
        private const val PREFS = "gateway_settings"

        fun load(context: Context): GatewaySettings {
            val p: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return GatewaySettings(
                serverUrl = p.getString("server_url", "") ?: "",
                deviceId = p.getString("device_id", "") ?: "",
                apiKey = p.getString("api_key", "") ?: "",
                gatewayName = p.getString("gateway_name", "") ?: "",
                pollSeconds = p.getInt("poll_seconds", 5).coerceIn(3, 300),
                maxPerMinute = p.getInt("max_per_minute", 10).coerceIn(1, 60),
                dryRun = p.getBoolean("dry_run", false),
                autoStartOnBoot = p.getBoolean("auto_start_on_boot", true),
                keepAwake = p.getBoolean("keep_awake", false),
            )
        }

        fun save(context: Context, s: GatewaySettings) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("server_url", s.serverUrl.trim())
                .putString("device_id", s.deviceId.trim())
                .putString("api_key", s.apiKey.trim())
                .putString("gateway_name", s.gatewayName.trim())
                .putInt("poll_seconds", s.pollSeconds.coerceIn(3, 300))
                .putInt("max_per_minute", s.maxPerMinute.coerceIn(1, 60))
                .putBoolean("dry_run", s.dryRun)
                .putBoolean("auto_start_on_boot", s.autoStartOnBoot)
                .putBoolean("keep_awake", s.keepAwake)
                .apply()
        }

        fun clear(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().clear().apply()
        }
    }
}
