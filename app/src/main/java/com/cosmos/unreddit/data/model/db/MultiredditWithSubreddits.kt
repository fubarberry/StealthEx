package com.cosmos.unreddit.data.model.db

import androidx.room.Embedded
import androidx.room.Relation

data class MultiredditWithSubreddits(
    @Embedded val multireddit: Multireddit,
    @Relation(
        parentColumn = "id",
        entityColumn = "multireddit_id"
    )
    val subreddits: List<MultiredditSubreddit>
) {
    val subredditNames: List<String>
        get() = subreddits.map { it.subreddit }.sortedBy { it.lowercase() }

    val subredditWeights: Map<String, Int>
        get() = subreddits.associate { it.subreddit to it.weight }

    fun containsSubreddit(subreddit: String): Boolean {
        return subreddits.any { it.subreddit.equals(subreddit, ignoreCase = true) }
    }
}
