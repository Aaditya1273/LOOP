package com.anzen.core

import android.app.KeyguardManager
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** A signature as the contract takes it: the key's coordinates and a low-s (r, s). */
data class P256Signature(val qx: String, val qy: String, val r: String, val s: String)

class AuthorizationCancelled : Exception("authorization cancelled")
class AuthorizationUnavailable(msg: String) : Exception(msg)

/**
 * The account key: EC P-256 in Android Keystore (hardware-backed where the phone supports it).
 * Every use needs the phone's biometric or screen lock, and each prompt names the one action it signs.
 *
 * This is a device-bound key, not a synced passkey: it lives only on this phone. It is verified onchain as
 * a raw P-256 signature (Monad's precompile), not as a WebAuthn assertion.
 * Private key material never leaves Keystore and is never logged.
 */
class DeviceKey(private val context: Context) {
    private val ks get() = KeyStore.getInstance(PROVIDER).apply { load(null) }

    fun exists(): Boolean = ks.containsAlias(ALIAS)

    fun canCreate(): Boolean = context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    fun create() {
        if (!canCreate()) throw AuthorizationUnavailable("Set a screen lock on this phone first.")
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).apply {
            initialize(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(true)
                    .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                    // Adding a fingerprint must not destroy the key: it holds the account's funds.
                    .setInvalidatedByBiometricEnrollment(false)
                    .build()
            )
        }.generateKeyPair()
    }

    /** Removes the key from this phone. Funds held by it become unreachable. */
    fun delete() = ks.deleteEntry(ALIAS)

    private val publicKey: ECPublicKey get() = ks.getCertificate(ALIAS).publicKey as ECPublicKey
    val qx: ByteArray get() = coord(publicKey.w.affineX)
    val qy: ByteArray get() = coord(publicKey.w.affineY)
    val keyId: String get() = Messages.keyId(qx, qy).toHex()

    /** Shows the system prompt with [title]/[subtitle], then signs [message] (SHA256withECDSA). */
    suspend fun sign(activity: FragmentActivity, title: String, subtitle: String, message: ByteArray): P256Signature {
        val sig = Signature.getInstance("SHA256withECDSA").apply { initSign(ks.getKey(ALIAS, null) as PrivateKey) }
        val authorized = suspendCancellableCoroutine { cont ->
            val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (cont.isActive) cont.resume(result.cryptoObject?.signature ?: sig)
                    }

                    override fun onAuthenticationError(code: Int, msg: CharSequence) {
                        if (!cont.isActive) return
                        val cancelled = code in setOf(BiometricPrompt.ERROR_USER_CANCELED,
                            BiometricPrompt.ERROR_NEGATIVE_BUTTON, BiometricPrompt.ERROR_CANCELED)
                        cont.resumeWithException(if (cancelled) AuthorizationCancelled() else AuthorizationUnavailable(msg.toString()))
                    }
                })
            prompt.authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
                    .build(),
                BiometricPrompt.CryptoObject(sig),
            )
            cont.invokeOnCancellation { prompt.cancelAuthentication() }
        }
        authorized.update(message)
        val (r, s) = derToLowS(authorized.sign())
        return P256Signature(qx.toHex(), qy.toHex(), r.toHex(), s.toHex())
    }

    companion object {
        private const val PROVIDER = "AndroidKeyStore"
        private const val ALIAS = "anzen-account-v1"
        private val N = BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16)

        private fun coord(v: BigInteger): ByteArray = v.toByteArray().takeLast(32).toByteArray().let { ByteArray(32 - it.size) + it }

        /** DER ECDSA signature -> 32-byte r and low-s s (the contract rejects high-s, like OpenZeppelin's P256). */
        fun derToLowS(der: ByteArray): Pair<ByteArray, ByteArray> {
            var i = 2 // 0x30 len
            fun int(): BigInteger {
                require(der[i] == 0x02.toByte()) { "bad DER" }
                val len = der[i + 1].toInt()
                val v = BigInteger(1, der.copyOfRange(i + 2, i + 2 + len))
                i += 2 + len
                return v
            }
            val r = int()
            var s = int()
            if (s > N.shiftRight(1)) s = N - s
            return coord(r) to coord(s)
        }
    }
}
