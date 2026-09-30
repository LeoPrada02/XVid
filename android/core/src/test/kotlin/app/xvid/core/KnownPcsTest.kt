package app.xvid.core

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import javax.net.ssl.SSLException
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KnownPcsTest {
    private val ca = FakeCa()
    private val storage = FakeStorage()
    private val pcs = KnownPcs(storage)
    private val servers = mutableListOf<FakePc>()

    private fun pc(name: String, signedBy: FakeCa = ca, isHome: Boolean = true) =
        FakePc(name, signedBy, isHome).also { servers += it }

    @AfterEach
    fun stopServers() = servers.forEach { runCatching { it.stop() } }

    private fun statesByName() = pcs.refresh().associate { it.pc.name to it.state }

    // Pairing

    @Test
    fun `pairing redeems the code over HTTPS and remembers the PC`() {
        val home = pc("home")

        val result = pcs.pair(home.qr())

        assertEquals(PairingResult.Paired(listOf(home.listed)), result)
        assertEquals(listOf("/api/pair/ca", "/api/pair/redeem", "/api/pcs"), home.paths())
        assertEquals(listOf(home.listed), pcs.list())
        assertEquals(mapOf("home" to PcState.REACHABLE), statesByName())
        assertEquals("Bearer ${FakePc.SESSION}", home.requests.last().getHeader("Authorization"))
    }

    @Test
    fun `the PC's CA is trusted only for XVid's own connections`() {
        val home = pc("home")
        pcs.pair(home.qr())

        val anyOtherClient = OkHttpClient() // the system's trust, as any other app on the phone has it
        assertThrows<SSLException> {
            anyOtherClient.newCall(Request.Builder().url("${home.url}/api/ping").build()).execute()
        }
    }

    @Test
    fun `pairing is refused when the PC's CA doesn't match the fingerprint`() {
        val home = pc("home")

        val result = pcs.pair(home.qr(fingerprint = FakeCa("someone else's CA").fingerprint))

        assertEquals(PairingResult.FingerprintMismatch, result)
        assertEquals(listOf("/api/pair/ca"), home.paths()) // the code never left the phone
        assertEquals(emptyList(), pcs.list())
    }

    @Test
    fun `pairing is refused when the PC's certificate isn't signed by the fingerprinted CA`() {
        // Something pretending to be the PC hands out the real CA, but can't have a certificate signed by it.
        val impostor = pc("impostor", signedBy = FakeCa("impostor's CA"))
        impostor.servesCa = ca

        val result = pcs.pair(impostor.qr(fingerprint = ca.fingerprint))

        assertEquals(PairingResult.FingerprintMismatch, result)
        assertEquals(listOf("/api/pair/ca"), impostor.paths())
        assertEquals(emptyList(), pcs.list())
    }

    @Test
    fun `an expired or used code is refused with the PC's reason`() {
        val home = pc("home")

        val result = pcs.pair(home.qr(code = "expired-code"))

        val refused = assertIs<PairingResult.CodeRefused>(result)
        assertTrue(refused.reason.startsWith("This pairing code expired"))
        assertEquals(emptyList(), pcs.list())
    }

    @Test
    fun `other QR codes aren't pairing codes`() {
        for (text in listOf("https://x.com/someone/status/1", "xvid://pair?pc=http%3A%2F%2F192.0.2.10&code=c&fp=00", "")) {
            assertEquals(PairingResult.NotAPairingCode, pcs.pair(text), text)
        }
    }

    @Test
    fun `pairing needs the PC to be reachable`() {
        val home = pc("home")
        val qr = home.qr()
        home.stop()

        assertEquals(PairingResult.NotReachable, pcs.pair(qr))
        assertEquals(emptyList(), pcs.list())
    }

    @Test
    fun `pairing is remembered across app restarts`() {
        val home = pc("home")
        pcs.pair(home.qr())

        val restarted = KnownPcs(storage)

        assertEquals(listOf(home.listed), restarted.list())
        assertEquals(listOf(PcStatus(home.listed, PcState.REACHABLE)), restarted.refresh())
    }

    // Joined PCs

    @Test
    fun `pairing once covers every PC that joined the home PC`() {
        val home = pc("home")
        val laptop = pc("laptop", isHome = false)
        home.others = listOf(laptop.listed)

        val result = pcs.pair(home.qr())

        assertEquals(PairingResult.Paired(listOf(home.listed, laptop.listed)), result)
        assertEquals(mapOf("home" to PcState.REACHABLE, "laptop" to PcState.REACHABLE), statesByName())
        assertEquals(emptyList(), laptop.paths().filter { it!!.startsWith("/api/pair") }) // no pairing needed there
    }

    @Test
    fun `joined PCs are learned from any reachable PC`() {
        val home = pc("home")
        val laptop = pc("laptop", isHome = false)
        val desktop = pc("desktop", isHome = false)
        home.others = listOf(laptop.listed)
        pcs.pair(home.qr())

        home.stop()
        laptop.others = listOf(home.listed, desktop.listed) // the desktop joined while the phone was away

        assertEquals(
            mapOf("home" to PcState.NOT_REACHABLE, "laptop" to PcState.REACHABLE, "desktop" to PcState.REACHABLE),
            statesByName(),
        )
        assertEquals(listOf("home", "laptop", "desktop"), pcs.list().map { it.name })
    }

    @Test
    fun `a PC's new name is learned from its list`() {
        val home = pc("home")
        pcs.pair(home.qr())
        home.name = "living room"

        pcs.refresh()

        assertEquals(listOf(Pc("living room", home.url, home = true)), pcs.list())
    }

    // Reachability

    @Test
    fun `a PC that's off or on another network is shown as not reachable, and kept`() {
        val home = pc("home")
        pcs.pair(home.qr())
        home.stop()

        assertEquals(listOf(PcStatus(home.listed, PcState.NOT_REACHABLE)), pcs.refresh())
        assertEquals(listOf(home.listed), pcs.list())
    }

    @Test
    fun `a PC that no longer accepts the phone's login asks to pair again`() {
        val home = pc("home")
        pcs.pair(home.qr())
        home.session = "a new token was made on the PC"

        assertEquals(listOf(PcStatus(home.listed, PcState.PAIR_AGAIN)), pcs.refresh())
    }

    @Test
    fun `nothing is known before pairing`() {
        assertEquals(emptyList(), pcs.list())
        assertEquals(emptyList(), pcs.refresh())
        assertEquals(null, pcs.connection())
    }

    @Test
    fun `pairing with another home PC replaces the old PCs`() {
        val home = pc("home")
        pcs.pair(home.qr())
        val otherHome = pc("other home", signedBy = FakeCa("another household's CA"))

        pcs.pair(otherHome.qr())

        assertEquals(listOf(otherHome.listed), pcs.list())
        assertEquals(mapOf("other home" to PcState.REACHABLE), statesByName())
    }
}
