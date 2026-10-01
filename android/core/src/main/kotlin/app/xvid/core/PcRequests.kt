package app.xvid.core

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Why a PC couldn't do what the phone asked. */
class PcException(val reason: Reason, message: String) : Exception(message) {
    enum class Reason {
        /** Off, not running XVid, or the phone is on another network. */
        NOT_REACHABLE,

        /** The PC no longer accepts the phone's login: pair again. */
        PAIR_AGAIN,

        /** What was asked about isn't on the PC anymore. */
        NOT_FOUND,

        /** Anything else: the PC answered with an error, or the phone couldn't keep what it sent. */
        FAILED,
    }
}

/** Requests to a known PC's API, with the phone's login, failing with a [PcException]. */
internal class PcRequests(private val pcs: KnownPcs) {
    fun connection(): PcConnection =
        pcs.connection() ?: throw PcException(PcException.Reason.PAIR_AGAIN, "This phone isn't paired with a PC")

    /** An address on [pc]. */
    fun url(pc: Pc, build: HttpUrl.Builder.() -> Unit): HttpUrl = pc.url.toHttpUrl().newBuilder().apply(build).build()

    /**
     * Sends [request] to [pc] and hands a successful response to [read]. [client] can adjust the
     * connection (e.g. longer timeouts); [notFound] is the message when the PC answers 404.
     */
    fun <T> call(
        pc: Pc,
        request: Request.Builder,
        client: (OkHttpClient) -> OkHttpClient = { it },
        notFound: String = "That isn't on ${pc.name} anymore",
        read: (Response) -> T,
    ): T {
        val connection = connection()
        val http = client(connection.http.newBuilder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build())
        try {
            http.newCall(request.header(AUTHORIZATION, "Bearer ${connection.session}").build()).execute().use { response ->
                when {
                    response.code == 401 -> throw PcException(PcException.Reason.PAIR_AGAIN, "${pc.name} doesn't accept this phone anymore")
                    response.code == 404 -> throw PcException(PcException.Reason.NOT_FOUND, notFound)
                    !response.isSuccessful -> fail("${pc.name} answered ${response.code}")
                }
                return read(response)
            }
        } catch (e: IOException) {
            throw PcException(PcException.Reason.NOT_REACHABLE, "Couldn't reach ${pc.name}")
        }
    }

    companion object {
        const val AUTHORIZATION = "Authorization"

        fun fail(message: String): Nothing = throw PcException(PcException.Reason.FAILED, message)
    }
}
