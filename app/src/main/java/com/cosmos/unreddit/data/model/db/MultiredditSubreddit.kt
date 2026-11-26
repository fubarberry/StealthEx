package com.cosmos.unreddit.data.model.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey

@Entity(
    tableName = "multireddit_subreddit",
    primaryKeys = ["multireddit_id", "subreddit"],
    foreignKeys = [
        ForeignKey(
            entity = Multireddit::class,
            parentColumns = ["id"],
            childColumns = ["multireddit_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class MultiredditSubreddit(
    @ColumnInfo(name = "multireddit_id", index = true)
    val multiredditId: Int,

    @ColumnInfo(name = "subreddit", collate = ColumnInfo.NOCASE)
    val subreddit: String,

    @ColumnInfo(name = "weight", defaultValue = "100")
    val weight: Int = Subscription.DEFAULT_HOME_WEIGHT
)
