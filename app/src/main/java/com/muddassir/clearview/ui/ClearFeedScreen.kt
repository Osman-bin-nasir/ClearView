package com.muddassir.clearview.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muddassir.clearview.R

/**
 * Clear Feed — the calm replacement for the old bottom Live tab.
 *
 * A curated, fully offline feed of short community posts (reflections,
 * discipline, wellness and Quran notes) with no ads, no infinite scroll and no
 * engagement traps. Posts can be liked and saved locally; the state is kept in
 * a dedicated SharedPreferences file so it survives restarts. Makkah / Madinah
 * live broadcasts moved to the Media tab's "Haramayn Live" shortcut.
 */
@Composable
fun ClearFeedScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    var likedIds by remember {
        mutableStateOf(prefs.getStringSet(KEY_LIKED, emptySet())?.toSet() ?: emptySet())
    }
    var savedIds by remember {
        mutableStateOf(prefs.getStringSet(KEY_SAVED, emptySet())?.toSet() ?: emptySet())
    }
    // 0 = All, then one chip per category (in [FeedCategory.entries] order).
    var filterIndex by rememberSaveable { mutableStateOf(0) }

    val posts = remember(filterIndex) {
        if (filterIndex == 0) CLEAR_FEED_POSTS
        else CLEAR_FEED_POSTS.filter { it.category == FeedCategory.entries[filterIndex - 1] }
    }

    fun persist(key: String, ids: Set<String>) {
        prefs.edit().putStringSet(key, ids).apply()
    }

    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.clear_feed_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp)
        )

        // ── Category filter strip ──────────────────────────────────
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(key = "all") {
                FilterChip(
                    selected = filterIndex == 0,
                    onClick = { filterIndex = 0 },
                    label = { Text(stringResource(R.string.clear_feed_filter_all)) }
                )
            }
            items(FeedCategory.entries.toList(), key = { it.name }) { category ->
                val index = category.ordinal + 1
                FilterChip(
                    selected = filterIndex == index,
                    onClick = { filterIndex = index },
                    label = { Text(stringResource(category.labelRes)) }
                )
            }
        }

        if (posts.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.clear_feed_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(32.dp)
                )
            }
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(posts, key = { it.id }) { post ->
                ClearFeedPostCard(
                    post = post,
                    liked = post.id in likedIds,
                    saved = post.id in savedIds,
                    onToggleLike = {
                        likedIds = if (post.id in likedIds) likedIds - post.id
                        else likedIds + post.id
                        persist(KEY_LIKED, likedIds)
                    },
                    onToggleSave = {
                        savedIds = if (post.id in savedIds) savedIds - post.id
                        else savedIds + post.id
                        persist(KEY_SAVED, savedIds)
                    },
                    onShare = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(
                                Intent.EXTRA_TEXT,
                                context.getString(
                                    R.string.clear_feed_share_text,
                                    post.body,
                                    post.author
                                )
                            )
                        }
                        runCatching {
                            context.startActivity(
                                Intent.createChooser(
                                    send,
                                    context.getString(R.string.clear_feed_share_via)
                                )
                            )
                        }
                    }
                )
            }
        }
    }
}

/** One post card: author line, body, tag row and the like / save / share actions. */
@Composable
private fun ClearFeedPostCard(
    post: ClearFeedPost,
    liked: Boolean,
    saved: Boolean,
    onToggleLike: () -> Unit,
    onToggleSave: () -> Unit,
    onShare: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ── Author row ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(post.avatarColor),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = post.author.take(1).uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = post.author,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (post.verified) {
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                Icons.Filled.Verified,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                    Text(
                        text = stringResource(
                            R.string.clear_feed_handle_time,
                            post.handle,
                            relativeTime(post.minutesAgo)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── Body ──
            Text(
                text = post.body,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(Modifier.height(10.dp))

            // ── Tag ──
            Text(
                text = "#" + stringResource(post.category.labelRes).replace(" ", ""),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(Modifier.height(4.dp))

            // ── Actions ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onToggleLike) {
                    Icon(
                        imageVector = if (liked) Icons.Filled.Favorite
                        else Icons.Outlined.FavoriteBorder,
                        contentDescription = stringResource(R.string.clear_feed_like),
                        tint = if (liked) Color(0xFFE53935)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Text(
                    text = (post.likes + if (liked) 1 else 0).toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onToggleSave) {
                    Icon(
                        imageVector = if (saved) Icons.Filled.Bookmark
                        else Icons.Outlined.BookmarkBorder,
                        contentDescription = stringResource(R.string.clear_feed_save),
                        tint = if (saved) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onShare) {
                    Icon(
                        Icons.Filled.Share,
                        contentDescription = stringResource(R.string.clear_feed_share),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

/**
 * Coarse relative time from a static "minutes ago" value. Localized through
 * plurals so "1 minute ago" never renders as "1 minutes ago".
 */
@Composable
private fun relativeTime(minutesAgo: Int): String = when {
    minutesAgo < 60 -> pluralStringResource(
        R.plurals.clear_feed_time_minutes, minutesAgo, minutesAgo
    )
    minutesAgo < 60 * 24 -> {
        val hours = minutesAgo / 60
        pluralStringResource(R.plurals.clear_feed_time_hours, hours, hours)
    }
    minutesAgo < 60 * 24 * 7 -> {
        val days = minutesAgo / (60 * 24)
        pluralStringResource(R.plurals.clear_feed_time_days, days, days)
    }
    else -> {
        val weeks = minutesAgo / (60 * 24 * 7)
        pluralStringResource(R.plurals.clear_feed_time_weeks, weeks, weeks)
    }
}

/** The feed categories behind the filter chips. */
private enum class FeedCategory(val labelRes: Int) {
    REFLECTION(R.string.clear_feed_filter_reflection),
    DISCIPLINE(R.string.clear_feed_filter_discipline),
    WELLNESS(R.string.clear_feed_filter_wellness),
    QURAN(R.string.clear_feed_filter_quran)
}

/** One bundled post. */
private data class ClearFeedPost(
    val id: String,
    val author: String,
    val handle: String,
    val minutesAgo: Int,
    val body: String,
    val category: FeedCategory,
    val likes: Int,
    val verified: Boolean = false,
    val avatarColor: Color = Color(0xFF546E7A)
)

private const val PREFS_NAME = "clear_feed"
private const val KEY_LIKED = "liked_posts"
private const val KEY_SAVED = "saved_posts"

/**
 * The bundled, ad-free community feed. Static and fully offline — a small,
 * finite set of posts so the feed is always calm and never doom-scrollable.
 */
private val CLEAR_FEED_POSTS: List<ClearFeedPost> = listOf(
    ClearFeedPost(
        id = "cf1",
        author = "Clear View Community",
        handle = "@clearview",
        minutesAgo = 12,
        body = "Start your day with intention: name the one thing you want to " +
            "finish today before you touch your phone. Clarity beats speed.",
        category = FeedCategory.DISCIPLINE,
        likes = 214,
        verified = true,
        avatarColor = Color(0xFF00695C)
    ),
    ClearFeedPost(
        id = "cf2",
        author = "Reflection Corner",
        handle = "@reflect",
        minutesAgo = 48,
        body = "\"Indeed, with hardship comes ease.\" Pause on that for a moment " +
            "today — ease is promised alongside, not only after.",
        category = FeedCategory.QURAN,
        likes = 532,
        avatarColor = Color(0xFF283593)
    ),
    ClearFeedPost(
        id = "cf3",
        author = "Calm Notes",
        handle = "@calmnotes",
        minutesAgo = 130,
        body = "A quiet 5-minute walk with no headphones does more for focus " +
            "than a second cup of coffee. Try it before your next deep-work block.",
        category = FeedCategory.WELLNESS,
        likes = 176,
        avatarColor = Color(0xFF6A1B9A)
    ),
    ClearFeedPost(
        id = "cf4",
        author = "The Long Game",
        handle = "@longgame",
        minutesAgo = 300,
        body = "Consistency is quiet. Nobody claps for day 4 of a 100-day habit, " +
            "and that's exactly why it works — keep showing up unseen.",
        category = FeedCategory.DISCIPLINE,
        likes = 389,
        verified = true,
        avatarColor = Color(0xFFEF6C00)
    ),
    ClearFeedPost(
        id = "cf5",
        author = "Clear View Community",
        handle = "@clearview",
        minutesAgo = 700,
        body = "Your feed should serve your goals, not hijack them. Curate ruthlessly: " +
            "follow what teaches you, mute what merely distracts.",
        category = FeedCategory.WELLNESS,
        likes = 268,
        verified = true,
        avatarColor = Color(0xFF00695C)
    ),
    ClearFeedPost(
        id = "cf6",
        author = "Reflection Corner",
        handle = "@reflect",
        minutesAgo = 1500,
        body = "Gratitude is a muscle. Write down three specific things from today " +
            "that you'd miss if they disappeared — specificity is what makes it stick.",
        category = FeedCategory.REFLECTION,
        likes = 141,
        avatarColor = Color(0xFF283593)
    ),
    ClearFeedPost(
        id = "cf7",
        author = "Quiet Discipline",
        handle = "@quietdiscipline",
        minutesAgo = 2600,
        body = "Discipline isn't punishment — it's the promise you keep to your " +
            "future self when motivation has already gone home.",
        category = FeedCategory.DISCIPLINE,
        likes = 623,
        avatarColor = Color(0xFFAD1457)
    ),
    ClearFeedPost(
        id = "cf8",
        author = "Calm Notes",
        handle = "@calmnotes",
        minutesAgo = 4300,
        body = "Protect the first hour of your morning and the last hour before " +
            "sleep. Those two hours shape the whole day more than any app will.",
        category = FeedCategory.WELLNESS,
        likes = 297,
        avatarColor = Color(0xFF6A1B9A)
    )
)
