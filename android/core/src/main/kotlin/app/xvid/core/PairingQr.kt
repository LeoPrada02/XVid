package app.xvid.core

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI
import java.net.URLDecoder

/**
 * What the PC's Add a phone QR code carries for the phone app:
 * `xvid://pair?pc=<the PC's https address>&code=<one-time code>&fp=<SHA-256 of the CA certificate>`.
 */
internal data class PairingQr(val pc: HttpUrl, val code: String, val fingerprint: String) {
    companion object {
        private val HEX_SHA256 = Regex("[0-9a-f]{64}")

        /** The pairing QR code in [text], or null if it's any other text. */
        fun parse(text: String): PairingQr? {
            val uri = runCatching { URI(text.trim()) }.getOrNull() ?: return null
            if (uri.scheme != "xvid" || uri.authority != "pair") return null
            val params = (uri.rawQuery ?: return null).split('&').mapNotNull { part ->
                val (key, value) = part.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
                key to URLDecoder.decode(value, "UTF-8")
            }.toMap()
            val pc = params["pc"]?.toHttpUrlOrNull()?.takeIf { it.isHttps } ?: return null
            val code = params["code"]?.takeIf { it.isNotBlank() } ?: return null
            val fingerprint = params["fp"]?.replace(":", "")?.lowercase()?.takeIf { HEX_SHA256.matches(it) } ?: return null
            return PairingQr(pc, code, fingerprint)
        }
    }
}
