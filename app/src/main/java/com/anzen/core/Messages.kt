package com.anzen.core

import java.math.BigInteger
import java.security.MessageDigest

/**
 * The exact bytes this phone's key signs for each action: abi.encode(TAG, chainId, treasury, ...).
 * Byte-for-byte identical to AnzenTreasury.sol and backend/anzen/messages.py
 * (golden vectors in MessagesTest and backend/tests/test_messages.py).
 *
 * The app builds these itself from the terms it shows the user, so the server cannot get a
 * different action signed than the one on screen.
 */
object Messages {
    data class EventTerms(
        val organizer: ByteArray,
        val policyHash: ByteArray,
        val issuer: String,
        val amount: Long,
        val capacity: Int,
        val commitDeadline: Long,
        val checkInStart: Long,
        val checkInEnd: Long,
        val noShow: Int,
    )

    fun create(chainId: Long, treasury: String, nonce: Long, deadline: Long, t: EventTerms): ByteArray = words(
        tag("ANZEN/create/v1"), uint(chainId), address(treasury), uint(nonce), uint(deadline),
        t.organizer, t.policyHash, address(t.issuer), uint(t.amount), uint(t.capacity.toLong()),
        uint(t.commitDeadline), uint(t.checkInStart), uint(t.checkInEnd), uint(t.noShow.toLong()),
    )

    fun commit(chainId: Long, treasury: String, eventId: Long, amount: Long, nonce: Long, deadline: Long) = words(
        tag("ANZEN/commit/v1"), uint(chainId), address(treasury), uint(eventId), uint(amount), uint(nonce), uint(deadline),
    )

    fun checkIn(chainId: Long, treasury: String, eventId: Long, challenge: ByteArray, expiresAt: Long) = words(
        tag("ANZEN/checkin/v1"), uint(chainId), address(treasury), uint(eventId), challenge, uint(expiresAt),
    )

    fun cancel(chainId: Long, treasury: String, eventId: Long, nonce: Long, deadline: Long) = words(
        tag("ANZEN/cancel/v1"), uint(chainId), address(treasury), uint(eventId), uint(nonce), uint(deadline),
    )

    /** "No ERC-8004 identity": the agent isn't registered (its reservations then carry no reputation). */
    val NO_AGENT_ID: BigInteger = BigInteger.ONE.shiftLeft(256) - BigInteger.ONE

    /** Grants (cap > 0) or revokes (agent 0x0, cap 0) the AI agent's commit budget, bound to its ERC-8004 identity. */
    fun delegate(chainId: Long, treasury: String, agent: String, agentId: BigInteger, cap: Long, expiry: Long, nonce: Long, deadline: Long) = words(
        tag("ANZEN/delegate/v1"), uint(chainId), address(treasury), address(agent), uint(agentId), uint(cap), uint(expiry), uint(nonce), uint(deadline),
    )

    /** Offchain only: opens a check-in desk. The contract never accepts it. */
    fun desk(chainId: Long, treasury: String, eventId: Long, deadline: Long) = words(
        tag("ANZEN/desk/v1"), uint(chainId), address(treasury), uint(eventId), uint(deadline),
    )

    fun keyId(qx: ByteArray, qy: ByteArray): ByteArray = sha256(qx + qy)

    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    // ---- ABI words (static types only)

    internal fun tag(s: String): ByteArray = s.toByteArray(Charsets.US_ASCII).copyOf(32)

    internal fun uint(v: Long): ByteArray = uint(BigInteger.valueOf(v))

    internal fun uint(v: BigInteger): ByteArray {
        require(v.signum() >= 0 && v.bitLength() <= 256) { "uint256 out of range" }
        return leftPad(v.toByteArray().dropWhile { it == 0.toByte() }.toByteArray())
    }

    internal fun address(hex: String): ByteArray {
        val b = hex.hexToBytes()
        require(b.size == 20) { "address must be 20 bytes" }
        return leftPad(b)
    }

    private fun leftPad(b: ByteArray) = ByteArray(32 - b.size) + b

    private fun words(vararg w: ByteArray): ByteArray {
        w.forEach { require(it.size == 32) { "every word is 32 bytes" } }
        return w.fold(ByteArray(0)) { acc, b -> acc + b }
    }
}

fun ByteArray.toHex(): String = "0x" + joinToString("") { "%02x".format(it) }

fun String.hexToBytes(): ByteArray {
    val h = removePrefix("0x")
    require(h.length % 2 == 0 && h.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) { "not hex" }
    return ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
