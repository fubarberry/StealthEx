package com.cosmos.unreddit.data.model.preferences

import androidx.appcompat.app.AppCompatDelegate
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey

data class UiPreferences(
    val nightMode: Int,
    val leftHandedMode: Boolean,
    val useCompactLayout: Boolean = false,
    val usePopularFeed: Boolean = false,
    val homeFeedType: Int = 0
) {
    object PreferencesKeys {
        val NIGHT_MODE = intPreferencesKey("night_mode")
        val LEFT_HANDED_MODE = booleanPreferencesKey("left_handed_mode")
        val USE_COMPACT_LAYOUT = booleanPreferencesKey("use_compact_layout")
        val USE_POPULAR_FEED = booleanPreferencesKey("use_popular_feed")
        val HOME_FEED_TYPE = intPreferencesKey("home_feed_type")
    }

    enum class NightMode(val index: Int, val mode: Int) {
        // Indexes must follow pref_night_mode_labels array
        LIGHT(0, AppCompatDelegate.MODE_NIGHT_NO),
        DARK(1, AppCompatDelegate.MODE_NIGHT_YES),
        AMOLED(2, 434),
        FOLLOW_SYSTEM(3, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);

        companion object {
            fun asIndex(nightMode: Int): Int? {
                return values().find { it.mode == nightMode }?.index
            }

            fun asMode(index: Int): Int? {
                return values().find { it.index == index }?.mode
            }

            fun isAmoled(nightMode: Int): Boolean {
                return values().find { it.mode == nightMode } == AMOLED
            }
        }
    }
}
