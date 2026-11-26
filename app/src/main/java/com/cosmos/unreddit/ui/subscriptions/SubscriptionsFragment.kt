package com.cosmos.unreddit.ui.subscriptions

import android.content.DialogInterface
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.cosmos.unreddit.NavigationGraphDirections
import com.cosmos.unreddit.R
import com.cosmos.unreddit.data.model.db.MultiredditWithSubreddits
import com.cosmos.unreddit.data.model.db.Subscription
import com.cosmos.unreddit.databinding.DialogFeedMixBinding
import com.cosmos.unreddit.databinding.DialogGoToSubredditBinding
import com.cosmos.unreddit.databinding.DialogMultiredditNameBinding
import com.cosmos.unreddit.databinding.FragmentSubscriptionsBinding
import com.cosmos.unreddit.databinding.ItemFeedMixBinding
import com.cosmos.unreddit.ui.base.BaseFragment
import com.cosmos.unreddit.util.SearchUtil
import com.cosmos.unreddit.util.extension.applyWindowInsets
import com.cosmos.unreddit.util.extension.hideSoftKeyboard
import com.cosmos.unreddit.util.extension.text
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SubscriptionsFragment : BaseFragment() {

    private var _binding: FragmentSubscriptionsBinding? = null
    private val binding get() = _binding!!

    override val viewModel: SubscriptionsViewModel by activityViewModels()

    private lateinit var subscriptionsAdapter: SubscriptionsAdapter
    private lateinit var multiredditsAdapter: MultiredditsAdapter

    private var latestSubscriptions: List<SubscriptionListItem> = emptyList()
    private var latestSubscribedCommunities: List<Subscription> = emptyList()
    private var latestMultireddits: List<MultiredditWithSubreddits> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSubscriptionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initAppBar()
        initTabs()
        initRecyclerView()
        bindViewModel()
    }

    override fun onResume() {
        super.onResume()
        binding.appBar.searchInput.text?.firstOrNull()?.let {
            showSearchInput(true)
        }
    }

    private fun initTabs() {
        binding.tabs.apply {
            removeAllTabs()
            addTab(newTab().setText(R.string.tab_subscriptions_subreddits))
            addTab(newTab().setText(R.string.tab_subscriptions_multireddits))
            val initialIndex = if (viewModel.selectedTab.value == SubscriptionsViewModel.Tab.MULTIREDDITS) 1 else 0
            getTabAt(initialIndex)?.select()
            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    val selected = if (tab.position == 1) {
                        SubscriptionsViewModel.Tab.MULTIREDDITS
                    } else {
                        SubscriptionsViewModel.Tab.SUBREDDITS
                    }
                    viewModel.setSelectedTab(selected)
                }

                override fun onTabUnselected(tab: TabLayout.Tab) = Unit
                override fun onTabReselected(tab: TabLayout.Tab) = Unit
            })
        }
    }

    private fun bindViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            launch {
                viewModel.filteredSubscriptions
                    .flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
                    .collect { subscriptions ->
                        latestSubscriptions = subscriptions
                        subscriptionsAdapter.submitList(subscriptions)
                        updateEmptyState()
                    }
            }

            launch {
                viewModel.subscribedCommunities
                    .flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
                    .collect { subscribed ->
                        latestSubscribedCommunities = subscribed
                    }
            }

            launch {
                viewModel.filteredMultireddits
                    .flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
                    .collect { multireddits ->
                        latestMultireddits = multireddits
                        multiredditsAdapter.submitList(multireddits)
                        updateEmptyState()
                    }
            }

            launch {
                viewModel.selectedTab
                    .flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
                    .collect { tab ->
                        applySelectedTab(tab)
                    }
            }
        }
    }

    private fun applySelectedTab(tab: SubscriptionsViewModel.Tab) {
        val targetAdapter = when (tab) {
            SubscriptionsViewModel.Tab.SUBREDDITS -> subscriptionsAdapter
            SubscriptionsViewModel.Tab.MULTIREDDITS -> multiredditsAdapter
        }
        if (binding.listSubscriptions.adapter !== targetAdapter) {
            binding.listSubscriptions.adapter = targetAdapter
        }
        if (!binding.appBar.searchInput.isVisible) {
            binding.appBar.newMultiredditCard.isVisible =
                tab == SubscriptionsViewModel.Tab.MULTIREDDITS
        }
        updateEmptyState()
    }

    private fun updateEmptyState() {
        if (!binding.appBar.searchInput.isQueryEmpty()) {
            binding.emptyData.isVisible = false
            binding.textEmptyData.isVisible = false
            return
        }
        when (viewModel.selectedTab.value) {
            SubscriptionsViewModel.Tab.SUBREDDITS -> {
                val isEmpty = latestSubscriptions.isEmpty()
                binding.emptyData.isVisible = isEmpty
                binding.textEmptyData.isVisible = isEmpty
                binding.textEmptyData.setText(R.string.empty_data_subscriptions)
            }
            SubscriptionsViewModel.Tab.MULTIREDDITS -> {
                val isEmpty = latestMultireddits.isEmpty()
                binding.emptyData.isVisible = isEmpty
                binding.textEmptyData.isVisible = isEmpty
                binding.textEmptyData.setText(R.string.empty_data_multireddits)
            }
        }
    }

    private fun initRecyclerView() {
        subscriptionsAdapter = SubscriptionsAdapter(
            listener = { onClick(it) },
            onPinClick = { viewModel.togglePin(it) },
            onSubscribeClick = { onSubscribeToggle(it) },
            onFeedMixClick = { showFeedMixDialog() }
        )
        multiredditsAdapter = MultiredditsAdapter { multireddit ->
            onMultiredditClick(multireddit)
        }
        binding.listSubscriptions.apply {
            applyWindowInsets(left = false, top = false, right = false)
            layoutManager = LinearLayoutManager(requireContext())
            adapter = if (viewModel.selectedTab.value == SubscriptionsViewModel.Tab.MULTIREDDITS) {
                multiredditsAdapter
            } else {
                subscriptionsAdapter
            }
        }
    }

    private fun onSubscribeToggle(subscription: Subscription) {
        if (subscription.isSubscribed) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.dialog_unsubscribe_title)
                .setMessage(getString(R.string.dialog_unsubscribe_body, subscription.name))
                .setPositiveButton(R.string.subreddit_button_unsubscribe) { _, _ ->
                    viewModel.unsubscribe(subscription)
                }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
        } else {
            viewModel.subscribe(subscription)
        }
    }

    private fun showFeedMixDialog() {
        val communities = latestSubscribedCommunities
        if (communities.isEmpty()) return

        val workingWeights = LinkedHashMap<String, Int>(communities.size).apply {
            communities.forEach { sub ->
                val snapped = ((sub.homeWeight + 12) / 25) * 25
                put(sub.name, snapped.coerceIn(Subscription.MIN_HOME_WEIGHT, Subscription.MAX_HOME_WEIGHT))
            }
        }

        val dialogBinding = DialogFeedMixBinding.inflate(requireActivity().layoutInflater)
        val mixAdapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<FeedMixViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FeedMixViewHolder {
                return FeedMixViewHolder(
                    ItemFeedMixBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                )
            }

            override fun onBindViewHolder(holder: FeedMixViewHolder, position: Int) {
                val sub = communities[position]
                val currentWeight = workingWeights[sub.name] ?: Subscription.DEFAULT_HOME_WEIGHT
                holder.bind(sub.name, currentWeight) { newWeight ->
                    workingWeights[sub.name] = newWeight
                }
            }

            override fun getItemCount(): Int = communities.size
        }

        dialogBinding.listFeedMix.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = mixAdapter
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_feed_mix_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.dialog_multireddit_save_button) { _, _ ->
                viewModel.saveHomeFeedWeights(workingWeights)
            }
            .setNeutralButton(R.string.dialog_feed_mix_reset, null)
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()

        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
            communities.forEach { sub ->
                workingWeights[sub.name] = Subscription.DEFAULT_HOME_WEIGHT
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

    private fun initAppBar() {
        with(binding.appBar) {
            newMultiredditCard.setOnClickListener { showCreateMultiredditDialog() }
            goToSubredditCard.setOnClickListener { showGoToSubredditDialog() }
            searchCard.setOnClickListener { showSearchInput(true) }
            cancelCard.setOnClickListener {
                showSearchInput(false)
                binding.appBar.searchInput.clear()
            }
            searchInput.apply {
                addTarget(label)
                addTarget(newMultiredditCard)
                addTarget(goToSubredditCard)
                addTarget(searchCard)
                addTarget(cancelCard)
                doOnTextChanged { text, _, _, _ ->
                    viewModel.setSearchQuery(text.toString())
                }
                setSearchActionListener {
                    handleSearchAction(it)
                }
            }
        }
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
                viewModel.createMultireddit(name)
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

    private fun showGoToSubredditDialog() {
        val dialogBinding = DialogGoToSubredditBinding.inflate(requireActivity().layoutInflater)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogBinding.root)
            .setTitle(R.string.dialog_go_to_subreddit_title)
            .setPositiveButton(R.string.dialog_go_to_subreddit_button, null)
            .setNeutralButton(R.string.dialog_cancel) { dialogInterface, _ ->
                dialogInterface.dismiss()
            }
            .show()

        val submitAction = {
            val rawInput = dialogBinding.inputSubreddit.text().toString()
            val cleanSubreddit = normalizeSubredditName(rawInput)
            if (cleanSubreddit.isBlank()) {
                dialogBinding.inputSubreddit.error = getString(R.string.subreddit_blank_error)
            } else {
                dialog.dismiss()
                onClick(cleanSubreddit)
            }
        }

        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            submitAction()
        }

        dialogBinding.editSubreddit.setOnEditorActionListener { _, actionId, event ->
            val isEnterKey = event != null &&
                event.action == KeyEvent.ACTION_DOWN &&
                (event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE || isEnterKey) {
                submitAction()
                true
            } else {
                false
            }
        }
    }

    private fun normalizeSubredditName(input: String): String {
        return input.trim()
            .removePrefix("/")
            .let { if (it.startsWith("r/", ignoreCase = true)) it.substring(2) else it }
            .trim('/')
            .trim()
    }

    private fun showSearchInput(show: Boolean) {
        binding.appBar.searchInput.show(binding.appBar.root, show) {
            with(binding.appBar) {
                label.isVisible = !show
                newMultiredditCard.isVisible =
                    !show && viewModel.selectedTab.value == SubscriptionsViewModel.Tab.MULTIREDDITS
                goToSubredditCard.isVisible = !show
                searchCard.isVisible = !show
                cancelCard.isVisible = show
            }
        }
    }

    private fun showSearchFragment(query: String) {
        binding.appBar.searchInput.hideSoftKeyboard()

        navigate(SubscriptionsFragmentDirections.openSearch(query))

        binding.appBar.searchInput.clear()
    }

    private fun onClick(subreddit: String) {
        navigate(NavigationGraphDirections.openSubreddit(subreddit))
    }

    private fun onMultiredditClick(multireddit: MultiredditWithSubreddits) {
        navigate(NavigationGraphDirections.openMultireddit(multireddit.multireddit.id))
    }

    private fun handleSearchAction(query: String) {
        if (SearchUtil.isQueryValid(query)) {
            showSearchFragment(query)
        }
    }

    override fun onBackPressed() {
        if (binding.appBar.searchInput.isVisible) {
            showSearchInput(false)
            binding.appBar.searchInput.clear()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "SubscriptionsFragment"
    }
}
