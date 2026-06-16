package com.cosmos.unreddit.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import com.cosmos.unreddit.data.model.db.HiddenPostEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class HiddenPostDao : BaseDao<HiddenPostEntity> {

    @Query("DELETE FROM hidden_post WHERE id = :id AND profile_id = :profileId")
    abstract suspend fun deleteFromIdAndProfile(id: String, profileId: Int)

    @Query("DELETE FROM hidden_post WHERE profile_id = :profileId")
    abstract suspend fun deleteFromProfile(profileId: Int)

    @Query("SELECT id FROM hidden_post WHERE profile_id = :profileId")
    abstract fun getHiddenPostIdsFromProfile(profileId: Int): Flow<List<String>>

    @Query("SELECT * FROM hidden_post WHERE profile_id = :profileId")
    abstract fun getHiddenPostsFromProfile(profileId: Int): Flow<List<HiddenPostEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM hidden_post WHERE id = :id AND profile_id = :profileId)")
    abstract suspend fun isPostHidden(id: String, profileId: Int): Boolean
}
