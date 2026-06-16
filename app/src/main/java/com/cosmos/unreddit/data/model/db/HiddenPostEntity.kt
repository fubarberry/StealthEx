package com.cosmos.unreddit.data.model.db

import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Ignore
import com.cosmos.unreddit.data.model.Award
import com.cosmos.unreddit.data.model.Flair
import com.cosmos.unreddit.data.model.GalleryMedia
import com.cosmos.unreddit.data.model.MediaType
import com.cosmos.unreddit.data.model.PostType
import com.cosmos.unreddit.data.model.PosterType
import com.cosmos.unreddit.data.model.RedditText
import com.cosmos.unreddit.data.model.Sorting
import com.cosmos.unreddit.data.remote.api.reddit.model.Crosspost
import kotlinx.parcelize.Parcelize

@Parcelize
@Entity(
    tableName = "hidden_post",
    primaryKeys = ["id", "profile_id"],
    foreignKeys = [
        ForeignKey(
            entity = Profile::class,
            parentColumns = ["id"],
            childColumns = ["profile_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class HiddenPostEntity @JvmOverloads constructor(
    @ColumnInfo(name = "id")
    val id: String,

    val subreddit: String,

    val title: String,

    val ratio: Int,

    @ColumnInfo(name = "total_awards")
    val totalAwards: Int,

    @ColumnInfo(name = "oc")
    val isOC: Boolean,

    @Ignore
    val flair: Flair = Flair(),

    @Ignore
    val authorFlair: Flair = Flair(),

    @Ignore
    var hasFlairs: Boolean = false,

    val score: String,

    val type: PostType,

    val domain: String,

    @ColumnInfo(name = "self")
    val isSelf: Boolean,

    @Ignore
    val crosspost: PostEntity? = null,

    @ColumnInfo(name = "self_text_html")
    val selfTextHtml: String?,

    @ColumnInfo(name = "suggested_sorting")
    val suggestedSorting: Sorting,

    @Ignore
    var selfRedditText: RedditText = RedditText(),

    @ColumnInfo(name = "nsfw")
    val isOver18: Boolean,

    val preview: String?,

    @Ignore
    var previewText: CharSequence? = null,

    @Ignore
    val awards: List<Award> = listOf(),

    @ColumnInfo(name = "spoiler")
    val isSpoiler: Boolean,

    @ColumnInfo(name = "archived")
    val isArchived: Boolean,

    @ColumnInfo(name = "locked")
    val isLocked: Boolean,

    @ColumnInfo(name = "poster_type")
    val posterType: PosterType,

    val author: String,

    @ColumnInfo(name = "comments_number")
    val commentsNumber: String,

    val permalink: String,

    @ColumnInfo(name = "stickied")
    val isStickied: Boolean,

    val url: String,

    val created: Long,

    @ColumnInfo(name = "media_type")
    val mediaType: MediaType,

    @ColumnInfo(name = "media_url")
    val mediaUrl: String,

    @Ignore
    val gallery: List<GalleryMedia> = listOf(),

    @Ignore
    var seen: Boolean = true,

    @Ignore
    var saved: Boolean = true,

    @ColumnInfo(name = "time")
    var time: Long = -1,

    @ColumnInfo(name = "profile_id", index = true)
    var profileId: Int = -1,

    @Ignore
    var crosspostScrap: Crosspost? = null
) : Parcelable

fun PostEntity.toHiddenPostEntity(profileId: Int): HiddenPostEntity {
    return HiddenPostEntity(
        id = id,
        subreddit = subreddit,
        title = title,
        ratio = ratio,
        totalAwards = totalAwards,
        isOC = isOC,
        score = score,
        type = type,
        domain = domain,
        isSelf = isSelf,
        selfTextHtml = selfTextHtml,
        suggestedSorting = suggestedSorting,
        isOver18 = isOver18,
        preview = preview,
        isSpoiler = isSpoiler,
        isArchived = isArchived,
        isLocked = isLocked,
        posterType = posterType,
        author = author,
        commentsNumber = commentsNumber,
        permalink = permalink,
        isStickied = isStickied,
        url = url,
        created = created,
        mediaType = mediaType,
        mediaUrl = mediaUrl,
        time = System.currentTimeMillis(),
        profileId = profileId
    )
}

fun HiddenPostEntity.toPostEntity(): PostEntity {
    return PostEntity(
        id = id,
        subreddit = subreddit,
        title = title,
        ratio = ratio,
        totalAwards = totalAwards,
        isOC = isOC,
        score = score,
        type = type,
        domain = domain,
        isSelf = isSelf,
        selfTextHtml = selfTextHtml,
        suggestedSorting = suggestedSorting,
        isOver18 = isOver18,
        preview = preview,
        isSpoiler = isSpoiler,
        isArchived = isArchived,
        isLocked = isLocked,
        posterType = posterType,
        author = author,
        commentsNumber = commentsNumber,
        permalink = permalink,
        isStickied = isStickied,
        url = url,
        created = created,
        mediaType = mediaType,
        mediaUrl = mediaUrl,
        time = time,
        profileId = profileId
    ).apply {
        saved = false
    }
}
