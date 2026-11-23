package com.cosmos.unreddit.ui.subscriptions

import androidx.annotation.StringRes
import androidx.lifecycle.viewModelScope
import com.cosmos.unreddit.R
import com.cosmos.unreddit.data.model.db.MultiredditWithSubreddits
import com.cosmos.unreddit.data.model.db.Subscription
import com.cosmos.unreddit.data.repository.PostListRepository
import com.cosmos.unreddit.data.repository.PreferencesRepository
import com.cosmos.unreddit.di.DispatchersModule.DefaultDispatcher
import com.cosmos.unreddit.ui.base.BaseViewModel
import com.cosmos.unreddit.util.extension.latest
import com.cosmos.unreddit.util.extension.updateValue
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class SubscriptionListItem {
    data class Section(@StringRes val titleRes: Int) : SubscriptionListItem()
    data class SubredditItem(val subscription: Subscription) : SubscriptionListItem()
}

@HiltViewModel
class SubscriptionsViewModel @Inject constructor(
    preferencesRepository: PreferencesRepository,
    private val repository: PostListRepository,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher
) : BaseViewModel(preferencesRepository, repository) {

    enum class Tab {
        SUBREDDITS,
        MULTIREDDITS
    }

    private val _selectedTab: MutableStateFlow<Tab> = MutableStateFlow(Tab.SUBREDDITS)
    val selectedTab: StateFlow<Tab> = _selectedTab.asStateFlow()

    private val _searchQuery: MutableStateFlow<String> = MutableStateFlow("")

    val filteredSubscriptions: Flow<List<SubscriptionListItem>> = combine(
        subscriptions,
        _searchQuery
    ) { subscriptions, searchQuery ->
        val matching = subscriptions.filter {
            (it.isSubscribed || it.isPinned) && it.name.contains(searchQuery, ignoreCase = true)
        }
        val pinned = matching.filter { it.isPinned }
        val subscribed = matching.filter { !it.isPinned && it.isSubscribed }

        buildList {
            if (pinned.isNotEmpty()) {
                add(SubscriptionListItem.Section(R.string.subscriptions_section_pinned))
                pinned.forEach { add(SubscriptionListItem.SubredditItem(it)) }
            }
            if (subscribed.isNotEmpty()) {
                if (pinned.isNotEmpty()) {
                    add(SubscriptionListItem.Section(R.string.subscriptions_section_subscribed))
                }
                subscribed.forEach { add(SubscriptionListItem.SubredditItem(it)) }
            }
        }
    }.flowOn(defaultDispatcher)

    val filteredMultireddits: Flow<List<MultiredditWithSubreddits>> = combine(
        multireddits,
        _searchQuery
    ) { list, searchQuery ->
        if (searchQuery.isBlank()) {
            list
        } else {
            list.filter { item ->
                item.multireddit.name.contains(searchQuery, ignoreCase = true) ||
                    item.subreddits.any { it.subreddit.contains(searchQuery, ignoreCase = true) }
            }
        }
    }.flowOn(defaultDispatcher)

    fun setSelectedTab(tab: Tab) {
        _selectedTab.updateValue(tab)
    }

    fun setSearchQuery(query: String) {
        _searchQuery.updateValue(query)
    }

    fun createMultireddit(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            currentProfile.latest?.let { profile ->
                repository.createMultireddit(name, profile.id)
            }
        }
    }

    fun togglePin(subscription: Subscription) {
        viewModelScope.launch {
            currentProfile.latest?.let { profile ->
                if (subscription.isPinned) {
                    repository.unpin(subscription.name, profile.id)
                } else {
                    repository.pin(subscription.name, profile.id, subscription.icon)
                }
            }
        }
    }

    fun subscribe(subscription: Subscription) {
        viewModelScope.launch {
            currentProfile.latest?.let { profile ->
                repository.subscribe(subscription.name, profile.id, subscription.icon)
            }
        }
    }

    fun unsubscribe(subscription: Subscription) {
        viewModelScope.launch {
            currentProfile.latest?.let { profile ->
                repository.unsubscribe(subscription.name, profile.id)
            }
        }
    }
}
