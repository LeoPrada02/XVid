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
    private val discovery = FakeDiscovery()
    private val pcs = KnownPcs(storage, discovery)
    private val servers = mutableListOf<FakePc>()

    private fun pc(
        name: String,
        signedBy: FakeCa = ca,
        isHome: Boolean = true,
        id: String = "id-of-$name",
        certifiedFor: List<String>? = null,
        port: Int = 0,
    ) = FakePc(name, signedBy, isHome, id = id, certifiedFor = certifiedFor, port = port).also { servers += it }

    /** [pc] stopped, and started again at a new address (a new port, here). */
    private fun moved(pc: FakePc): FakePc {
        pc.stop()
        return pc(pc.name, isHome = pc.isHome, id = pc.id).also { it.others = pc.others }
    }

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

        val restarted = KnownPcs(storage, discovery)

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

        assertEquals(listOf(Pc(home.id, "living room", home.url, home = true)), pcs.list())
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

    // Finding PCs on the network

    @Test
    fun `a PC that moved to a new address is found there and remembered there`() {
        val home = pc("home")
        pcs.pair(home.qr())
        val movedHome = moved(home)
        discovery.found = listOf(FoundPc(home.id, movedHome.url))

        assertEquals(listOf(PcStatus(movedHome.listed, PcState.REACHABLE)), pcs.refresh())
        assertEquals(listOf(movedHome.listed), pcs.list())

        discovery.found = emptyList() // found there once, it's reached there later even without discovery
        assertEquals(listOf(PcStatus(movedHome.listed, PcState.REACHABLE)), pcs.refresh())
    }

    @Test
    fun `a joined PC's new address wins over an older address in another PC's list`() {
        val home = pc("home")
        val laptop = pc("laptop", isHome = false)
        home.others = listOf(laptop.listed)
        pcs.pair(home.qr())
        val movedLaptop = moved(laptop) // the home PC still lists the old address
        discovery.found = listOf(FoundPc(laptop.id, movedLaptop.url))

        assertEquals(mapOf("home" to PcState.REACHABLE, "laptop" to PcState.REACHABLE), statesByName())
        assertEquals(listOf(home.url, movedLaptop.url), pcs.list().map { it.url })
    }

    @Test
    fun `when discovery finds nothing, each PC is tried at its last known address`() {
        val home = pc("home")
        pcs.pair(home.qr())

        discovery.found = emptyList()
        assertEquals(listOf(PcStatus(home.listed, PcState.REACHABLE)), pcs.refresh())

        discovery.failWith = "no Wi-Fi"
        assertEquals(listOf(PcStatus(home.listed, PcState.REACHABLE)), pcs.refresh())
    }

    @Test
    fun `when nothing answers at the found address, the last known address is tried`() {
        val home = pc("home")
        pcs.pair(home.qr())
        val gone = pc("gone").also { it.stop() }
        discovery.found = listOf(FoundPc(home.id, gone.url))

        assertEquals(listOf(PcStatus(home.listed, PcState.REACHABLE)), pcs.refresh())
        assertEquals(listOf(home.listed), pcs.list())
    }

    @Test
    fun `PCs found on the network are matched to the paired PCs by id`() {
        val home = pc("home")
        pcs.pair(home.qr())
        val stranger = pc("a PC the phone isn't paired with")
        discovery.found = listOf(FoundPc(stranger.id, stranger.url))

        assertEquals(mapOf("home" to PcState.REACHABLE), statesByName())
        assertEquals(listOf(home.listed), pcs.list())
    }

    @Test
    fun `another PC now at a PC's last address isn't mistaken for it`() {
        val home = pc("home")
        val laptop = pc("laptop", isHome = false)
        home.others = listOf(laptop.listed)
        pcs.pair(home.qr())
        laptop.stop()
        pc("desktop", isHome = false, port = laptop.port) // took the laptop's old address

        assertEquals(PcState.NOT_REACHABLE, statesByName()["laptop"])
    }

    @Test
    fun `a PC is still trusted when its certificate was made for an older address`() {
        val home = pc("home", certifiedFor = listOf("192.0.2.10")) // signed by the paired CA, for another address

        assertIs<PairingResult.Paired>(pcs.pair(home.qr()))
        assertEquals(mapOf("home" to PcState.REACHABLE), statesByName())
    }

    @Test
    fun `PCs remembered before PCs had ids keep working, and learn their ids`() {
        val home = pc("home")
        storage.put("pcs.list", """[{"name":"home","url":"${home.url}","home":true}]""")
        pcs.pair(home.qr())
        storage.put("pcs.list", """[{"name":"home","url":"${home.url}","home":true}]""")

        assertEquals(listOf(PcStatus(home.listed, PcState.REACHABLE)), pcs.refresh())
        assertEquals(listOf(home.listed), pcs.list())
    }

    @Test
    fun `the reachable PCs are the ones that answer now`() {
        val home = pc("home")
        val laptop = pc("laptop", isHome = false)
        home.others = listOf(laptop.listed)
        pcs.pair(home.qr())
        laptop.stop()

        assertEquals(listOf("home"), pcs.reachable().map { it.name })

        home.stop()
        assertEquals(emptyList(), pcs.reachable())
    }
}
