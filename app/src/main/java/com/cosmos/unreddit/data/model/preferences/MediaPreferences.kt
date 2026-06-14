package com.cosmos.unreddit.data.model.preferences

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey

data class MediaPreferences(
    val muteVideo: Boolean,
    val commentImageMode: Int
) {
    object PreferencesKeys {
        val MUTE_VIDEO = booleanPreferencesKey("mute_video")
        val COMMENT_IMAGE_MODE = intPreferencesKey("comment_image_mode")
    }
}
