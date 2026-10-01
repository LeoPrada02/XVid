package app.xvid.core

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * How the phone app trusts the PCs' private certificate authority (made by mkcert on the home PC)
 * for its own connections only. Nothing here touches Android's system-wide trust store, so no other
 * app on the phone trusts that CA.
 */
internal object PcTrust {
    /** The SHA-256 of the certificate's DER bytes, in lowercase hex, as the pairing QR code carries it. */
    fun fingerprint(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }

    /** Parses one PEM certificate; throws [java.security.cert.CertificateException] if it isn't one. */
    fun parsePem(pem: String): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(pem.byteInputStream()) as X509Certificate

    /**
     * A client that trusts only [ca]: the normal certificate checks, with [ca] as the only root. Any
     * certificate [ca] signed is accepted whatever address it was made for, since a PC's address can
     * change after its certificate was made, and only the household's own PCs have certificates from
     * [ca] (they share its login too, so telling them apart by certificate would protect nothing).
     */
    fun pinnedTo(ca: X509Certificate, base: OkHttpClient): OkHttpClient {
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("xvid-ca", ca)
        }
        val trustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(keyStore) }
            .trustManagers.filterIsInstance<X509TrustManager>().single()
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
        return base.newBuilder()
            .sslSocketFactory(context.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    /**
     * Only for fetching the PC's public CA certificate while pairing, before anything is trusted.
     * It sends nothing secret, and what it gets back is only used if it matches the fingerprint
     * from the QR code (see [KnownPcs.pair]).
     */
    fun unverified(base: OkHttpClient): OkHttpClient {
        val acceptAnything = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(acceptAnything), null) }
        return base.newBuilder()
            .connectionPool(ConnectionPool()) // never hand these connections to a verified client
            .sslSocketFactory(context.socketFactory, acceptAnything)
            .hostnameVerifier { _, _ -> true }
            .build()
    }
}
