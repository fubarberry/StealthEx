package com.cosmos.unreddit.ui.user

import androidx.fragment.app.FragmentTransaction
import androidx.hilt.navigation.fragment.hiltNavGraphViewModels
import androidx.lifecycle.Lifecycle
import androidx.paging.PagingData
import com.cosmos.unreddit.R
import com.cosmos.unreddit.data.model.Comment
import com.cosmos.unreddit.ui.commentmenu.CommentMenuFragment
import com.cosmos.unreddit.ui.common.fragment.PagingListFragment
import com.cosmos.unreddit.ui.postdetails.PostDetailsFragment
import com.cosmos.unreddit.data.repository.PreferencesRepository
import com.cosmos.unreddit.util.Util
import com.cosmos.unreddit.util.extension.launchRepeat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

@AndroidEntryPoint
class UserCommentFragment : PagingListFragment<UserCommentsAdapter, Comment>(),
    UserCommentsAdapter.CommentClickListener {

    override val viewModel: UserViewModel by hiltNavGraphViewModels(R.id.user)

    override val flow: Flow<PagingData<Comment>>
        get() = viewModel.commentDataFlow

    override val showItemDecoration: Boolean
        get() = true

    override fun bindViewModel() {
        super.bindViewModel()
        launchRepeat(Lifecycle.State.STARTED) {
            launch {
                viewModel.lastRefreshComment.collect {
                    setRefreshTime(it)
                }
            }
        }
    }

    @Inject
    lateinit var preferencesRepository: PreferencesRepository

    override fun createPagingAdapter(): UserCommentsAdapter {
        val commentImageMode = runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            preferencesRepository.getCommentImageMode().first()
        }
        val isExpandedDefault = when (commentImageMode) {
            1 -> true
            2 -> Util.isWifiConnected(requireContext())
            else -> false
        }
        return UserCommentsAdapter(requireContext(), isExpandedDefault, this, this)
    }

    override fun onClick(comment: Comment.CommentEntity) {
        requireActivity().supportFragmentManager.beginTransaction()
            .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
            .add(
                R.id.fragment_container,
                PostDetailsFragment.newInstance(comment.permalink),
                PostDetailsFragment.TAG
            )
            .addToBackStack(null)
            .commit()
    }

    override fun onLongClick(comment: Comment.CommentEntity) {
        CommentMenuFragment.show(childFragmentManager, comment, CommentMenuFragment.MenuType.USER)
    }
}
