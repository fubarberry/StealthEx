package com.cosmos.unreddit.data.model.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey

@Entity(
    tableName = "subscription",
    primaryKeys = ["name", "profile_id"],
    foreignKeys = [
        ForeignKey(
            entity = Profile::class,
            parentColumns = ["id"],
            childColumns = ["profile_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class Subscription (
    @ColumnInfo(name = "name", collate = ColumnInfo.NOCASE)
    val name: String,

    @ColumnInfo(name = "time")
    val time: Long,

    @ColumnInfo(name = "icon")
    val icon: String?,

    @ColumnInfo(name = "profile_id", index = true)
    var profileId: Int = 1,

    @ColumnInfo(name = "is_subscribed", defaultValue = "1")
    val isSubscribed: Boolean = true,

    @ColumnInfo(name = "is_pinned", defaultValue = "0")
    val isPinned: Boolean = false,

    @ColumnInfo(name = "home_weight", defaultValue = "100")
    val homeWeight: Int = DEFAULT_HOME_WEIGHT
) {
    companion object {
        const val DEFAULT_HOME_WEIGHT = 100
        const val MIN_HOME_WEIGHT = 0
        const val MAX_HOME_WEIGHT = 200
    }
}
