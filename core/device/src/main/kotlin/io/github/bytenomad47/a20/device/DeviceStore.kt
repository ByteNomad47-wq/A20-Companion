// SPDX-License-Identifier: GPL-3.0-only
package io.github.bytenomad47.a20.device

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.devicePreferences by preferencesDataStore("a20_companion")

data class UserSettings(
    val address: String? = null,
    val name: String? = null,
    val ready: Boolean = false,
    val automation: Boolean = false,
    val theme: ThemeChoice = ThemeChoice.SYSTEM,
)

class DeviceStore(context: Context) {
    private val data = context.devicePreferences

    private object Keys {
        val address = stringPreferencesKey("device_address")
        val name = stringPreferencesKey("device_name")
        val ready = booleanPreferencesKey("ready")
        val automation = booleanPreferencesKey("automation")
        val theme = stringPreferencesKey("theme")
        val left = intPreferencesKey("left")
        val right = intPreferencesKey("right")
        val case = intPreferencesKey("case")
        val leftAt = longPreferencesKey("left_at")
        val rightAt = longPreferencesKey("right_at")
        val caseAt = longPreferencesKey("case_at")
    }

    val settings =
        data.data.map { p ->
            UserSettings(
                p[Keys.address],
                p[Keys.name],
                p[Keys.ready] ?: false,
                p[Keys.automation] ?: false,
                runCatching { ThemeChoice.valueOf(p[Keys.theme] ?: "SYSTEM") }
                    .getOrDefault(ThemeChoice.SYSTEM),
            )
        }
    val batteries =
        data.data.map { p ->
            listOf(
                BatteryReading(p[Keys.left], p[Keys.leftAt] ?: 0),
                BatteryReading(p[Keys.right], p[Keys.rightAt] ?: 0),
                BatteryReading(p[Keys.case], p[Keys.caseAt] ?: 0),
            )
        }

    suspend fun select(address: String, name: String) {
        data.edit { p ->
            val theme = p[Keys.theme]
            p.clear()
            if (theme != null) p[Keys.theme] = theme
            p[Keys.address] = address
            p[Keys.name] = name
        }
    }

    suspend fun forget() {
        data.edit { it.clear() }
    }

    suspend fun ready(enabled: Boolean) {
        data.edit { it[Keys.ready] = enabled }
    }

    suspend fun automation(enabled: Boolean) {
        data.edit { it[Keys.automation] = enabled }
    }

    suspend fun theme(theme: ThemeChoice) {
        data.edit { it[Keys.theme] = theme.name }
    }

    suspend fun cache(state: CompanionState) {
        data.edit { p ->
            listOf(
                    Triple(Keys.left, Keys.leftAt, state.left),
                    Triple(Keys.right, Keys.rightAt, state.right),
                    Triple(Keys.case, Keys.caseAt, state.case),
                )
                .forEach { (valueKey, timeKey, reading) ->
                    reading.percent?.let {
                        p[valueKey] = it
                        p[timeKey] = reading.receivedAtMs
                    }
                }
        }
    }
}
