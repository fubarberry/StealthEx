package com.cosmos.unreddit.ui.user

import android.os.Bundle
import android.view.View
import androidx.hilt.navigation.fragment.hiltNavGraphViewModels
import androidx.lifecycle.Lifecycle
import androidx.paging.PagingData
import androidx.recyclerview.widget.ItemTouchHelper
import com.cosmos.unreddit.R
import com.cosmos.unreddit.data.model.db.PostEntity
import com.cosmos.unreddit.data.repository.PostListRepository
import com.cosmos.unreddit.ui.common.SwipeActionTouchHelper
import com.cosmos.unreddit.ui.common.fragment.PagingListFragment
import com.cosmos.unreddit.ui.common.widget.CheckableImageView
import com.cosmos.unreddit.ui.postlist.PostListAdapter
import com.cosmos.unreddit.util.extension.launchRepeat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class UserPostFragment : PagingListFragment<PostListAdapter, PostEntity>() {

    override val viewModel: UserViewModel by hiltNavGraphViewModels(R.id.user)

    override val flow: Flow<PagingData<PostEntity>>
        get() = viewModel.postDataFlow

    override val showItemDecoration: Boolean
        get() = true

    @Inject
    lateinit var repository: PostListRepository

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val swipeHandler = SwipeActionTouchHelper(
            context = requireContext(),
            onSwipeLeft = { position ->
                val post = adapter.peek(position)
                if (post != null) {
                    viewModel.hidePost(post)
                }
            },
            onSwipeRight = { position ->
                val post = adapter.peek(position)
                if (post != null) {
                    viewModel.toggleSavePost(post)
                    post.saved = !post.saved
                }
            }
        )
        ItemTouchHelper(swipeHandler).attachToRecyclerView(binding.listContent)
    }

    override fun bindViewModel() {
        super.bindViewModel()
        launchRepeat(Lifecycle.State.STARTED) {
            launch {
                viewModel.contentPreferences.collect {
                    adapter.contentPreferences = it
                }
            }

            launch {
                viewModel.layoutType.collect { layoutType ->
                    adapter.layoutType = layoutType
                    setFullscreenMode(layoutType == 2)
                }
            }

            launch {
                viewModel.leftHandedMode.collect { leftHandedMode ->
                    adapter.leftHandedMode = leftHandedMode
                }
            }

            launch {
                viewModel.lastRefreshPost.collect {
                    setRefreshTime(it)
                }
            }
        }
    }

    override fun onPauseFullscreenMedia() {
        if (isAdapterInitialized) {
            adapter.pausePlayback()
        }
    }

    override fun onResumeFullscreenMedia() {
        if (isAdapterInitialized) {
            adapter.resumePlayback()
        }
    }

    override fun onDestroyView() {
        if (isAdapterInitialized) {
            adapter.releasePlayer()
        }
        super.onDestroyView()
    }

    override fun createPagingAdapter(): PostListAdapter {
        return PostListAdapter(repository, this, this)
    }
}
