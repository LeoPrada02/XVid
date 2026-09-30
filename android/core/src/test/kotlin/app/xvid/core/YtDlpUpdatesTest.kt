package app.xvid.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YtDlpUpdatesTest {
    private val updater = FakeUpdater()
    private val storage = FakeStorage()
    private val clock = FakeClock()
    private val updates = YtDlpUpdates(updater, storage, clock)

    @Test
    fun `the weekly check runs the first time`() {
        assertTrue(updates.weeklyCheck())
        assertEquals(1, updater.calls)
    }

    @Test
    fun `the weekly check never runs more than once a week`() {
        updates.weeklyCheck()
        repeat(7 * 24 - 1) {
            clock.advanceHours(1)
            assertFalse(updates.weeklyCheck())
        }
        assertEquals(1, updater.calls)

        clock.advanceHours(1)
        assertTrue(updates.weeklyCheck())
        assertEquals(2, updater.calls)
    }

    @Test
    fun `a check that fails still counts for the week`() {
        updater.failWith = "No connection"

        updates.weeklyCheck()
        clock.advanceHours(24)
        updates.weeklyCheck()

        assertEquals(1, updater.calls)
    }

    @Test
    fun `an update after a failed download counts as this week's check`() {
        updates.updateAfterFailure()
        clock.advanceHours(24)

        assertFalse(updates.weeklyCheck())
        assertEquals(1, updater.calls)
    }

    @Test
    fun `after a failed download yt-dlp is updated, at most once an hour`() {
        updates.updateAfterFailure()
        updates.updateAfterFailure()
        assertEquals(1, updater.calls)

        clock.advanceHours(1)
        updates.updateAfterFailure()
        assertEquals(2, updater.calls)
    }

    @Test
    fun `an update that fails doesn't throw`() {
        updater.failWith = "GitHub is down"

        updates.updateAfterFailure()

        assertEquals(1, updater.calls)
    }

    @Test
    fun `a clock set back in time doesn't allow extra checks`() {
        updates.weeklyCheck()
        clock.advanceHours(-24 * 30)

        assertFalse(updates.weeklyCheck())
        clock.advanceHours(24 * 6)
        assertFalse(updates.weeklyCheck())
        clock.advanceHours(24)
        assertTrue(updates.weeklyCheck())
        assertEquals(2, updater.calls)
    }
}
