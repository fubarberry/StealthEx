package com.cosmos.unreddit.ui.subscriptions

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.size.Precision
import coil.size.Scale
import com.cosmos.unreddit.R
import com.cosmos.unreddit.data.model.db.Subscription
import com.cosmos.unreddit.databinding.ItemSectionBinding
import com.cosmos.unreddit.databinding.ItemSubscriptionBinding

class SubscriptionsAdapter(
    private val listener: (String) -> Unit,
    private val onPinClick: (Subscription) -> Unit,
    private val onSubscribeClick: (Subscription) -> Unit,
    private val onFeedMixClick: () -> Unit = {}
) : ListAdapter<SubscriptionListItem, RecyclerView.ViewHolder>(
    SUBSCRIPTION_COMPARATOR
) {

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is SubscriptionListItem.Section -> TYPE_SECTION
            is SubscriptionListItem.SubredditItem -> TYPE_SUBSCRIPTION
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_SECTION -> SectionViewHolder(ItemSectionBinding.inflate(inflater, parent, false))
            TYPE_SUBSCRIPTION -> SubscriptionViewHolder(ItemSubscriptionBinding.inflate(inflater, parent, false))
            else -> throw IllegalArgumentException("Unknown viewType $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is SubscriptionListItem.Section -> (holder as SectionViewHolder).bind(item)
            is SubscriptionListItem.SubredditItem -> (holder as SubscriptionViewHolder).bind(item.subscription)
        }
    }

    inner class SectionViewHolder(
        private val binding: ItemSectionBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(section: SubscriptionListItem.Section) {
            val context = binding.root.context
            binding.title.text = context.getString(section.titleRes)
            binding.buttonAction.apply {
                isVisible = section.showFeedMixAction
                if (section.showFeedMixAction) {
                    setText(R.string.subscriptions_feed_mix_button)
                    setOnClickListener { onFeedMixClick() }
                } else {
                    setOnClickListener(null)
                }
            }
        }
    }

    inner class SubscriptionViewHolder(
        private val binding: ItemSubscriptionBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(subscription: Subscription) {
            binding.subscription = subscription

            binding.subscriptionImage.load(subscription.icon) {
                crossfade(true)
                scale(Scale.FILL)
                precision(Precision.AUTOMATIC)
                placeholder(R.drawable.icon_reddit_placeholder)
                error(R.drawable.icon_reddit_placeholder)
                fallback(R.drawable.icon_reddit_placeholder)
            }

            binding.buttonPin.apply {
                setImageResource(
                    if (subscription.isPinned) R.drawable.ic_pin_slash else R.drawable.ic_pin
                )
                contentDescription = context.getString(
                    if (subscription.isPinned) R.string.subreddit_button_unpin else R.string.subreddit_button_pin
                )
                setOnClickListener {
                    onPinClick(subscription)
                }
            }

            binding.buttonSubscribe.apply {
                setImageResource(
                    if (subscription.isSubscribed) R.drawable.ic_star_slash else R.drawable.ic_star
                )
                contentDescription = context.getString(
                    if (subscription.isSubscribed) {
                        R.string.subreddit_button_unsubscribe
                    } else {
                        R.string.subreddit_button_subscribe
                    }
                )
                setOnClickListener {
                    onSubscribeClick(subscription)
                }
            }

            itemView.setOnClickListener {
                listener(subscription.name)
            }
        }
    }

    companion object {
        private const val TYPE_SECTION = 0
        private const val TYPE_SUBSCRIPTION = 1

        private val SUBSCRIPTION_COMPARATOR = object : DiffUtil.ItemCallback<SubscriptionListItem>() {

            override fun areItemsTheSame(
                oldItem: SubscriptionListItem,
                newItem: SubscriptionListItem
            ): Boolean {
                return when {
                    oldItem is SubscriptionListItem.Section && newItem is SubscriptionListItem.Section ->
                        oldItem.titleRes == newItem.titleRes
                    oldItem is SubscriptionListItem.SubredditItem && newItem is SubscriptionListItem.SubredditItem ->
                        oldItem.subscription.name.equals(newItem.subscription.name, ignoreCase = true)
                    else -> false
                }
            }

            override fun areContentsTheSame(
                oldItem: SubscriptionListItem,
                newItem: SubscriptionListItem
            ): Boolean {
                return oldItem == newItem
            }
        }
    }
}
