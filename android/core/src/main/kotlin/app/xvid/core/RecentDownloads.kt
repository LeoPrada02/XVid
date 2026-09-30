package app.xvid.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** A recent phone download and how it's going or how it ended. [detail] is shown under it. */
data class RecentDownload(val url: String, val state: State, val detail: String, val time: Long) {
    enum class State { DOWNLOADING, SAVED, FAILED, NEEDS_LOGIN }
}

/**
 * The latest phone downloads, newest first, for the app's main screen: how each one
 * ended is visible there even when XVid isn't allowed to show notifications.
 * Kept in [storage], so it survives the app being closed.
 */
class RecentDownloads(private val storage: Storage, private val clock: Clock) {
    @Synchronized
    fun list(): List<RecentDownload> {
        val stored = storage.get(KEY) ?: return emptyList()
        val array = runCatching { Json.parseToJsonElement(stored) as JsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            fun string(key: String) = entry[key]?.jsonPrimitive?.contentOrNull
            RecentDownload(
                url = string("url") ?: return@mapNotNull null,
                state = RecentDownload.State.entries.firstOrNull { it.name == string("state") } ?: return@mapNotNull null,
                detail = string("detail").orEmpty(),
                time = entry["time"]?.jsonPrimitive?.longOrNull ?: 0,
            )
        }
    }

    fun started(url: String) = put(url, RecentDownload.State.DOWNLOADING, "")

    fun ended(url: String, outcome: PhoneDownloadOutcome) = when (outcome) {
        is PhoneDownloadOutcome.Saved -> put(
            url,
            RecentDownload.State.SAVED,
            outcome.videos.singleOrNull()?.name ?: "${outcome.videos.size} videos",
        )
        is PhoneDownloadOutcome.Failed -> put(url, RecentDownload.State.FAILED, outcome.reason)
        is PhoneDownloadOutcome.NeedsLogin -> put(url, RecentDownload.State.NEEDS_LOGIN, outcome.reason)
    }

    /** Puts [url] at the top with its new state, replacing its older entry. */
    @Synchronized
    private fun put(url: String, state: RecentDownload.State, detail: String) {
        val entries = listOf(RecentDownload(url, state, detail, clock.now())) + list().filterNot { it.url == url }
        val json = buildJsonArray {
            for (entry in entries.take(MAX)) addJsonObject {
                put("url", entry.url)
                put("state", entry.state.name)
                put("detail", entry.detail)
                put("time", entry.time)
            }
        }
        storage.put(KEY, json.toString())
    }

    companion object {
        /** How many downloads the main screen shows. */
        const val MAX = 10

        private const val KEY = "downloads.recent"
    }
}
