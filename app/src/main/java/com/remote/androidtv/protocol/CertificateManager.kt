package com.remote.androidtv.protocol

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.io.IOException
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Owns the app's client certificate. The TV remembers this certificate after pairing,
 * so it is generated once and kept in the app's private storage.
 */
class CertificateManager(private val storeFile: File) {

    private val keyStore: KeyStore by lazy { loadOrCreate() }

    val certificate: X509Certificate
        get() = keyStore.getCertificate(ALIAS) as X509Certificate

    val publicKey: RSAPublicKey
        get() = certificate.publicKey as RSAPublicKey

    private val sslContext: SSLContext by lazy {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, PASSWORD)
        SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, arrayOf<TrustManager>(AcceptTvCertificate), SecureRandom())
        }
    }

    fun connect(host: String, port: Int, timeoutMs: Int = 5000): SSLSocket {
        val socket = sslContext.socketFactory.createSocket() as SSLSocket
        try {
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.soTimeout = timeoutMs
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
        try {
            socket.startHandshake()
        } catch (e: SocketTimeoutException) {
            runCatching { socket.close() }
            throw e
        } catch (e: IOException) {
            runCatching { socket.close() }
            // The TV reached us but dropped the TLS handshake (it often just resets the
            // connection when it does not know our certificate): report it as a TLS failure.
            throw e as? SSLException ?: SSLException("TLS handshake failed", e)
        }
        return socket
    }

    private fun loadOrCreate(): KeyStore {
        val ks = KeyStore.getInstance("PKCS12")
        if (storeFile.exists()) {
            runCatching {
                storeFile.inputStream().use { ks.load(it, PASSWORD) }
                if (ks.containsAlias(ALIAS)) return ks
            }
        }
        ks.load(null, null)
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val name = X500Name("CN=atvremote")
        val now = System.currentTimeMillis()
        val holder = JcaX509v3CertificateBuilder(
            name,
            BigInteger.valueOf(now),
            Date(now - DAY_MS),
            Date(now + 20 * 365 * DAY_MS),
            name,
            keyPair.public,
        ).build(JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private))
        val cert = JcaX509CertificateConverter().getCertificate(holder)
        ks.setKeyEntry(ALIAS, keyPair.private, PASSWORD, arrayOf(cert))
        storeFile.parentFile?.mkdirs()
        storeFile.outputStream().use { ks.store(it, PASSWORD) }
        return ks
    }

    /**
     * Android TVs present self-signed certificates, so there is no CA to check against.
     * The pairing code shown on the TV screen is what proves we talk to the right TV:
     * it is derived from both certificates' public keys.
     */
    private object AcceptTvCertificate : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private companion object {
        const val ALIAS = "atvremote"
        val PASSWORD = "atvremote".toCharArray()
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
