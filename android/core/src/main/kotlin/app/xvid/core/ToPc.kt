package app.xvid.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

/** An X link waiting in the queue for its PC ([pcId], see [Pc.id]). [queuedAt] is in milliseconds since the epoch. */
data class QueuedLink(val id: String, val url: String, val pcId: String, val queuedAt: Long)

/** How sending a link To PC went. */
sealed interface ToPcOutcome {
    /** [pc] got it: it's downloading it into its PC library. */
    data class Sent(val pc: Pc) : ToPcOutcome

    /** [pc] isn't reachable: the link waits in the queue until it is. */
    data class Queued(val pc: Pc) : ToPcOutcome

    /** [pc] is reachable but didn't take the link (e.g. it needs pairing again); [reason] says why. Not queued. */
    data class Refused(val pc: Pc, val reason: String) : ToPcOutcome

    /** The text has no X link. */
    data object NotALink : ToPcOutcome
}

/** A queued [link] that [pc] got. */
data class SentLink(val link: QueuedLink, val pc: Pc)

/**
 * To PC and the queue: an X link goes to the chosen PC, which downloads it into its PC library. If
 * that PC isn't reachable, the link waits in the queue, kept in [storage] so it lasts across restarts,
 * and [sendQueue] sends it once that PC is reachable again. A queued link only ever goes to its own PC;
 * it can be removed or moved to another PC, and never expires. Sending the queue and moving a link
 * happen one at a time, so a link is never sent twice (or to two PCs) when the app and the
 * background both send the queue.
 *
 * Blocking; callers run it off the main thread.
 */
class ToPc(private val pcs: KnownPcs, private val storage: Storage, private val clock: Clock) {
    private val requests = PcRequests(pcs)
    private val sending = Any()

    /** The PC to offer first: the one chosen last, else the home PC. Null before pairing. */
    fun lastChoice(): Pc? = pcs.find(storage.get(LAST_CHOICE)) ?: pcs.list().let { all -> all.firstOrNull { it.home } ?: all.firstOrNull() }

    /**
     * Sends the X link in [text] to [pc] (looking for it on the network if it isn't at its last
     * address), or queues it for [pc] when it isn't reachable.
     */
    fun send(text: String, pc: Pc): ToPcOutcome {
        val link = XPostLink.find(text) ?: return ToPcOutcome.NotALink
        storage.put(LAST_CHOICE, pc.id)
        var outcome = attempt(link.url, pc)
        if (outcome is ToPcOutcome.Queued) {
            val found = pcs.refresh().firstOrNull { it.pc.id == pc.id && it.state == PcState.REACHABLE }?.pc
            if (found != null) outcome = attempt(link.url, found)
        }
        if (outcome is ToPcOutcome.Queued) {
            synchronized(this) { save(load() + QueuedLink(UUID.randomUUID().toString(), link.url, pc.id, clock.now())) }
        }
        return outcome
    }

    /** The links waiting for their PC, oldest first. */
    @Synchronized
    fun queue(): List<QueuedLink> = load()

    @Synchronized
    fun remove(id: String) = save(load().filterNot { it.id == id })

    /**
     * Moves the queued link [id] to [pc]: sent right away when [pc] is reachable, else it waits for
     * [pc]. Null when the link isn't in the queue anymore.
     */
    fun reassign(id: String, pc: Pc): ToPcOutcome? = synchronized(sending) {
        val link = queue().firstOrNull { it.id == id } ?: return null
        val outcome = attempt(link.url, pc)
        if (outcome is ToPcOutcome.Sent) remove(id) else synchronized(this) { save(load().map { if (it.id == id) it.copy(pcId = pc.id) else it }) }
        outcome
    }

    /**
     * Sends each queued link whose PC is reachable now (looking for the PCs on the network first),
     * and returns the ones sent. The others keep waiting.
     */
    fun sendQueue(): List<SentLink> = synchronized(sending) {
        if (queue().isEmpty()) return emptyList()
        val reachable = pcs.refresh().filter { it.state == PcState.REACHABLE }.associate { it.pc.id to it.pc }
        queue().mapNotNull { link ->
            val pc = reachable[link.pcId] ?: return@mapNotNull null
            if (queue().none { it == link }) return@mapNotNull null // removed meanwhile
            if (attempt(link.url, pc) !is ToPcOutcome.Sent) return@mapNotNull null // a refused link waits too
            remove(link.id)
            SentLink(link, pc)
        }
    }

    /** Sends [url] to [pc] as a job: [ToPcOutcome.Queued] means only that [pc] isn't reachable (nothing is queued here). */
    private fun attempt(url: String, pc: Pc): ToPcOutcome = try {
        val body = buildJsonObject { put("url", url) }.toString().toRequestBody(JSON)
        requests.call(pc, Request.Builder().url(requests.url(pc) { addPathSegments("api/jobs") }).post(body)) {}
        ToPcOutcome.Sent(pc)
    } catch (e: PcException) {
        if (e.reason == PcException.Reason.NOT_REACHABLE) ToPcOutcome.Queued(pc) else ToPcOutcome.Refused(pc, e.message.orEmpty())
    }

    private fun load(): List<QueuedLink> {
        val json = storage.get(QUEUE)?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } as? JsonArray
        return json.orEmpty().mapNotNull { item ->
            val entry = item as? JsonObject ?: return@mapNotNull null
            fun string(key: String) = entry[key]?.jsonPrimitive?.contentOrNull
            QueuedLink(
                id = string("id") ?: return@mapNotNull null,
                url = string("url") ?: return@mapNotNull null,
                pcId = string("pc") ?: return@mapNotNull null,
                queuedAt = entry["queuedAt"]?.jsonPrimitive?.longOrNull ?: 0,
            )
        }
    }

    private fun save(links: List<QueuedLink>) = storage.put(QUEUE, JsonArray(links.map {
        buildJsonObject { put("id", it.id); put("url", it.url); put("pc", it.pcId); put("queuedAt", it.queuedAt) }
    }).toString())

    private companion object {
        const val QUEUE = "toPc.queue"
        const val LAST_CHOICE = "toPc.lastChoice"
        val JSON = "application/json".toMediaType()
    }
}
