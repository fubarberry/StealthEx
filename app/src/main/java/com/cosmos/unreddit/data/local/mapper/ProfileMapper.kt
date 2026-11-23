package com.cosmos.unreddit.data.local.mapper

import com.cosmos.unreddit.data.model.backup.Multireddit
import com.cosmos.unreddit.data.model.backup.Profile
import com.cosmos.unreddit.data.model.db.ProfileWithDetails
import com.cosmos.unreddit.data.model.db.toPostEntity
import com.cosmos.unreddit.di.DispatchersModule.DefaultDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileMapper @Inject constructor(
    private val subscriptionMapper: SubscriptionMapper,
    private val backupPostMapper: BackupPostMapper,
    private val backupCommentMapper: BackupCommentMapper,
    @DefaultDispatcher defaultDispatcher: CoroutineDispatcher
) : Mapper<ProfileWithDetails, Profile>(defaultDispatcher) {

    override suspend fun toEntity(from: ProfileWithDetails): Profile {
        return with(from) {
            Profile(
                name = profile.name,
                subscriptions = subscriptionMapper.dataToEntities(subscription),
                savedPosts = backupPostMapper.dataToEntities(savedPosts),
                savedComments = backupCommentMapper.dataToEntities(savedComments),
                hiddenPosts = backupPostMapper.dataToEntities(hiddenPosts.map { it.toPostEntity() }),
                multireddits = multireddits.map { item ->
                    Multireddit(
                        name = item.multireddit.name,
                        time = item.multireddit.time,
                        subreddits = item.subredditNames
                    )
                }
            )
        }
    }
}
