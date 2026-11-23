package com.cosmos.unreddit.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.cosmos.unreddit.data.model.db.Multireddit
import com.cosmos.unreddit.data.model.db.MultiredditSubreddit
import com.cosmos.unreddit.data.model.db.MultiredditWithSubreddits
import kotlinx.coroutines.flow.Flow

@Dao
abstract class MultiredditDao {

    @Transaction
    @Query("SELECT * FROM multireddit WHERE profile_id = :profileId ORDER BY name COLLATE NOCASE ASC")
    abstract fun getMultiredditsFromProfile(profileId: Int): Flow<List<MultiredditWithSubreddits>>

    @Transaction
    @Query("SELECT * FROM multireddit WHERE id = :multiredditId")
    abstract fun getMultiredditById(multiredditId: Int): Flow<MultiredditWithSubreddits?>

    @Transaction
    @Query("SELECT * FROM multireddit WHERE profile_id = :profileId AND name = :name COLLATE NOCASE LIMIT 1")
    abstract suspend fun getMultiredditByName(profileId: Int, name: String): MultiredditWithSubreddits?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertMultireddit(multireddit: Multireddit): Long

    @Query("UPDATE multireddit SET name = :name WHERE id = :multiredditId")
    abstract suspend fun renameMultireddit(multiredditId: Int, name: String)

    @Query("DELETE FROM multireddit WHERE id = :multiredditId")
    abstract suspend fun deleteMultireddit(multiredditId: Int)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun addSubreddit(entry: MultiredditSubreddit)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun addSubreddits(entries: List<MultiredditSubreddit>)

    @Query("DELETE FROM multireddit_subreddit WHERE multireddit_id = :multiredditId AND subreddit = :subreddit COLLATE NOCASE")
    abstract suspend fun removeSubreddit(multiredditId: Int, subreddit: String)

    @Query("DELETE FROM multireddit_subreddit WHERE multireddit_id = :multiredditId")
    abstract suspend fun clearSubreddits(multiredditId: Int)

    @Transaction
    open suspend fun replaceSubreddits(multiredditId: Int, subreddits: List<String>) {
        clearSubreddits(multiredditId)
        val distinctEntries = subreddits
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .map { MultiredditSubreddit(multiredditId, it) }
        if (distinctEntries.isNotEmpty()) {
            addSubreddits(distinctEntries)
        }
    }
}
