package app.xvid.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpdateCheckTest {
    private val feed = FakeReleaseFeed()
    private val storage = FakeStorage()
    private val clock = FakeClock()
    private val check = UpdateCheck(feed, currentVersion = "1.2.0", storage, clock)

    private fun release(tag: String) = Release(tag, "https://example.com/releases/$tag")

    @Test
    fun `a newer release is offered with its link`() {
        feed.latest = release("v1.10.0")

        assertEquals(Update("1.10.0", "https://example.com/releases/v1.10.0"), check.newerRelease())
    }

    @Test
    fun `the same or an older release isn't offered`() {
        feed.latest = release("v1.2.0")
        assertNull(check.newerRelease())

        feed.latest = release("1.1.9")
        assertNull(check.newerRelease())
    }

    @Test
    fun `no release, a failed check or an odd tag offers nothing`() {
        assertNull(check.newerRelease())

        feed.latest = release("nightly")
        assertNull(check.newerRelease())

        feed.failWith = "No connection"
        assertNull(check.newerRelease())
    }

    @Test
    fun `the notification check runs at most once a day`() {
        feed.latest = release("v1.3.0")

        check.releaseToNotify()
        clock.advanceHours(23)
        check.releaseToNotify()
        assertEquals(1, feed.calls)

        clock.advanceHours(1)
        check.releaseToNotify()
        assertEquals(2, feed.calls)
    }

    @Test
    fun `each new version is notified only once`() {
        feed.latest = release("v1.3.0")

        assertEquals("1.3.0", check.releaseToNotify()?.version)
        clock.advanceHours(24)
        assertNull(check.releaseToNotify())

        feed.latest = release("v1.4.0")
        clock.advanceHours(24)
        assertEquals("1.4.0", check.releaseToNotify()?.version)
    }

    @Test
    fun `a failed notification check is retried the next day`() {
        feed.failWith = "No connection"
        assertNull(check.releaseToNotify())

        feed.failWith = null
        feed.latest = release("v1.3.0")
        clock.advanceHours(24)
        assertEquals("1.3.0", check.releaseToNotify()?.version)
    }
}
