package com.cosmos.unreddit.ui.multireddit

import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.filter
import com.cosmos.unreddit.data.local.mapper.PostMapper2
import com.cosmos.unreddit.data.model.Data
import com.cosmos.unreddit.data.model.Sort
import com.cosmos.unreddit.data.model.Sorting
import com.cosmos.unreddit.data.model.db.MultiredditWithSubreddits
import com.cosmos.unreddit.data.model.db.PostEntity
import com.cosmos.unreddit.data.model.db.Subscription
import com.cosmos.unreddit.data.model.preferences.ContentPreferences
import com.cosmos.unreddit.data.repository.PostListRepository
import com.cosmos.unreddit.data.repository.PreferencesRepository
import com.cosmos.unreddit.di.DispatchersModule.DefaultDispatcher
import com.cosmos.unreddit.ui.base.BaseViewModel
import com.cosmos.unreddit.util.PostUtil
import com.cosmos.unreddit.util.extension.updateValue
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MultiredditViewModel @Inject constructor(
    private val repository: PostListRepository,
    preferencesRepository: PreferencesRepository,
    private val postMapper: PostMapper2,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher
) : BaseViewModel(preferencesRepository, repository) {

    val contentPreferences: Flow<ContentPreferences> =
        preferencesRepository.getContentPreferences()

    private val _multiredditId: MutableStateFlow<Int> = MutableStateFlow(-1)

    private val _sorting: MutableStateFlow<Sorting> = MutableStateFlow(DEFAULT_SORTING)
    val sorting: StateFlow<Sorting> = _sorting

    val multireddit: StateFlow<MultiredditWithSubreddits?> = _multiredditId
        .flatMapLatest { id ->
            if (id > 0) repository.getMultireddit(id) else flowOf(null)
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            null
        )

    val subscribedAndPinnedNames: StateFlow<List<String>> = subscriptions
        .map { list ->
            list.filter { it.isSubscribed || it.isPinned }
                .map { it.name }
                .distinctBy { it.lowercase() }
                .sortedBy { it.lowercase() }
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            emptyList()
        )

    private val _lastRefresh: MutableStateFlow<Long> = MutableStateFlow(System.currentTimeMillis())
    val lastRefresh: StateFlow<Long> = _lastRefresh.asStateFlow()

    private var latestUser: Data.User? = null

    private val userData: Flow<Data.User> = combine(
        historyIds, savedPostIds, contentPreferences, hiddenPostIds
    ) { history, saved, prefs, hidden ->
        Data.User(history, saved, prefs, hidden = hidden)
    }.onEach {
        latestUser = it
    }.distinctUntilChangedBy {
        it.contentPreferences
    }

    private val feedQuery: Flow<Triple<List<String>, Map<String, Int>, Sorting>> = combine(
        multireddit.map { multi ->
            val names = multi?.subredditNames ?: emptyList()
            val weights = multi?.subredditWeights ?: emptyMap()
            val activeNames = names.filter {
                (weights[it] ?: Subscription.DEFAULT_HOME_WEIGHT) > 0
            }
            activeNames to weights
        }.distinctUntilChanged(),
        sorting
    ) { (subreddits, weights), sorting ->
        Triple(subreddits, weights, sorting)
    }

    val postDataFlow: Flow<PagingData<PostEntity>>

    init {
        val rawPostDataFlow = feedQuery
            .flatMapLatest { (subreddits, weights, sorting) ->
                if (subreddits.isEmpty()) {
                    flowOf(PagingData.empty())
                } else {
                    userData.flatMapLatest { user ->
                        getPosts(subreddits, weights, sorting, user)
                    }
                }
            }
            .onEach { _lastRefresh.value = System.currentTimeMillis() }
            .cachedIn(viewModelScope)

        postDataFlow = combine(rawPostDataFlow, hiddenPostIds) { pagingData, hiddenIds ->
            pagingData.filter { post -> !hiddenIds.contains(post.id) }
        }
    }

    private fun getPosts(
        subreddits: List<String>,
        weights: Map<String, Int>,
        sorting: Sorting,
        user: Data.User
    ): Flow<PagingData<PostEntity>> {
        return repository.getPosts(subreddits, sorting, subredditWeights = weights)
            .map { pagingData ->
                PostUtil.filterPosts(pagingData, latestUser ?: user, postMapper, defaultDispatcher)
            }
    }

    fun setMultiredditId(id: Int) {
        _multiredditId.updateValue(id)
    }

    fun setSorting(sorting: Sorting) {
        _sorting.updateValue(sorting)
    }

    fun renameMultireddit(newName: String) {
        val id = _multiredditId.value
        if (id > 0 && newName.isNotBlank()) {
            viewModelScope.launch {
                repository.renameMultireddit(id, newName)
            }
        }
    }

    fun updateSubreddits(subreddits: List<String>) {
        val id = _multiredditId.value
        if (id > 0) {
            viewModelScope.launch {
                repository.updateMultiredditSubreddits(id, subreddits)
            }
        }
    }

    fun saveSubredditWeights(weights: Map<String, Int>) {
        val id = _multiredditId.value
        if (id > 0) {
            viewModelScope.launch {
                repository.updateMultiredditSubredditWeights(id, weights)
            }
        }
    }

    fun deleteMultireddit(onDeleted: () -> Unit) {
        val id = _multiredditId.value
        if (id > 0) {
            viewModelScope.launch {
                repository.deleteMultireddit(id)
                onDeleted()
            }
        }
    }

    companion object {
        private val DEFAULT_SORTING = Sorting(Sort.HOT)
    }
}
