package net.pendzialek.closeby

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

object Secrets {
    private val random = SecureRandom()

    // no 0/O, 1/I/L: codes are typed in by hand
    private const val CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    fun newId(): String = UUID.randomUUID().toString()

    fun newToken(): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun newCode(length: Int): String = buildString {
        repeat(length) { append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]) }
    }

    /** Accepts "abcd-efgh", "ABCD EFGH", ... */
    fun normalizeCode(code: String): String = code.uppercase().filter { it.isLetterOrDigit() }

    fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
}
