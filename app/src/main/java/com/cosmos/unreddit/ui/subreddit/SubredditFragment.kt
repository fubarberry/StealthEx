package com.cosmos.unreddit.ui.subreddit

import android.content.DialogInterface
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.appcompat.widget.PopupMenu
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import com.cosmos.unreddit.R
import com.cosmos.unreddit.ui.common.SwipeActionTouchHelper
import com.cosmos.unreddit.ui.common.widget.CheckableImageView
import com.cosmos.unreddit.data.model.Resource
import com.cosmos.unreddit.data.model.db.PostEntity
import com.cosmos.unreddit.data.model.db.SubredditEntity
import com.cosmos.unreddit.data.repository.PostListRepository
import com.cosmos.unreddit.databinding.DialogMultiredditNameBinding
import com.cosmos.unreddit.databinding.FragmentSubredditBinding
import com.cosmos.unreddit.databinding.LayoutSubredditAboutBinding
import com.cosmos.unreddit.databinding.LayoutSubredditContentBinding
import com.cosmos.unreddit.ui.base.BaseFragment
import com.cosmos.unreddit.ui.common.widget.PullToRefreshLayout
import com.cosmos.unreddit.ui.common.widget.PullToRefreshView
import com.cosmos.unreddit.ui.loadstate.NetworkLoadStateAdapter
import com.cosmos.unreddit.ui.postlist.PostListAdapter
import com.cosmos.unreddit.ui.postmenu.PostMenuFragment
import com.cosmos.unreddit.ui.sort.SortFragment
import com.cosmos.unreddit.ui.layout.LayoutFragment
import com.cosmos.unreddit.util.DateUtil
import com.cosmos.unreddit.util.extension.addLoadStateListener
import com.cosmos.unreddit.util.extension.applyWindowInsets
import com.cosmos.unreddit.util.extension.betterSmoothScrollToPosition
import com.cosmos.unreddit.util.extension.clearSortingListener
import com.cosmos.unreddit.util.extension.clearLayoutListener
import com.cosmos.unreddit.util.extension.clearWindowInsetsListener
import com.cosmos.unreddit.util.extension.launchRepeat
import com.cosmos.unreddit.util.extension.loadSubredditIcon
import com.cosmos.unreddit.util.extension.onRefreshFromNetwork
import com.cosmos.unreddit.util.extension.setSortingListener
import com.cosmos.unreddit.util.extension.setLayoutListener
import com.cosmos.unreddit.util.extension.text
import com.cosmos.unreddit.util.extension.toPixels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SubredditFragment : BaseFragment(), PopupMenu.OnMenuItemClickListener,
    PullToRefreshLayout.OnRefreshListener {

    private var _binding: FragmentSubredditBinding? = null
    private val binding get() = _binding!!

    private var _bindingContent: LayoutSubredditContentBinding? = null
    private val bindingContent get() = _bindingContent!!

    private var _bindingAbout: LayoutSubredditAboutBinding? = null
    private val bindingAbout get() = _bindingAbout!!

    override val viewModel: SubredditViewModel by viewModels()

    private val args: SubredditFragmentArgs by navArgs()

    private lateinit var postListAdapter: PostListAdapter

    private var isSubscribeEnabled: Boolean
        get() = bindingAbout.subredditSubscribeButton.isEnabled
        set(value) {
            bindingAbout.subredditSubscribeButton.isEnabled = value
            bindingAbout.subredditPinButton.isEnabled = value
            bindingAbout.subredditMultiredditButton.isEnabled = value
        }

    @Inject
    lateinit var repository: PostListRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.setSubreddit(args.subreddit.removeSuffix("/"))
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSubredditBinding.inflate(inflater, container, false)
        _bindingContent = binding.subredditContent
        _bindingAbout = binding.subredditAbout
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initResultListener()
        initAppBar()
        initRecyclerView()
        initDrawer()
        bindViewModel()
        bindingAbout.subredditSubscribeButton.setOnClickListener { toggleSubscription() }
        bindingAbout.subredditPinButton.setOnClickListener { togglePin() }
        bindingAbout.subredditMultiredditButton.setOnClickListener { showAddToMultiredditDialog() }
        bindingAbout.subredditMultiredditButton.visibility = View.VISIBLE
        bindingContent.loadingState.infoRetry.setActionClickListener { retry() }

        viewModel.contentLayoutProgress?.let { bindingContent.layoutRoot.progress = it }
        viewModel.drawerContentLayoutProgress?.let { binding.drawerContent.progress = it }
    }

    override fun applyInsets(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { rootView, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

            binding.container.setContentPadding(0, insets.top, 0, 0)

            binding.subredditAbout.subredditName.run {
                updatePadding(
                    paddingLeft,
                    insets.top,
                    paddingRight,
                    paddingBottom
                )
            }

            rootView.clearWindowInsetsListener()

            windowInsets
        }
    }

    private fun bindViewModel() {
        launchRepeat(Lifecycle.State.STARTED) {
            launch {
                combine(viewModel.contentPreferences, viewModel.about) { prefs, about ->
                    if (about.dataValue?.over18 == true) {
                        prefs.copy(showNsfwPreview = true)
                    } else {
                        prefs
                    }
                }.collect {
                    postListAdapter.contentPreferences = it
                }
            }

            launch {
                viewModel.layoutType.collect { layoutType ->
                    postListAdapter.layoutType = layoutType
                    bindingContent.listPost.isFullscreenMode = (layoutType == 2)
                    bindingContent.layoutToggleCard.apply {
                        val iconRes = when (layoutType) {
                            1 -> R.drawable.ic_layout_compact
                            2 -> R.drawable.ic_layout_fullscreen
                            else -> R.drawable.ic_layout_large
                        }
                        val descRes = when (layoutType) {
                            1 -> R.string.layout_compact
                            2 -> R.string.layout_fullscreen
                            else -> R.string.layout_card
                        }
                        setIcon(iconRes)
                        setIconTint(null)
                        contentDescription = getString(descRes)
                    }
                }
            }

            launch {
                viewModel.leftHandedMode.collect { leftHandedMode ->
                    postListAdapter.leftHandedMode = leftHandedMode
                }
            }

            launch {
                viewModel.searchData.collect {
                    bindingContent.loadingState.infoRetry.hide()
                }
            }

            launch {
                viewModel.subreddit.collect { subreddit ->
                    subreddit.takeIf { it.isNotBlank() }?.let {
                        viewModel.loadSubredditInfo(false)
                    }
                }
            }

            launch {
                viewModel.sorting.collect {
                    bindingContent.sortIcon.setSorting(it)
                }
            }

            launch {
                viewModel.postDataFlow.collectLatest {
                    postListAdapter.submitData(it)
                }
            }

            launch {
                viewModel.about.collect {
                    when (it) {
                        is Resource.Success -> bindInfo(it.data)
                        is Resource.Error -> handleError(it.code)
                        is Resource.Loading -> {
                            // ignore
                        }
                    }
                }
            }

            launch {
                viewModel.isSubscribed.collect { isSubscribed ->
                    with(bindingAbout.subredditSubscribeButton) {
                        visibility = View.VISIBLE
                        text = if (isSubscribed) {
                            getString(R.string.subreddit_button_unsubscribe)
                        } else {
                            getString(R.string.subreddit_button_subscribe)
                        }
                    }
                }
            }

            launch {
                viewModel.isPinned.collect { isPinned ->
                    with(bindingAbout.subredditPinButton) {
                        visibility = View.VISIBLE
                        text = if (isPinned) {
                            getString(R.string.subreddit_button_unpin)
                        } else {
                            getString(R.string.subreddit_button_pin)
                        }
                    }
                }
            }

            launch {
                viewModel.isDescriptionCollapsed.collect { isCollapsed ->
                    // TODO: Animate layout changes
                    val maxHeight = if (isCollapsed) {
                        requireContext().toPixels(DESCRIPTION_MAX_HEIGHT).toInt()
                    } else {
                        Integer.MAX_VALUE
                    }
                    ConstraintSet().apply {
                        clone(bindingAbout.layoutRoot)
                        constrainMaxHeight(R.id.subreddit_public_description, maxHeight)
                        applyTo(bindingAbout.layoutRoot)
                    }
                }
            }

            launch {
                viewModel.availableMultireddits.collect {
                    // Keep multireddits StateFlow active while viewing subreddit
                }
            }

            launch {
                viewModel.lastRefresh.collect {
                    val time = getString(R.string.last_refresh, DateUtil.getLocalizedTime(it))
                    (binding.subredditContent.pullRefresh.refreshView as? PullToRefreshView)
                        ?.setLastRefresh(time)
                }
            }
        }
    }

    private fun initRecyclerView() {
        postListAdapter = PostListAdapter(repository, this, this).apply {
            addLoadStateListener(
                bindingContent.listPost,
                bindingContent.loadingState,
                bindingContent.pullRefresh
            ) {
                showRetryBar()
            }
        }
        bindingContent.listPost.apply {
            applyWindowInsets(left = false, top = false, right = false)
            layoutManager = LinearLayoutManager(requireContext())
            adapter = postListAdapter.withLoadStateHeaderAndFooter(
                header = NetworkLoadStateAdapter { postListAdapter.retry() },
                footer = NetworkLoadStateAdapter { postListAdapter.retry() }
            )
        }

        val swipeHandler = SwipeActionTouchHelper(
            context = requireContext(),
            onSwipeLeft = { position ->
                val post = postListAdapter.peek(position)
                if (post != null) {
                    viewModel.hidePost(post)
                }
            },
            onSwipeRight = { position ->
                val post = postListAdapter.peek(position)
                if (post != null) {
                    viewModel.toggleSavePost(post)
                    post.saved = !post.saved
                }
            }
        )
        ItemTouchHelper(swipeHandler).attachToRecyclerView(bindingContent.listPost)

        bindingContent.pullRefresh.setOnRefreshListener(this)

        launchRepeat(Lifecycle.State.STARTED) {
            postListAdapter.onRefreshFromNetwork {
                scrollToTop()
            }
        }
    }

    private fun initDrawer() {
        with(binding.drawerLayout) {
            setScrimColor(Color.TRANSPARENT)
            drawerElevation = 0F
        }
        bindingAbout.subredditPublicDescription.setOnClickListener {
            viewModel.toggleDescriptionCollapsed()
        }
    }

    private fun initAppBar() {
        with(bindingContent) {
            sortCard.setOnClickListener { showSortDialog() }
            backCard.setOnClickListener { onBackPressed() }
            moreCard.setOnClickListener { showMenu() }
            infoCard.setOnClickListener { openDrawer() }
            subredditName.setOnClickListener { scrollToTop() }
            subredditImage.setOnClickListener { scrollToTop() }
            layoutToggleCard.setOnClickListener {
                showLayoutDialog()
            }
        }
    }

    private fun initResultListener() {
        setSortingListener { sorting -> sorting?.let { viewModel.setSorting(it) } }
        setLayoutListener { layout ->
            layout?.let {
                viewModel.setLayoutType(it)
                val message = when (it) {
                    1 -> R.string.layout_compact
                    2 -> R.string.layout_fullscreen
                    else -> R.string.layout_card
                }
                android.widget.Toast.makeText(requireContext(), message, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showLayoutDialog() {
        viewLifecycleOwner.lifecycleScope.launch {
            val currentLayoutType = viewModel.layoutType.first()
            LayoutFragment.show(childFragmentManager, currentLayoutType)
        }
    }

    private fun bindInfo(about: SubredditEntity) {
        viewModel.isSubredditReachable = true
        with(about) {
            bindingContent.subreddit = this
            bindingAbout.subreddit = this

            bindingContent.subredditImage.loadSubredditIcon(icon)

            if (publicDescription.isNotEmpty()) {
                bindingAbout.subredditPublicDescription.apply {
                    setText(publicDescription)
                    setOnLinkClickListener(this@SubredditFragment)
                }
            } else {
                bindingAbout.subredditPublicDescription.visibility = View.GONE
            }

            if (description.isNotEmpty()) {
                bindingAbout.subredditDescription.apply {
                    setText(description)
                    setOnLinkClickListener(this@SubredditFragment)
                }
            }

            isSubscribeEnabled = true
        }
    }

    private fun handleError(code: Int?) {
        isSubscribeEnabled = true
        when (code) {
            403 -> {
                viewModel.isSubredditReachable = false
                showUnauthorizedDialog()
            }
            404 -> {
                viewModel.isSubredditReachable = false
                showNotFoundDialog()
            }
            else -> showRetryBar()
        }
    }

    private fun retry() {
        if (viewModel.about.value is Resource.Error) {
            viewModel.loadSubredditInfo(true)
        }

        postListAdapter.retry() // TODO: Don't retry if not necessary
    }

    private fun showRetryBar() {
        if (!bindingContent.loadingState.infoRetry.isVisible) {
            bindingContent.loadingState.infoRetry.show()
        }
    }

    private fun scrollToTop() {
        bindingContent.listPost.betterSmoothScrollToPosition(0)
    }

    private fun showSearchFragment() {
        navigate(
            SubredditFragmentDirections.openSearch(
                viewModel.subreddit.value,
                viewModel.about.value.dataValue?.icon,
                viewModel.about.value.dataValue?.over18 == true
            )
        )
    }

    private fun showSortDialog() {
        SortFragment.show(childFragmentManager, viewModel.sorting.value)
    }

    private fun showNotFoundDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_subreddit_not_found_title)
            .setMessage(R.string.dialog_subreddit_not_found_body)
            .setPositiveButton(R.string.dialog_ok) { dialog, _ -> dialog.handleUserAcknowledgement() }
            .setCancelable(false)
            .show()
    }

    private fun showUnauthorizedDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_subreddit_unauthorized_title)
            .setMessage(R.string.dialog_subreddit_unauthorized_body)
            .setPositiveButton(R.string.dialog_ok) { dialog, _ -> dialog.handleUserAcknowledgement() }
            .setCancelable(false)
            .show()
    }

    private fun toggleSubscription() {
        if (viewModel.isSubscribed.value) {
            val name = viewModel.about.value.dataValue?.displayName ?: viewModel.subreddit.value
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.dialog_unsubscribe_title)
                .setMessage(getString(R.string.dialog_unsubscribe_body, name))
                .setPositiveButton(R.string.subreddit_button_unsubscribe) { _, _ ->
                    if (!viewModel.isSubredditReachable) {
                        isSubscribeEnabled = false
                    }
                    viewModel.toggleSubscription()
                }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
        } else {
            if (!viewModel.isSubredditReachable) {
                isSubscribeEnabled = false
            }
            viewModel.toggleSubscription()
        }
    }

    private fun togglePin() {
        if (!viewModel.isSubredditReachable) {
            isSubscribeEnabled = false
        }
        viewModel.togglePin()
    }

    private fun showAddToMultiredditDialog() {
        val subredditName = viewModel.getCurrentSubredditName()
        if (subredditName.isBlank()) return

        val multireddits = viewModel.availableMultireddits.value
        if (multireddits.isEmpty()) {
            showCreateMultiredditDialog()
            return
        }

        val names = multireddits.map { it.multireddit.name }.toTypedArray()
        val checked = multireddits.map { it.containsSubreddit(subredditName) }.toBooleanArray()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.dialog_add_to_multireddit_title, subredditName))
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(R.string.dialog_multireddit_save_button) { _, _ ->
                val selectedIds = multireddits
                    .filterIndexed { index, _ -> checked[index] }
                    .map { it.multireddit.id }
                    .toSet()
                viewModel.updateMultiredditMembership(selectedIds)
            }
            .setNeutralButton(R.string.dialog_new_multireddit_button) { _, _ ->
                showCreateMultiredditDialog()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun showCreateMultiredditDialog() {
        val dialogBinding = DialogMultiredditNameBinding.inflate(requireActivity().layoutInflater)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_create_multireddit_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.dialog_multireddit_create_button, null)
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()

        val submit = {
            val name = dialogBinding.inputName.text().toString().trim()
            if (name.isBlank()) {
                dialogBinding.inputName.error = getString(R.string.dialog_multireddit_name_blank_error)
            } else {
                viewModel.createMultiredditWithSubreddit(name)
                dialog.dismiss()
            }
        }

        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener { submit() }
        dialogBinding.editName.setOnEditorActionListener { _, actionId, event ->
            val isEnter = event != null &&
                event.action == KeyEvent.ACTION_DOWN &&
                (event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
            if (actionId == EditorInfo.IME_ACTION_DONE || isEnter) {
                submit()
                true
            } else {
                false
            }
        }
    }

    private fun showMenu() {
        PopupMenu(requireContext(), binding.subredditContent.moreCard)
            .apply {
                menuInflater.inflate(R.menu.subreddit_menu, this.menu)
                menu.findItem(R.id.subscribe)?.apply {
                    isEnabled = isSubscribeEnabled
                    title = if (viewModel.isSubscribed.value) {
                        getString(R.string.subreddit_button_unsubscribe)
                    } else {
                        getString(R.string.subreddit_button_subscribe)
                    }
                }
                menu.findItem(R.id.pin)?.apply {
                    isEnabled = isSubscribeEnabled
                    title = if (viewModel.isPinned.value) {
                        getString(R.string.subreddit_button_unpin)
                    } else {
                        getString(R.string.subreddit_button_pin)
                    }
                }
                menu.findItem(R.id.multireddit)?.apply {
                    isEnabled = isSubscribeEnabled
                }
                setOnMenuItemClickListener(this@SubredditFragment)
            }
            .show()
    }

    private fun openDrawer() {
        binding.drawerLayout.openDrawer(GravityCompat.END)
    }

    private fun DialogInterface.handleUserAcknowledgement() {
        if (viewModel.isSubscribed.value || viewModel.isPinned.value) {
            // Allow the user to unsubscribe or unpin
            dismiss()
        } else {
            onBackPressed()
        }
    }

    override fun onMenuItemClick(menuItem: MenuItem): Boolean {
        when (menuItem.itemId) {
            R.id.search -> showSearchFragment()
            R.id.subscribe -> toggleSubscription()
            R.id.pin -> togglePin()
            R.id.multireddit -> showAddToMultiredditDialog()
            R.id.sidebar -> openDrawer()
            else -> {
                return false
            }
        }
        return true
    }

    override fun onRefresh() {
        postListAdapter.refresh()
    }

    override fun onBackPressed() {
        if (binding.drawerLayout.isDrawerOpen(GravityCompat.END)) {
            binding.drawerLayout.closeDrawer(GravityCompat.END)
        } else {
            super.onBackPressed()
        }
    }

    override fun onPauseFullscreenMedia() {
        if (::postListAdapter.isInitialized) {
            postListAdapter.pausePlayback()
        }
    }

    override fun onResumeFullscreenMedia() {
        if (_binding != null && ::postListAdapter.isInitialized) {
            postListAdapter.resumePlayback()
        }
    }

    override fun onDestroyView() {
        if (::postListAdapter.isInitialized) {
            postListAdapter.releasePlayer()
        }
        super.onDestroyView()

        (binding.subredditContent.pullRefresh.refreshView as? PullToRefreshLayout.RefreshCallback)
            ?.reset()

        // Save progress of MotionLayout to restore it in case of fragment recreation
        // currentState is not always properly updated
        viewModel.contentLayoutProgress = bindingContent.layoutRoot.progress
        viewModel.drawerContentLayoutProgress = binding.drawerContent.progress

        clearSortingListener()
        clearLayoutListener()

        _binding = null
        _bindingContent = null
        _bindingAbout = null
    }

    override fun onLongClick(post: PostEntity) {
        PostMenuFragment.show(parentFragmentManager, post, PostMenuFragment.MenuType.SUBREDDIT)
    }

    override fun onMenuClick(post: PostEntity) {
        PostMenuFragment.show(parentFragmentManager, post, PostMenuFragment.MenuType.SUBREDDIT)
    }

    companion object {
        private const val DESCRIPTION_MAX_HEIGHT = 200F
    }
}
