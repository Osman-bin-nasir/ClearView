package com.muddassir.clearview.media.worker

import android.content.Context
import org.json.JSONObject

/**
 * Remembers which media notifications the user has SWIPED AWAY in the system
 * tray, so a dismissed notification can never be re-posted, duplicated or
 * stacked by a later worker run / app open.
 *
 * The key is the exact notification identity — `channelId|videoId` — not just
 * the channel: when a channel uploads something NEW the key changes, so the
 * fresh upload still notifies normally while the dismissal of the previous one
 * is respected. Entries older than [MAX_AGE_MS] are pruned so the store stays
 * bounded.
 */
object NotificationStateStore {

    private const val PREFS_NAME = "notification_state"
    private const val KEY_DISMISSED = "dismissed"
    private const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

    private fun key(channelId: String, videoId: String) = "$channelId|$videoId"

    /** True when the user dismissed exactly this notification (channel + video). */
    fun isDismissed(context: Context, channelId: String, videoId: String): Boolean =
        readDismissed(context).containsKey(key(channelId, videoId))

    /** Records that the user swiped away this notification. */
    fun markDismissed(context: Context, channelId: String, videoId: String) {
        val map = readDismissed(context)
        map[key(channelId, videoId)] = System.currentTimeMillis()
        writeDismissed(context, map)
    }

    /** Forgets every dismissal belonging to [channelId] (e.g. it was removed). */
    fun clearChannel(context: Context, channelId: String) {
        val map = readDismissed(context)
        val prefix = "$channelId|"
        val sizeBefore = map.size
        map.keys.removeAll { it.startsWith(prefix) }
        if (map.size != sizeBefore) writeDismissed(context, map)
    }

    private fun readDismissed(context: Context): MutableMap<String, Long> {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_DISMISSED, null) ?: return mutableMapOf()
        return runCatching {
            val o = JSONObject(raw)
            val map = mutableMapOf<String, Long>()
            o.keys().forEach { k -> map[k] = o.optLong(k, 0L) }
            map
        }.getOrDefault(mutableMapOf())
    }

    private fun writeDismissed(context: Context, map: Map<String, Long>) {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        val o = JSONObject()
        map.forEach { (k, at) -> if (at >= cutoff) o.put(k, at) }
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_DISMISSED, o.toString()).apply()
    }
}
