package de.westnordost.streetcomplete.screens.settings

import de.westnordost.streetcomplete.data.preferences.Preferences
import kotlinx.serialization.json.Json

private val perPresetQuestSettingKey = Regex("\\d+_qs_.+")

internal enum class SettingsImportPolicy {
    /** Same key set as [exportSettings]. */
    General,
    CustomOverlay,
    PresetQuestSettings,
}

internal fun isExportableSettingsKey(key: String): Boolean =
    !key.contains("TangramPinsSpriteSheet")
        && !key.contains("TangramIconsSpriteSheet")
        && key != Preferences.OAUTH2_ACCESS_TOKEN
        && !key.contains("osm.")
        && !key.matches(perPresetQuestSettingKey)
        && !key.startsWith("custom_overlay")

internal fun isBlockedFromSettingsImport(key: String): Boolean =
    key == Preferences.OAUTH2_ACCESS_TOKEN
        || key.contains("osm.")
        || key.contains("TangramPinsSpriteSheet")
        || key.contains("TangramIconsSpriteSheet")
        || key == Preferences.LAST_VERSION_DATA
        || key == "lastVersion"

private fun isAllowedImportKey(key: String, policy: SettingsImportPolicy): Boolean {
    if (isBlockedFromSettingsImport(key)) return false
    return when (policy) {
        SettingsImportPolicy.General -> isExportableSettingsKey(key)
        SettingsImportPolicy.CustomOverlay ->
            key.startsWith("custom_overlay")
                || key == "custom_overlay_filter"
                || key == "custom_overlay_color_key"
        SettingsImportPolicy.PresetQuestSettings -> key.matches(perPresetQuestSettingKey)
    }
}

private data class ParsedSettings(
    val booleans: Map<String, Boolean> = emptyMap(),
    val ints: Map<String, Int> = emptyMap(),
    val longs: Map<String, Long> = emptyMap(),
    val floats: Map<String, Float> = emptyMap(),
    val strings: Map<String, String> = emptyMap(),
) {
    fun allKeys(): Set<String> =
        booleans.keys + ints.keys + longs.keys + floats.keys + strings.keys
}

private fun filterParsed(parsed: ParsedSettings, policy: SettingsImportPolicy): ParsedSettings {
    fun <V> Map<String, V>.filterAllowed() = filterKeys { isAllowedImportKey(it, policy) }
    return ParsedSettings(
        booleans = parsed.booleans.filterAllowed(),
        ints = parsed.ints.filterAllowed(),
        longs = parsed.longs.filterAllowed(),
        floats = parsed.floats.filterAllowed(),
        strings = parsed.strings.filterAllowed(),
    )
}

@Suppress("UNCHECKED_CAST")
internal fun settingsToString(settings: Map<String, Any?>): String {
    val booleans = settings.filterValues { it is Boolean } as Map<String, Boolean>
    val ints = settings.filterValues { it is Int } as Map<String, Int>
    val longs = settings.filterValues { it is Long } as Map<String, Long>
    val floats = settings.filterValues { it is Float } as Map<String, Float>
    val strings = settings.filterValues { it is String } as Map<String, String>
    val sb = StringBuilder()
    sb.appendLine("boolean settings")
    sb.appendLine(Json.encodeToString(booleans))
    sb.appendLine()
    sb.appendLine("int settings")
    sb.appendLine(Json.encodeToString(ints))
    sb.appendLine()
    sb.appendLine("long settings")
    sb.appendLine(Json.encodeToString(longs))
    sb.appendLine()
    sb.appendLine("float settings")
    sb.appendLine(Json.encodeToString(floats))
    sb.appendLine()
    sb.appendLine("string settings")
    sb.appendLine(Json.encodeToString(strings))
    return sb.toString()
}

private fun parseSettingsLines(list: List<String>): ParsedSettings? {
    val booleans = mutableMapOf<String, Boolean>()
    val ints = mutableMapOf<String, Int>()
    val longs = mutableMapOf<String, Long>()
    val floats = mutableMapOf<String, Float>()
    val strings = mutableMapOf<String, String>()
    val i = list.iterator()
    try {
        while (i.hasNext()) {
            val next = i.next()
            if (next.isBlank()) continue
            when (next) {
                "boolean settings" -> {
                    if (!i.hasNext()) return null
                    val json = i.next()
                    if (json.isBlank()) return null
                    booleans.putAll(Json.decodeFromString<Map<String, Boolean>>(json))
                }
                "int settings" -> {
                    if (!i.hasNext()) return null
                    val json = i.next()
                    if (json.isBlank()) return null
                    ints.putAll(Json.decodeFromString<Map<String, Int>>(json))
                }
                "long settings" -> {
                    if (!i.hasNext()) return null
                    val json = i.next()
                    if (json.isBlank()) return null
                    longs.putAll(Json.decodeFromString<Map<String, Long>>(json))
                }
                "float settings" -> {
                    if (!i.hasNext()) return null
                    val json = i.next()
                    if (json.isBlank()) return null
                    floats.putAll(Json.decodeFromString<Map<String, Float>>(json))
                }
                "string settings" -> {
                    if (!i.hasNext()) return null
                    val json = i.next()
                    if (json.isBlank()) return null
                    strings.putAll(Json.decodeFromString<Map<String, String>>(json))
                }
                else -> return null
            }
        }
        return ParsedSettings(booleans, ints, longs, floats, strings)
    } catch (_: Exception) {
        return null
    }
}

private class Snapshot(private val prefs: Preferences) {
    private val previous = mutableMapOf<String, Any?>()
    private val absentBeforeImport = mutableSetOf<String>()

    fun remember(key: String) {
        if (key in previous || key in absentBeforeImport) return
        if (prefs.contains(key)) {
            previous[key] = prefs.all[key]
        } else {
            absentBeforeImport += key
        }
    }

    fun rollback(keys: Collection<String>) {
        for (key in keys) {
            when {
                key in absentBeforeImport -> prefs.remove(key)
                key in previous -> when (val value = previous[key]) {
                    null -> prefs.remove(key)
                    is Boolean -> prefs.putBoolean(key, value)
                    is Int -> prefs.putInt(key, value)
                    is Long -> prefs.putLong(key, value)
                    is Float -> prefs.putFloat(key, value)
                    is String -> prefs.putString(key, value)
                    else -> prefs.remove(key)
                }
            }
        }
    }
}

/**
 * Parses the full file, applies only [policy]-allowed keys, and rolls back on write failure.
 * Omitted keys in the file are left unchanged. Not a single multi-key transaction on Android.
 */
internal fun readToSettings(
    list: List<String>,
    prefs: Preferences,
    policy: SettingsImportPolicy = SettingsImportPolicy.General,
): Boolean {
    val parsed = parseSettingsLines(list) ?: return false
    val filtered = filterParsed(parsed, policy)
    val snapshot = Snapshot(prefs)
    val applied = mutableListOf<String>()
    fun rememberAndApply(key: String, apply: () -> Unit) {
        snapshot.remember(key)
        apply()
        applied += key
    }
    return try {
        filtered.booleans.forEach { (k, v) -> rememberAndApply(k) { prefs.putBoolean(k, v) } }
        filtered.ints.forEach { (k, v) -> rememberAndApply(k) { prefs.putInt(k, v) } }
        filtered.longs.forEach { (k, v) -> rememberAndApply(k) { prefs.putLong(k, v) } }
        filtered.floats.forEach { (k, v) -> rememberAndApply(k) { prefs.putFloat(k, v) } }
        filtered.strings.forEach { (k, v) -> rememberAndApply(k) { prefs.putString(k, v) } }
        true
    } catch (_: Exception) {
        snapshot.rollback(applied)
        false
    }
}