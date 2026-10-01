package app.xvid.core

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ToPcTest {
    private val ca = FakeCa()
    private val storage = FakeStorage()
    private val clock = FakeClock()
    private val discovery = FakeDiscovery()
    private val knownPcs = KnownPcs(storage, discovery)
    private val home = FakePc("home", ca)
    private val laptop = FakePc("laptop", ca, isHome = false)
    private val toPc = ToPc(knownPcs, storage, clock)

    init {
        home.others = listOf(laptop.listed)
        knownPcs.pair(home.qr())
    }

    @AfterEach
    fun stop() = listOf(home, laptop).forEach { runCatching { it.stop() } }

    private fun pc(name: String) = knownPcs.list().single { it.name == name }

    private val post = "Look at this https://x.com/someone/status/123?s=20"
    private val postUrl = "https://x.com/someone/status/123"

    // Sending

    @Test
    fun `a link to a reachable PC is sent right away as a job`() {
        val outcome = toPc.send(post, pc("laptop"))

        assertEquals(ToPcOutcome.Sent(pc("laptop")), outcome)
        assertEquals(listOf(postUrl), laptop.jobs)
        assertEquals(emptyList(), toPc.queue())
    }

    @Test
    fun `text without an X link isn't sent or queued`() {
        assertEquals(ToPcOutcome.NotALink, toPc.send("just some words", pc("home")))
        assertEquals(emptyList(), home.jobs)
        assertEquals(emptyList(), toPc.queue())
    }

    @Test
    fun `a link to a PC that isn't reachable waits in the queue for that PC`() {
        laptop.stop()

        val outcome = toPc.send(post, pc("laptop"))

        assertEquals(ToPcOutcome.Queued(pc("laptop")), outcome)
        val queued = toPc.queue().single()
        assertEquals(postUrl, queued.url)
        assertEquals(pc("laptop").id, queued.pcId)
        assertEquals(clock.millis, queued.queuedAt)
    }

    @Test
    fun `the queue lasts across app restarts`() {
        laptop.stop()
        toPc.send(post, pc("laptop"))

        val restarted = ToPc(KnownPcs(storage, discovery), storage, clock)

        assertEquals(listOf(postUrl), restarted.queue().map { it.url })
    }

    @Test
    fun `the PC chosen last is remembered`() {
        assertEquals(pc("home"), toPc.lastChoice()) // before any choice: the home PC
        laptop.stop()

        toPc.send(post, pc("laptop"))

        assertEquals(pc("laptop"), ToPc(knownPcs, storage, clock).lastChoice())
    }

    @Test
    fun `nothing to choose before pairing`() {
        assertNull(ToPc(KnownPcs(FakeStorage(), discovery), FakeStorage(), clock).lastChoice())
    }

    // Sending the queue

    @Test
    fun `queued links are sent once their PC is reachable, and leave the queue`() {
        val laptopPc = pc("laptop")
        laptop.stop()
        toPc.send(post, laptopPc)
        toPc.send("https://twitter.com/other/status/456", laptopPc)
        val back = FakePc("laptop", ca, isHome = false, id = laptop.id, port = laptop.port)

        try {
            val sent = toPc.sendQueue()

            assertEquals(listOf(postUrl, "https://x.com/other/status/456"), back.jobs)
            assertEquals(listOf(postUrl, "https://x.com/other/status/456"), sent.map { it.link.url })
            assertEquals(listOf("laptop", "laptop"), sent.map { it.pc.name })
            assertEquals(emptyList(), toPc.queue())
        } finally {
            back.stop()
        }
    }

    @Test
    fun `sending the queue finds a PC at its new address`() {
        laptop.stop()
        toPc.send(post, pc("laptop"))
        val moved = FakePc("laptop", ca, isHome = false, id = laptop.id)
        discovery.found = listOf(FoundPc(laptop.id, moved.url))

        try {
            toPc.sendQueue()

            assertEquals(listOf(postUrl), moved.jobs)
            assertEquals(emptyList(), toPc.queue())
        } finally {
            moved.stop()
        }
    }

    @Test
    fun `links for a PC that's still not reachable keep waiting`() {
        laptop.stop()
        toPc.send(post, pc("laptop"))

        assertEquals(emptyList(), toPc.sendQueue())
        assertEquals(listOf(postUrl), toPc.queue().map { it.url })
        assertEquals(emptyList(), home.jobs) // never sent to another PC by itself
    }

    @Test
    fun `a PC that never comes back keeps its links in the queue, which never expire`() {
        laptop.stop()
        toPc.send(post, pc("laptop"))

        repeat(5) {
            clock.advanceHours(24 * 365)
            toPc.sendQueue()
        }

        assertEquals(listOf(postUrl), toPc.queue().map { it.url })
    }

    // Changing the queue

    @Test
    fun `a queued link can be removed`() {
        laptop.stop()
        toPc.send(post, pc("laptop"))
        toPc.send("https://x.com/other/status/456", pc("laptop"))

        toPc.remove(toPc.queue().first().id)

        assertEquals(listOf("https://x.com/other/status/456"), toPc.queue().map { it.url })
    }

    @Test
    fun `a queued link can go to a different PC, which gets it right away when reachable`() {
        laptop.stop()
        toPc.send(post, pc("laptop"))

        val outcome = toPc.reassign(toPc.queue().single().id, pc("home"))

        assertEquals(ToPcOutcome.Sent(pc("home")), outcome)
        assertEquals(listOf(postUrl), home.jobs)
        assertEquals(emptyList(), toPc.queue())
    }

    @Test
    fun `a link moved to a PC that isn't reachable either waits for that PC`() {
        laptop.stop()
        toPc.send(post, pc("laptop"))
        home.stop()

        val outcome = toPc.reassign(toPc.queue().single().id, pc("home"))

        assertIs<ToPcOutcome.Queued>(outcome)
        assertEquals(listOf(pc("home").id), toPc.queue().map { it.pcId })
        assertEquals(1, toPc.queue().size)
    }

    @Test
    fun `a link that already left the queue can't be moved`() {
        assertNull(toPc.reassign("no such link", pc("home")))
    }

    // When it goes wrong

    @Test
    fun `a PC that refuses the link says why, and the link isn't queued`() {
        laptop.session = "a new token was made on the PC"

        val outcome = toPc.send(post, pc("laptop"))

        val refused = assertIs<ToPcOutcome.Refused>(outcome)
        assertEquals(pc("laptop"), refused.pc)
        assertEquals("laptop doesn't accept this phone anymore", refused.reason)
        assertEquals(emptyList(), toPc.queue())
    }

    @Test
    fun `a link to a PC that moved is sent right away at its new address`() {
        val laptopPc = pc("laptop")
        laptop.stop()
        val moved = FakePc("laptop", ca, isHome = false, id = laptop.id)
        discovery.found = listOf(FoundPc(laptop.id, moved.url))

        try {
            assertEquals(ToPcOutcome.Sent::class, toPc.send(post, laptopPc)::class)
            assertEquals(listOf(postUrl), moved.jobs)
            assertEquals(emptyList(), toPc.queue())
        } finally {
            moved.stop()
        }
    }

    @Test
    fun `sending the queue from two places at once sends each link once`() {
        laptop.stop()
        toPc.send(post, pc("laptop"))
        val back = FakePc("laptop", ca, isHome = false, id = laptop.id, port = laptop.port).apply { jobsDelayMillis = 300 }

        try {
            val threads = List(3) { Thread { toPc.sendQueue() } }
            threads.forEach { it.start() }
            threads.forEach { it.join() }

            assertEquals(listOf(postUrl), back.jobs)
            assertEquals(emptyList(), toPc.queue())
        } finally {
            back.stop()
        }
    }

    @Test
    fun `a link moved while the queue is being sent goes to one PC only`() {
        laptop.stop()
        toPc.send(post, pc("laptop"))
        val back = FakePc("laptop", ca, isHome = false, id = laptop.id, port = laptop.port).apply { jobsDelayMillis = 300 }
        val id = toPc.queue().single().id

        try {
            val sending = Thread { toPc.sendQueue() }.apply { start() }
            Thread.sleep(50)
            toPc.reassign(id, pc("home"))
            sending.join()

            assertEquals(1, back.jobs.size + home.jobs.size)
        } finally {
            back.stop()
        }
    }
}
