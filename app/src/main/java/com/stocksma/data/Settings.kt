package com.stocksma.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject

val Context.dataStore by preferencesDataStore("settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val providerId: String = "stooq",
    val fetchesPerDay: Int = 1,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Re-arm cooldown for notifications, in hours. 0 = re-arm only when the price crosses back. */
    val cooldownHours: Int = 0,
    val custom: CustomConfig = CustomConfig()
)

object SettingsKeys {
    val PROVIDER = stringPreferencesKey("provider")
    val FETCHES = intPreferencesKey("fetchesPerDay")
    val THEME = stringPreferencesKey("theme")
    val COOLDOWN = intPreferencesKey("cooldownHours")
    val CUSTOM = stringPreferencesKey("customConfig")
}

class SettingsRepo(private val context: Context) {

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            providerId = p[SettingsKeys.PROVIDER] ?: "stooq",
            fetchesPerDay = p[SettingsKeys.FETCHES] ?: 1,
            themeMode = runCatching { ThemeMode.valueOf(p[SettingsKeys.THEME] ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM),
            cooldownHours = p[SettingsKeys.COOLDOWN] ?: 0,
            custom = p[SettingsKeys.CUSTOM]?.let { decodeCustom(it) } ?: CustomConfig()
        )
    }

    suspend fun update(block: (MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }

    /** API keys are stored encrypted, never in plain DataStore. */
    private val securePrefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "secure_keys",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun apiKey(providerId: String): String? = securePrefs.getString("key_$providerId", null)

    fun setApiKey(providerId: String, value: String) {
        securePrefs.edit().putString("key_$providerId", value).apply()
    }

    companion object {
        fun encodeCustom(c: CustomConfig): String = JSONObject().apply {
            put("name", c.name)
            put("historyUrl", c.historyUrl)
            put("apiKey", c.apiKey)
            put("format", c.format)
            put("csvDateCol", c.csvDateCol)
            put("csvCloseCol", c.csvCloseCol)
            put("csvSkipHeader", c.csvSkipHeader)
            put("jsonArrayPath", c.jsonArrayPath)
            put("jsonDateField", c.jsonDateField)
            put("jsonCloseField", c.jsonCloseField)
            put("dateFormat", c.dateFormat)
        }.toString()

        fun decodeCustom(s: String): CustomConfig = runCatching {
            val o = JSONObject(s)
            CustomConfig(
                name = o.optString("name", "Custom"),
                historyUrl = o.optString("historyUrl"),
                apiKey = o.optString("apiKey"),
                format = o.optString("format", "CSV"),
                csvDateCol = o.optInt("csvDateCol", 0),
                csvCloseCol = o.optInt("csvCloseCol", 4),
                csvSkipHeader = o.optBoolean("csvSkipHeader", true),
                jsonArrayPath = o.optString("jsonArrayPath"),
                jsonDateField = o.optString("jsonDateField", "date"),
                jsonCloseField = o.optString("jsonCloseField", "close"),
                dateFormat = o.optString("dateFormat", "yyyy-MM-dd")
            )
        }.getOrDefault(CustomConfig())
    }
}
