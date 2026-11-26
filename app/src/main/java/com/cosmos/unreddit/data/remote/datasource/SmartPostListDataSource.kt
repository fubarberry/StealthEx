package com.cosmos.unreddit.data.remote.datasource

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.cosmos.unreddit.data.model.Sort
import com.cosmos.unreddit.data.model.Sorting
import com.cosmos.unreddit.data.remote.api.reddit.model.Child
import com.cosmos.unreddit.data.remote.api.reddit.model.PostChild
import com.cosmos.unreddit.data.remote.api.reddit.source.CurrentSource
import com.cosmos.unreddit.util.RedditUtil
import com.cosmos.unreddit.util.extension.interlace
import com.squareup.moshi.JsonDataException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException
import kotlin.math.ceil

class SmartPostListDataSource(
    private val source: CurrentSource,
    private val query: List<String>,
    private val sorting: Sorting,
    private val defaultDispatcher: CoroutineDispatcher,
    mainImmediateDispatcher: CoroutineDispatcher,
    subredditWeights: Map<String, Int> = emptyMap()
) : PagingSource<List<String>, Child>() {

    private val scope = CoroutineScope(mainImmediateDispatcher + SupervisorJob())

    private val normalizedWeights: Map<String, Int> = subredditWeights
        .mapKeys { it.key.lowercase() }

    private val activeQuery: List<String> = query.filter { sub ->
        (normalizedWeights[sub.lowercase()] ?: DEFAULT_WEIGHT) > 0
    }

    private val boostedSubreddits: List<String> = activeQuery.filter { sub ->
        (normalizedWeights[sub.lowercase()] ?: DEFAULT_WEIGHT) > DEFAULT_WEIGHT
    }

    private val hasBoostedStreams: Boolean = boostedSubreddits.isNotEmpty() && activeQuery.size > 1

    private val joinedQuery by lazy { RedditUtil.joinSubredditList(activeQuery) }
    private val chunkSize by lazy {
        // Find the optimal chunk size to have lists of similar sizes
        ceil(activeQuery.size / ceil(activeQuery.size / REDDIT_SUBREDDIT_LIMIT.toDouble())).toInt()
    }

    private val throttleAccumulators = mutableMapOf<String, Double>()

    override val keyReuseSupported: Boolean = true

    override suspend fun load(params: LoadParams<List<String>>): LoadResult<List<String>, Child> {
        return try {
            if (activeQuery.isEmpty()) {
                return LoadResult.Page(emptyList(), null, null)
            }
            if (hasBoostedStreams || activeQuery.size > REDDIT_SUBREDDIT_LIMIT) {
                getSmartData(params)
            } else {
                getData(params)
            }
        } catch (exception: IOException) {
            LoadResult.Error(exception)
        } catch (exception: HttpException) {
            LoadResult.Error(exception)
        } catch (exception: JsonDataException) {
            LoadResult.Error(exception)
        } catch (exception: Exception) {
            LoadResult.Error(exception)
        }
    }

    override fun getRefreshKey(state: PagingState<List<String>, Child>): List<String>? {
        return state.anchorPosition?.let { anchorPosition ->
            state.closestPageToPosition(anchorPosition)?.prevKey
        }
    }

    private suspend fun getSmartData(
        params: LoadParams<List<String>>
    ): LoadResult<List<String>, Child> {
        val queries = withContext(defaultDispatcher) {
            val baseChunks = if (activeQuery.size > REDDIT_SUBREDDIT_LIMIT) {
                activeQuery.chunked(chunkSize)
            } else {
                listOf(activeQuery)
            }

            val allChunks = buildList {
                addAll(baseChunks)
                if (hasBoostedStreams) {
                    // Add a dedicated boosted stream so boosted communities surface prominently
                    add(boostedSubreddits)
                }
            }

            allChunks
                .map { RedditUtil.joinSubredditList(it) }
                .mapIndexed { index, chunkedList -> chunkedList to params.key?.getOrNull(index) }
        }

        // Request the posts for each chunk in parallel
        val responses = queries
            .map {
                scope.async {
                    source.getSubreddit(
                        it.first,
                        sorting.generalSorting,
                        sorting.timeSorting,
                        it.second
                    )
                }
            }
            .awaitAll()

        // Flatten, apply weight throttling/boosting, deduplicate, and sort
        val data = withContext(defaultDispatcher) {
            val baseLists = if (hasBoostedStreams && responses.size > 1) {
                val mainLists = responses.dropLast(1).map { applyWeights(it.data.children, isBoostStream = false) }
                val boostList = applyWeights(responses.last().data.children, isBoostStream = true)
                mainLists + listOf(boostList)
            } else {
                responses.map { applyWeights(it.data.children, isBoostStream = false) }
            }

            baseLists
                .sort(sorting)
                .deduplicatePosts()
        }

        // Retrieve the `after` key for each response and create a list out of them
        val after = withContext(defaultDispatcher) {
            val keys = responses.map { it.data.after ?: "" }
            if (keys.all { it.isBlank() }) null else keys
        }

        return LoadResult.Page(data, null, after)
    }

    private suspend fun getData(params: LoadParams<List<String>>): LoadResult<List<String>, Child> {
        val response = source.getSubreddit(
            joinedQuery,
            sorting.generalSorting,
            sorting.timeSorting,
            params.key?.getOrNull(0)
        )

        val data = response.data
        val filtered = withContext(defaultDispatcher) {
            applyWeights(data.children, isBoostStream = false)
        }

        return LoadResult.Page(filtered, null, data.after?.let { listOf(it) })
    }

    private fun applyWeights(children: List<Child>, isBoostStream: Boolean): List<Child> {
        if (normalizedWeights.isEmpty()) return children
        val result = ArrayList<Child>(children.size)
        for (child in children) {
            val post = (child as? PostChild)?.data
            if (post == null) {
                result.add(child)
                continue
            }
            val subKey = post.subreddit.lowercase()
            val weight = normalizedWeights[subKey] ?: DEFAULT_WEIGHT
            if (weight <= 0) continue

            val ratio = if (isBoostStream) {
                ((weight - DEFAULT_WEIGHT).coerceAtLeast(0)) / DEFAULT_WEIGHT.toDouble()
            } else if (weight < DEFAULT_WEIGHT) {
                weight / DEFAULT_WEIGHT.toDouble()
            } else {
                1.0
            }

            if (ratio >= 1.0) {
                result.add(child)
            } else if (ratio > 0.0) {
                val accKey = if (isBoostStream) "boost:$subKey" else subKey
                val next = (throttleAccumulators[accKey] ?: 0.5) + ratio
                if (next >= 1.0) {
                    throttleAccumulators[accKey] = next - 1.0
                    result.add(child)
                } else {
                    throttleAccumulators[accKey] = next
                }
            }
        }
        return result
    }

    private fun List<Child>.deduplicatePosts(): List<Child> {
        val seenIds = HashSet<String>(size)
        return filter { child ->
            val id = (child as? PostChild)?.data?.name ?: return@filter true
            seenIds.add(id)
        }
    }

    private fun List<List<Child>>.sort(sorting: Sorting): List<Child> {
        return when(sorting.generalSorting) {
            // If sorting is set to NEW, simply flatten the lists and sort the posts by date
            Sort.NEW -> this.flatten().sortedByDescending { (it as PostChild).data.created }
            // If sorting is set to TOP, simply flatten the lists and sort the posts by score
            Sort.TOP -> this.flatten().sortedByDescending { (it as PostChild).data.score }
            // For all the other sorting methods, interlace the lists to have a consistent result
            // [['a', 'b', 'c'], ['e', 'f', 'g'], ['h', 'i']] ==> ['a', 'e', 'h', 'b', 'f', 'i', 'c', 'g']
            else -> this.interlace()
        }
    }

    companion object {
        private const val REDDIT_SUBREDDIT_LIMIT = 100
        private const val DEFAULT_WEIGHT = 100
    }
}
