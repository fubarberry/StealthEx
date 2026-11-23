package com.cosmos.unreddit.data.local.backup

import android.net.Uri
import com.cosmos.unreddit.data.local.RedditDatabase
import com.cosmos.unreddit.data.local.mapper.BackupCommentMapper
import com.cosmos.unreddit.data.local.mapper.BackupPostMapper
import com.cosmos.unreddit.data.local.mapper.ProfileMapper
import com.cosmos.unreddit.data.local.mapper.SubscriptionMapper
import com.cosmos.unreddit.data.model.backup.Profile
import com.cosmos.unreddit.data.model.db.Multireddit
import com.cosmos.unreddit.data.model.db.toHiddenPostEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

typealias SubscriptionBackup = com.cosmos.unreddit.data.model.backup.Subscription

sealed class BackupManager(
    protected val redditDatabase: RedditDatabase,
    private val profileMapper: ProfileMapper,
    private val subscriptionMapper: SubscriptionMapper,
    private val backupPostMapper: BackupPostMapper,
    private val backupCommentMapper: BackupCommentMapper,
    protected val defaultDispatcher: CoroutineDispatcher
) {

    protected suspend fun getProfiles(): List<Profile> {
        val profilesWithSubscriptions = redditDatabase.profileDao().getProfilesWithDetails()

        return profileMapper.dataToEntities(profilesWithSubscriptions)
    }

    protected suspend fun insertProfiles(profiles: List<Profile>) {
        withContext(defaultDispatcher) {
            insert(profiles)
        }
    }

    private suspend fun insert(profiles: List<Profile>) {
        val profileDao = redditDatabase.profileDao()
        val subscriptionDao = redditDatabase.subscriptionDao()
        val postDao = redditDatabase.postDao()
        val commentDao = redditDatabase.commentDao()
        val hiddenPostDao = redditDatabase.hiddenPostDao()
        val multiredditDao = redditDatabase.multiredditDao()

        profiles.forEach { profile ->
            val targetProfileId = profileDao.getProfileByName(profile.name)?.id
                ?: if (profiles.size == 1) {
                    runCatching { profileDao.getFirstProfile().id }.getOrNull()
                } else {
                    null
                }
                ?: profileDao.insert(ProfileDb(name = profile.name)).toInt()

            subscriptionMapper
                .dataFromEntities(profile.subscriptions)
                .map { subscription -> subscription.apply { profileId = targetProfileId } }
                .toTypedArray()
                .let { subscriptions ->
                    if (subscriptions.isNotEmpty()) {
                        subscriptionDao.upsert(*subscriptions)
                    }
                }

            profile.savedPosts?.let { savedPosts ->
                backupPostMapper
                    .dataFromEntities(savedPosts)
                    .map { post -> post.apply { profileId = targetProfileId } }
                    .toTypedArray()
                    .let { posts ->
                        if (posts.isNotEmpty()) {
                            postDao.upsert(*posts)
                        }
                    }
            }

            profile.savedComments?.let { savedComments ->
                backupCommentMapper
                    .dataFromEntities(savedComments)
                    .map { comment -> comment.apply { profileId = targetProfileId } }
                    .toTypedArray()
                    .let { comments ->
                        if (comments.isNotEmpty()) {
                            commentDao.upsert(*comments)
                        }
                    }
            }

            profile.hiddenPosts?.let { hiddenPosts ->
                backupPostMapper
                    .dataFromEntities(hiddenPosts)
                    .map { post -> post.toHiddenPostEntity(targetProfileId).copy(time = post.time) }
                    .toTypedArray()
                    .let { posts ->
                        if (posts.isNotEmpty()) {
                            hiddenPostDao.upsert(*posts)
                        }
                    }
            }

            profile.multireddits?.forEach { multiBackup ->
                val cleanName = multiBackup.name.trim()
                if (cleanName.isNotBlank()) {
                    val existing = multiredditDao.getMultiredditByName(targetProfileId, cleanName)
                    if (existing != null) {
                        val merged = existing.subredditNames + multiBackup.subreddits
                        multiredditDao.replaceSubreddits(existing.multireddit.id, merged)
                    } else {
                        val multiId = multiredditDao.insertMultireddit(
                            Multireddit(
                                name = cleanName,
                                time = multiBackup.time,
                                profileId = targetProfileId
                            )
                        ).toInt()
                        if (multiBackup.subreddits.isNotEmpty()) {
                            multiredditDao.replaceSubreddits(multiId, multiBackup.subreddits)
                        }
                    }
                }
            }
        }
    }

    abstract suspend fun import(uri: Uri): Result<List<Profile>>

    abstract suspend fun export(uri: Uri): Result<List<Profile>>
}
