package com.cosmos.unreddit.ui.multireddit

import android.content.DialogInterface
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.cosmos.unreddit.R
import com.cosmos.unreddit.data.model.db.PostEntity
import com.cosmos.unreddit.data.model.db.Subscription
import com.cosmos.unreddit.data.repository.PostListRepository
import com.cosmos.unreddit.databinding.DialogEditMultiredditSubredditsBinding
import com.cosmos.unreddit.databinding.DialogFeedMixBinding
import com.cosmos.unreddit.databinding.DialogMultiredditNameBinding
import com.cosmos.unreddit.databinding.FragmentMultiredditBinding
import com.cosmos.unreddit.databinding.ItemFeedMixBinding
import com.cosmos.unreddit.ui.base.BaseFragment
import com.cosmos.unreddit.ui.common.SwipeActionTouchHelper
import com.cosmos.unreddit.ui.common.widget.PullToRefreshLayout
import com.cosmos.unreddit.ui.common.widget.PullToRefreshView
import com.cosmos.unreddit.ui.layout.LayoutFragment
import com.cosmos.unreddit.ui.loadstate.NetworkLoadStateAdapter
import com.cosmos.unreddit.ui.postlist.PostListAdapter
import com.cosmos.unreddit.ui.postmenu.PostMenuFragment
import com.cosmos.unreddit.ui.sort.SortFragment
import com.cosmos.unreddit.util.DateUtil
import com.cosmos.unreddit.util.extension.addLoadStateListener
import com.cosmos.unreddit.util.extension.applyWindowInsets
import com.cosmos.unreddit.util.extension.betterSmoothScrollToPosition
import com.cosmos.unreddit.util.extension.clearLayoutListener
import com.cosmos.unreddit.util.extension.clearSortingListener
import com.cosmos.unreddit.util.extension.launchRepeat
import com.cosmos.unreddit.util.extension.onRefreshFromNetwork
import com.cosmos.unreddit.util.extension.setLayoutListener
import com.cosmos.unreddit.util.extension.setSortingListener
import com.cosmos.unreddit.util.extension.text
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MultiredditFragment : BaseFragment(), PopupMenu.OnMenuItemClickListener,
    PullToRefreshLayout.OnRefreshListener {

    private var _binding: FragmentMultiredditBinding? = null
    private val binding get() = _binding!!

    override val viewModel: MultiredditViewModel by viewModels()

    private val args: MultiredditFragmentArgs by navArgs()

    private lateinit var postListAdapter: PostListAdapter

    @Inject
    lateinit var repository: PostListRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.setMultiredditId(args.multiredditId)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMultiredditBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initResultListener()
        initAppBar()
        initRecyclerView()
        bindViewModel()
        binding.loadingState.infoRetry.setActionClickListener { postListAdapter.retry() }
    }

    private fun initResultListener() {
        setSortingListener { sorting -> sorting?.let { viewModel.setSorting(it) } }
        setLayoutListener { layoutType -> layoutType?.let { viewModel.setLayoutType(it) } }
    }

    private fun initAppBar() {
        with(binding.appBar) {
            backCard.setOnClickListener { onBackPressed() }
            label.setOnClickListener { scrollToTop() }
            sortCard.setOnClickListener { showSortDialog() }
            moreCard.setOnClickListener { showMenu() }
            layoutToggleCard.setOnClickListener {
                lifecycleScope.launch {
                    val currentType = viewModel.layoutType.first()
                    LayoutFragment.show(childFragmentManager, currentType)
                }
            }
        }
    }

    private fun initRecyclerView() {
        postListAdapter = PostListAdapter(repository, this, this).apply {
            addLoadStateListener(
                binding.listPost,
                binding.loadingState,
                binding.pullRefresh
            ) {
                showRetryBar()
            }
        }

        binding.listPost.apply {
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
        ItemTouchHelper(swipeHandler).attachToRecyclerView(binding.listPost)

        binding.pullRefresh.setOnRefreshListener(this)

        launchRepeat(Lifecycle.State.STARTED) {
            postListAdapter.onRefreshFromNetwork {
                scrollToTop()
            }
        }
    }

    private fun bindViewModel() {
        launchRepeat(Lifecycle.State.STARTED) {
            launch {
                viewModel.multireddit.collect { multi ->
                    if (multi != null) {
                        binding.appBar.label.text = multi.multireddit.name
                        val isEmpty = multi.subreddits.isEmpty()
                        binding.textEmptySubreddits.isVisible = isEmpty
                        if (isEmpty) {
                            binding.loadingState.loadingCradle.isVisible = false
                            binding.loadingState.infoRetry.hide()
                        }
                    }
                }
            }

            launch {
                viewModel.contentPreferences.collect {
                    postListAdapter.contentPreferences = it
                }
            }

            launch {
                viewModel.layoutType.collect { layoutType ->
                    postListAdapter.layoutType = layoutType
                    binding.listPost.isFullscreenMode = (layoutType == 2)
                    binding.appBar.layoutToggleCard.apply {
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
                viewModel.sorting.collect {
                    binding.appBar.sortIcon.setSorting(it)
                }
            }

            launch {
                viewModel.postDataFlow.collectLatest {
                    postListAdapter.submitData(it)
                }
            }

            launch {
                viewModel.lastRefresh.collect {
                    val time = getString(R.string.last_refresh, DateUtil.getLocalizedTime(it))
                    (binding.pullRefresh.refreshView as? PullToRefreshView)?.setLastRefresh(time)
                }
            }
        }
    }

    private fun showMenu() {
        PopupMenu(requireContext(), binding.appBar.moreCard)
            .apply {
                menuInflater.inflate(R.menu.multireddit_menu, this.menu)
                this.menu.findItem(R.id.feed_mix)?.isEnabled =
                    !viewModel.multireddit.value?.subreddits.isNullOrEmpty()
                setOnMenuItemClickListener(this@MultiredditFragment)
            }
            .show()
    }

    override fun onMenuItemClick(menuItem: MenuItem): Boolean {
        return when (menuItem.itemId) {
            R.id.feed_mix -> {
                showFeedMixDialog()
                true
            }
            R.id.edit_subreddits -> {
                showEditSubredditsDialog()
                true
            }
            R.id.rename -> {
                showRenameDialog()
                true
            }
            R.id.delete -> {
                showDeleteDialog()
                true
            }
            else -> false
        }
    }

    private fun showFeedMixDialog() {
        val multi = viewModel.multireddit.value ?: return
        val communities = multi.subreddits.sortedBy { it.subreddit.lowercase() }
        if (communities.isEmpty()) return

        val workingWeights = LinkedHashMap<String, Int>(communities.size).apply {
            communities.forEach { entry ->
                val snapped = ((entry.weight + 12) / 25) * 25
                put(
                    entry.subreddit,
                    snapped.coerceIn(Subscription.MIN_HOME_WEIGHT, Subscription.MAX_HOME_WEIGHT)
                )
            }
        }

        val dialogBinding = DialogFeedMixBinding.inflate(requireActivity().layoutInflater)
        dialogBinding.textSubtitle.setText(R.string.dialog_multireddit_feed_mix_subtitle)

        val mixAdapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<FeedMixViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FeedMixViewHolder {
                return FeedMixViewHolder(
                    ItemFeedMixBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                )
            }

            override fun onBindViewHolder(holder: FeedMixViewHolder, position: Int) {
                val entry = communities[position]
                val currentWeight = workingWeights[entry.subreddit] ?: Subscription.DEFAULT_HOME_WEIGHT
                holder.bind(entry.subreddit, currentWeight) { newWeight ->
                    workingWeights[entry.subreddit] = newWeight
                }
            }

            override fun getItemCount(): Int = communities.size
        }

        dialogBinding.listFeedMix.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = mixAdapter
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_multireddit_feed_mix_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.dialog_multireddit_save_button) { _, _ ->
                viewModel.saveSubredditWeights(workingWeights)
            }
            .setNeutralButton(R.string.dialog_feed_mix_reset, null)
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()

        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
            communities.forEach { entry ->
                workingWeights[entry.subreddit] = Subscription.DEFAULT_HOME_WEIGHT
            }
            mixAdapter.notifyDataSetChanged()
        }
    }

    private class FeedMixViewHolder(
        private val binding: ItemFeedMixBinding
    ) : androidx.recyclerview.widget.RecyclerView.ViewHolder(binding.root) {

        fun bind(subredditName: String, weight: Int, onWeightChanged: (Int) -> Unit) {
            binding.textSubreddit.text = "r/$subredditName"
            binding.textWeight.text = formatWeight(weight)
            binding.sliderWeight.clearOnChangeListeners()
            binding.sliderWeight.value = weight.toFloat()
            binding.sliderWeight.addOnChangeListener { _, value, _ ->
                val intWeight = value.toInt()
                binding.textWeight.text = formatWeight(intWeight)
                onWeightChanged(intWeight)
            }
        }

        private fun formatWeight(weight: Int): String {
            val context = binding.root.context
            return if (weight <= 0) {
                context.getString(R.string.feed_mix_off)
            } else {
                context.getString(R.string.feed_mix_percent, weight)
            }
        }
    }

    private fun showRenameDialog() {
        val currentName = viewModel.multireddit.value?.multireddit?.name ?: return
        val dialogBinding = DialogMultiredditNameBinding.inflate(requireActivity().layoutInflater)
        dialogBinding.editName.setText(currentName)
        dialogBinding.editName.setSelection(currentName.length)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_rename_multireddit_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.dialog_multireddit_save_button, null)
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()

        val submit = {
            val newName = dialogBinding.inputName.text().toString().trim()
            if (newName.isBlank()) {
                dialogBinding.inputName.error = getString(R.string.dialog_multireddit_name_blank_error)
            } else {
                viewModel.renameMultireddit(newName)
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

    private fun showEditSubredditsDialog() {
        val currentSubreddits = viewModel.multireddit.value?.subredditNames ?: emptyList()
        val subscribedNames = viewModel.subscribedAndPinnedNames.value

        val allItems = mutableListOf<String>()
        val selectedLower = currentSubreddits.map { it.lowercase() }.toMutableSet()

        currentSubreddits.forEach { allItems.add(it) }
        subscribedNames.forEach { sub ->
            if (allItems.none { it.equals(sub, ignoreCase = true) }) {
                allItems.add(sub)
            }
        }

        val dialogBinding = DialogEditMultiredditSubredditsBinding.inflate(requireActivity().layoutInflater)
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_list_item_multiple_choice,
            allItems
        )
        dialogBinding.listSubreddits.adapter = adapter

        fun syncCheckedStates() {
            for (i in allItems.indices) {
                dialogBinding.listSubreddits.setItemChecked(
                    i,
                    selectedLower.contains(allItems[i].lowercase())
                )
            }
        }
        syncCheckedStates()

        dialogBinding.listSubreddits.setOnItemClickListener { _, _, position, _ ->
            val sub = allItems[position]
            val lower = sub.lowercase()
            if (dialogBinding.listSubreddits.isItemChecked(position)) {
                selectedLower.add(lower)
            } else {
                selectedLower.remove(lower)
            }
        }

        val addSubredditAction = {
            val raw = dialogBinding.inputAddSubreddit.text().toString()
            val cleaned = normalizeSubredditName(raw)
            if (cleaned.isBlank()) {
                dialogBinding.inputAddSubreddit.error = getString(R.string.subreddit_blank_error)
            } else {
                dialogBinding.inputAddSubreddit.error = null
                val existingIdx = allItems.indexOfFirst { it.equals(cleaned, ignoreCase = true) }
                if (existingIdx >= 0) {
                    selectedLower.add(allItems[existingIdx].lowercase())
                    dialogBinding.listSubreddits.setItemChecked(existingIdx, true)
                    dialogBinding.listSubreddits.smoothScrollToPosition(existingIdx)
                } else {
                    allItems.add(0, cleaned)
                    selectedLower.add(cleaned.lowercase())
                    adapter.notifyDataSetChanged()
                    syncCheckedStates()
                    dialogBinding.listSubreddits.smoothScrollToPosition(0)
                }
                dialogBinding.editAddSubreddit.setText("")
            }
        }

        dialogBinding.buttonAddSubreddit.setOnClickListener { addSubredditAction() }
        dialogBinding.editAddSubreddit.setOnEditorActionListener { _, actionId, event ->
            val isEnter = event != null &&
                event.action == KeyEvent.ACTION_DOWN &&
                (event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
            if (actionId == EditorInfo.IME_ACTION_DONE || isEnter) {
                addSubredditAction()
                true
            } else {
                false
            }
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_edit_multireddit_subreddits_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.dialog_multireddit_save_button) { _, _ ->
                val chosen = allItems.filter { selectedLower.contains(it.lowercase()) }
                viewModel.updateSubreddits(chosen)
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun showDeleteDialog() {
        val name = viewModel.multireddit.value?.multireddit?.name ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_delete_multireddit_title)
            .setMessage(getString(R.string.dialog_delete_multireddit_body, name))
            .setPositiveButton(R.string.dialog_delete_multireddit_button) { _, _ ->
                viewModel.deleteMultireddit {
                    onBackPressed()
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun normalizeSubredditName(input: String): String {
        return input.trim()
            .removePrefix("/")
            .let { if (it.startsWith("r/", ignoreCase = true)) it.substring(2) else it }
            .trim('/')
            .trim()
    }

    private fun showSortDialog() {
        SortFragment.show(childFragmentManager, viewModel.sorting.value)
    }

    private fun showRetryBar() {
        if (!binding.loadingState.infoRetry.isVisible && viewModel.multireddit.value?.subreddits?.isNotEmpty() == true) {
            binding.loadingState.infoRetry.show()
        }
    }

    private fun scrollToTop() {
        binding.listPost.betterSmoothScrollToPosition(0)
    }

    override fun onRefresh() {
        postListAdapter.refresh()
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

    override fun onLongClick(post: PostEntity) {
        PostMenuFragment.show(parentFragmentManager, post, PostMenuFragment.MenuType.GENERAL)
    }

    override fun onDestroyView() {
        if (::postListAdapter.isInitialized) {
            postListAdapter.releasePlayer()
        }
        super.onDestroyView()
        (binding.pullRefresh.refreshView as? PullToRefreshLayout.RefreshCallback)?.reset()
        clearSortingListener()
        clearLayoutListener()
        _binding = null
    }
}
