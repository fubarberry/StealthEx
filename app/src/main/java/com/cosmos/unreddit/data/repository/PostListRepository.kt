package com.cosmos.unreddit.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.cosmos.unreddit.data.local.RedditDatabase
import com.cosmos.unreddit.data.model.Comment
import com.cosmos.unreddit.data.model.Sorting
import com.cosmos.unreddit.data.model.db.History
import com.cosmos.unreddit.data.model.db.HiddenPostEntity
import com.cosmos.unreddit.data.model.db.Multireddit
import com.cosmos.unreddit.data.model.db.MultiredditSubreddit
import com.cosmos.unreddit.data.model.db.MultiredditWithSubreddits
import com.cosmos.unreddit.data.model.db.PostEntity
import com.cosmos.unreddit.data.model.db.Profile
import com.cosmos.unreddit.data.model.db.Subscription
import com.cosmos.unreddit.data.model.db.toHiddenPostEntity
import com.cosmos.unreddit.data.remote.api.reddit.model.AboutChild
import com.cosmos.unreddit.data.remote.api.reddit.model.AboutUserChild
import com.cosmos.unreddit.data.remote.api.reddit.model.Child
import com.cosmos.unreddit.data.remote.api.reddit.model.Listing
import com.cosmos.unreddit.data.remote.api.reddit.model.MoreChildren
import com.cosmos.unreddit.data.remote.api.reddit.source.CurrentSource
import com.cosmos.unreddit.data.remote.datasource.CommentsDataSource
import com.cosmos.unreddit.data.remote.datasource.SearchPostDataSource
import com.cosmos.unreddit.data.remote.datasource.SearchSubredditDataSource
import com.cosmos.unreddit.data.remote.datasource.SearchUserDataSource
import com.cosmos.unreddit.data.remote.datasource.SmartPostListDataSource
import com.cosmos.unreddit.data.remote.datasource.SubredditSearchPostDataSource
import com.cosmos.unreddit.data.remote.datasource.UserPostsDataSource
import com.cosmos.unreddit.di.DispatchersModule.DefaultDispatcher
import com.cosmos.unreddit.di.DispatchersModule.MainImmediateDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PostListRepository @Inject constructor(
    private val source: CurrentSource,
    private val redditDatabase: RedditDatabase,
    private val redgifsRepository: RedgifsRepository,
    private val gfycatRepository: GfycatRepository,
    private val streamableRepository: StreamableRepository,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
    @MainImmediateDispatcher private val mainImmediateDispatcher: CoroutineDispatcher
) {

    suspend fun resolveRedgifsUrl(id: String): String? {
        return runCatching {
            val item = redgifsRepository.getRedgifsGif(id).first()
            item.gif.urls.hd ?: item.gif.urls.sd
        }.getOrNull()
    }

    suspend fun resolveGfycatUrl(id: String): String? {
        return runCatching {
            gfycatRepository.getGfycatGif(id).first().gfyItem.contentUrls.mp4.url
        }.getOrNull()
    }

    suspend fun resolveStreamableUrl(shortcode: String): String? {
        return runCatching {
            streamableRepository.getVideo(shortcode).first().files.mp4.url
        }.getOrNull()
    }

    fun getPost(permalink: String, sorting: Sorting): Flow<List<Listing>> = flow {
        emit(source.getPost(permalink, sort = sorting.generalSorting))
    }

    fun getMoreChildren(children: String, linkId: String): Flow<MoreChildren> = flow {
        emit(source.getMoreChildren(children, linkId))
    }

    //region Subreddit

    fun getPosts(
        subreddit: String,
        sorting: Sorting,
        pageSize: Int = DEFAULT_LIMIT
    ): Flow<PagingData<Child>> {
        return Pager(PagingConfig(pageSize = pageSize)) {
            SmartPostListDataSource(
                source,
                listOf(subreddit),
                sorting,
                defaultDispatcher,
                mainImmediateDispatcher
            )
        }.flow
    }

    fun getPosts(
        subreddit: List<String>,
        sorting: Sorting,
        pageSize: Int = DEFAULT_LIMIT,
        subredditWeights: Map<String, Int> = emptyMap()
    ): Flow<PagingData<Child>> {
        return Pager(PagingConfig(pageSize = pageSize)) {
            SmartPostListDataSource(
                source,
                subreddit,
                sorting,
                defaultDispatcher,
                mainImmediateDispatcher,
                subredditWeights
            )
        }.flow
    }

    fun getSubredditInfo(subreddit: String): Flow<AboutChild> = flow {
        emit(source.getSubredditInfo(subreddit) as AboutChild)
    }

    //endregion

    //region Multireddits

    fun getMultireddits(profileId: Int): Flow<List<MultiredditWithSubreddits>> = redditDatabase
        .multiredditDao().getMultiredditsFromProfile(profileId).distinctUntilChanged()

    fun getMultireddit(multiredditId: Int): Flow<MultiredditWithSubreddits?> = redditDatabase
        .multiredditDao().getMultiredditById(multiredditId).distinctUntilChanged()

    suspend fun createMultireddit(
        name: String,
        profileId: Int,
        initialSubreddits: List<String> = emptyList()
    ): Int {
        val dao = redditDatabase.multiredditDao()
        val id = dao.insertMultireddit(
            Multireddit(
                name = name.trim(),
                time = System.currentTimeMillis(),
                profileId = profileId
            )
        ).toInt()
        if (initialSubreddits.isNotEmpty()) {
            dao.replaceSubreddits(id, initialSubreddits)
        }
        return id
    }

    suspend fun renameMultireddit(multiredditId: Int, newName: String) {
        redditDatabase.multiredditDao().renameMultireddit(multiredditId, newName.trim())
    }

    suspend fun deleteMultireddit(multiredditId: Int) {
        redditDatabase.multiredditDao().deleteMultireddit(multiredditId)
    }

    suspend fun addSubredditToMultireddit(multiredditId: Int, subreddit: String) {
        val clean = subreddit.trim()
        if (clean.isNotBlank()) {
            redditDatabase.multiredditDao().addSubreddit(
                MultiredditSubreddit(multiredditId, clean)
            )
        }
    }

    suspend fun removeSubredditFromMultireddit(multiredditId: Int, subreddit: String) {
        redditDatabase.multiredditDao().removeSubreddit(multiredditId, subreddit.trim())
    }

    suspend fun updateMultiredditSubreddits(multiredditId: Int, subreddits: List<String>) {
        redditDatabase.multiredditDao().replaceSubreddits(multiredditId, subreddits)
    }

    suspend fun updateMultiredditSubredditWeights(multiredditId: Int, weights: Map<String, Int>) {
        val dao = redditDatabase.multiredditDao()
        weights.forEach { (subreddit, weight) ->
            val clamped = weight.coerceIn(
                Subscription.MIN_HOME_WEIGHT,
                Subscription.MAX_HOME_WEIGHT
            )
            dao.updateSubredditWeight(multiredditId, subreddit, clamped)
        }
    }

    //endregion

    //region Subscriptions

    fun getSubscriptions(profileId: Int): Flow<List<Subscription>> = redditDatabase
        .subscriptionDao().getSubscriptionsFromProfile(profileId).distinctUntilChanged()

    fun getSubscriptionsNames(profileId: Int): Flow<List<String>> {
        return redditDatabase.subscriptionDao().getSubscriptionsNamesFromProfile(profileId)
    }

    fun getPinnedNames(profileId: Int): Flow<List<String>> {
        return redditDatabase.subscriptionDao().getPinnedNamesFromProfile(profileId)
    }

    suspend fun updateSubscriptionHomeWeights(profileId: Int, weights: Map<String, Int>) {
        val dao = redditDatabase.subscriptionDao()
        weights.forEach { (name, weight) ->
            val clamped = weight.coerceIn(
                Subscription.MIN_HOME_WEIGHT,
                Subscription.MAX_HOME_WEIGHT
            )
            dao.updateHomeWeight(name, profileId, clamped)
        }
    }

    suspend fun resetSubscriptionHomeWeights(profileId: Int) {
        redditDatabase.subscriptionDao().resetHomeWeights(profileId)
    }

    suspend fun subscribe(name: String, profileId: Int, icon: String? = null) {
        val dao = redditDatabase.subscriptionDao()
        val existing = dao.getSubscriptionFromNameAndProfile(name, profileId)
        if (existing != null) {
            dao.update(
                existing.copy(
                    time = System.currentTimeMillis(),
                    icon = icon ?: existing.icon,
                    isSubscribed = true
                )
            )
        } else {
            dao.insert(
                Subscription(
                    name = name,
                    time = System.currentTimeMillis(),
                    icon = icon,
                    profileId = profileId,
                    isSubscribed = true,
                    isPinned = false
                )
            )
        }
    }

    suspend fun unsubscribe(name: String, profileId: Int) {
        val dao = redditDatabase.subscriptionDao()
        val existing = dao.getSubscriptionFromNameAndProfile(name, profileId)
        if (existing != null && existing.isPinned) {
            dao.update(existing.copy(isSubscribed = false))
        } else {
            dao.deleteFromNameAndProfile(name, profileId)
        }
    }

    suspend fun pin(name: String, profileId: Int, icon: String? = null) {
        val dao = redditDatabase.subscriptionDao()
        val existing = dao.getSubscriptionFromNameAndProfile(name, profileId)
        if (existing != null) {
            dao.update(
                existing.copy(
                    icon = icon ?: existing.icon,
                    isPinned = true
                )
            )
        } else {
            dao.insert(
                Subscription(
                    name = name,
                    time = System.currentTimeMillis(),
                    icon = icon,
                    profileId = profileId,
                    isSubscribed = false,
                    isPinned = true
                )
            )
        }
    }

    suspend fun unpin(name: String, profileId: Int) {
        val dao = redditDatabase.subscriptionDao()
        val existing = dao.getSubscriptionFromNameAndProfile(name, profileId)
        if (existing != null && existing.isSubscribed) {
            dao.update(existing.copy(isPinned = false))
        } else {
            dao.deleteFromNameAndProfile(name, profileId)
        }
    }

    //endregion

    //region User

    fun getUserPosts(
        user: String,
        sorting: Sorting,
        pageSize: Int = DEFAULT_LIMIT
    ): Flow<PagingData<Child>> {
        return Pager(PagingConfig(pageSize = pageSize)) {
            UserPostsDataSource(source, user, sorting)
        }.flow
    }

    fun getUserComments(
        user: String,
        sorting: Sorting,
        pageSize: Int = DEFAULT_LIMIT
    ): Flow<PagingData<Child>> {
        return Pager(PagingConfig(pageSize = pageSize)) {
            CommentsDataSource(source, user, sorting)
        }.flow
    }

    fun getUserInfo(user: String): Flow<AboutUserChild> = flow {
        emit(source.getUserInfo(user) as AboutUserChild)
    }

    //endregion

    //region Search

    fun searchPost(
        query: String,
        sorting: Sorting,
        pageSize: Int = DEFAULT_LIMIT
    ): Flow<PagingData<Child>> {
        return Pager(PagingConfig(pageSize = pageSize)) {
            SearchPostDataSource(source, query, sorting)
        }.flow
    }

    fun searchUser(
        query: String,
        sorting: Sorting,
        pageSize: Int = DEFAULT_LIMIT
    ): Flow<PagingData<Child>> {
        return Pager(PagingConfig(pageSize = pageSize)) {
            SearchUserDataSource(source, query, sorting)
        }.flow
    }

    fun searchSubreddit(
        query: String,
        sorting: Sorting,
        pageSize: Int = DEFAULT_LIMIT
    ): Flow<PagingData<Child>> {
        return Pager(PagingConfig(pageSize = pageSize)) {
            SearchSubredditDataSource(source, query, sorting)
        }.flow
    }

    fun searchInSubreddit(
        query: String,
        subreddit: String,
        sorting: Sorting,
        pageSize: Int = DEFAULT_LIMIT
    ): Flow<PagingData<Child>> {
        return Pager(PagingConfig(pageSize = pageSize)) {
            SubredditSearchPostDataSource(source, subreddit, query, sorting)
        }.flow
    }

    //endregion

    //region History

    fun getHistoryIds(profileId: Int): Flow<List<String>> {
        return redditDatabase.historyDao().getHistoryIdsFromProfile(profileId)
    }

    suspend fun insertPostInHistory(postId: String, profileId: Int) {
        redditDatabase.historyDao().upsert(History(postId, System.currentTimeMillis(), profileId))
    }

    //endregion

    //region Profile

    suspend fun addProfile(name: String) {
        redditDatabase.profileDao().insert(Profile(name = name))
    }

    suspend fun getProfile(id: Int): Profile {
        return redditDatabase.profileDao().getProfileFromId(id)
            ?: redditDatabase.profileDao().getFirstProfile()
    }

    fun getAllProfiles(): Flow<List<Profile>> {
        return redditDatabase.profileDao().getAllProfiles()
    }

    suspend fun deleteProfile(profileId: Int) {
        redditDatabase.profileDao().deleteFromId(profileId)
    }

    suspend fun updateProfile(profile: Profile) {
        redditDatabase.profileDao().update(profile)
    }

    //endregion

    //region Save

    suspend fun savePost(post: PostEntity, profileId: Int) {
        post.run {
            this.profileId = profileId
            this.time = System.currentTimeMillis()
            redditDatabase.postDao().upsert(this)
        }
    }

    suspend fun unsavePost(post: PostEntity, profileId: Int) {
        redditDatabase.postDao().deleteFromIdAndProfile(post.id, profileId)
    }

    fun getSavedPosts(profileId: Int): Flow<List<PostEntity>> {
        return redditDatabase.postDao().getSavedPostsFromProfile(profileId)
    }

    fun getSavedPostIds(profileId: Int): Flow<List<String>> {
        return redditDatabase.postDao().getSavedPostIdsFromProfile(profileId)
    }

    suspend fun hidePost(post: PostEntity, profileId: Int) {
        redditDatabase.hiddenPostDao().upsert(post.toHiddenPostEntity(profileId))
    }

    suspend fun unhidePost(post: PostEntity, profileId: Int) {
        redditDatabase.hiddenPostDao().deleteFromIdAndProfile(post.id, profileId)
    }

    fun getHiddenPosts(profileId: Int): Flow<List<HiddenPostEntity>> {
        return redditDatabase.hiddenPostDao().getHiddenPostsFromProfile(profileId)
    }

    fun getHiddenPostIds(profileId: Int): Flow<List<String>> {
        return redditDatabase.hiddenPostDao().getHiddenPostIdsFromProfile(profileId)
    }

    suspend fun isPostHidden(postId: String, profileId: Int): Boolean {
        return redditDatabase.hiddenPostDao().isPostHidden(postId, profileId)
    }

    suspend fun saveComment(comment: Comment.CommentEntity, profileId: Int) {
        comment.run {
            this.profileId = profileId
            this.time = System.currentTimeMillis()
            redditDatabase.commentDao().upsert(comment)
        }
    }

    suspend fun unsaveComment(comment: Comment.CommentEntity, profileId: Int) {
        redditDatabase.commentDao().deleteFromIdAndProfile(comment.name, profileId)
    }

    fun getSavedComments(profileId: Int): Flow<List<Comment.CommentEntity>> {
        return redditDatabase.commentDao().getSavedCommentsFromProfile(profileId)
    }

    fun getSavedCommentIds(profileId: Int): Flow<List<String>> {
        return redditDatabase.commentDao().getSavedCommentIdsFromProfile(profileId)
    }

    //endregion

    companion object {
        private const val DEFAULT_LIMIT = 25
    }
}
