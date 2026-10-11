package com.anzen

import com.anzen.core.DeviceKey
import com.anzen.core.DraftRules
import com.anzen.core.EventDraft
import com.anzen.core.Messages
import com.anzen.core.hexToBytes
import com.anzen.core.toHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/** Golden vectors shared with backend/tests/test_messages.py; the contract accepts those exact preimages. */
class MessagesTest {
    private val t = "0x5FbDB2315678afecb367f032d93F642f64180aa3"
    private val qx = ByteArray(32) { (it + 1).toByte() }
    private val qy = ByteArray(32) { (it + 33).toByte() }
    private val issuer = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8"

    private fun h(b: ByteArray) = Messages.sha256(b).toHex().removePrefix("0x")

    @Test fun keyId() = assertEquals("0x20a7ec84684f7fe124cb3727d049734ab0b7da2f52fcafbcef989ecfd91e870b", Messages.keyId(qx, qy).toHex())

    @Test fun create() {
        val terms = Messages.EventTerms(Messages.keyId(qx, qy), Messages.sha256("policy".toByteArray()), issuer,
            10_000_000, 20, 1_800_086_400, 1_800_172_800, 1_800_183_600, 1)
        assertEquals("18c0fcdd83456a3a8cbc13e0d31c678f844edfb511bcf20d15541c85ee05e3e4", h(Messages.create(10143, t, 3, 1_800_000_600, terms)))
    }

    @Test fun commit() = assertEquals("d7a0febc16a64dd5461c4d7bdb9804bde3796ce86bcec11ad8780a54c03c27c5",
        h(Messages.commit(10143, t, 7, 10_000_000, 4, 1_800_000_600)))

    @Test fun checkIn() = assertEquals("3ec77fc75c2c93950e193badf20080c19bee8f33706087c4df501058cd5707e1",
        h(Messages.checkIn(10143, t, 7, ByteArray(32) { 0xab.toByte() }, 1_800_172_845)))

    @Test fun cancel() = assertEquals("4d1419ebdaf75c90bdf278e1a891c8dea667214e9df00fb351211aa760ac5224",
        h(Messages.cancel(10143, t, 7, 5, 1_800_000_600)))

    @Test fun delegate() = assertEquals("1b93458a85a0df0e491a02d9b363f53722a038ff5d9b02ba1d1e4ce081e98049",
        h(Messages.delegate(10143, t, issuer, BigInteger.valueOf(7), 20_000_000, 1_800_604_800, 6, 1_800_000_600)))

    @Test fun desk() = assertEquals("ae130c5e88daa4d2c6aac044edeb9e72ece35b4a6cfec8bf8511d82a266430ab",
        h(Messages.desk(10143, t, 7, 1_800_000_300)))

    /** Exactly the document backend/tests/test_messages.py feeds to the service's policy validation. */
    @Test fun policyDocument() {
        val d = EventDraft(title = "Rooftop", creator = "Mira", venue = "Studio 4", startsAt = 1_800_180_000,
            timezone = "Asia/Kolkata", artwork = 2, disputeContact = "hi@example.com", amountUnits = "10", capacity = "20",
            commitDeadline = 1_800_086_400, checkInStart = 1_800_172_800, checkInEnd = 1_800_183_600, noShow = 1)
        assertEquals("""{"version":1,"title":"Rooftop","description":"","creator":"Mira","venue":"Studio 4","startsAt":1800180000,""" +
            """"timezone":"Asia/Kolkata","artwork":2,"disputeContact":"hi@example.com","amount":10000000,"capacity":20,""" +
            """"commitDeadline":1800086400,"checkInStart":1800172800,"checkInEnd":1800183600,"noShow":"split",""" +
            """"issuer":"$issuer"}""", DraftRules.policy(d, issuer))
    }

    @Test fun derToLowS() {
        val n = BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16)
        val r = BigInteger.ONE.shiftLeft(255).add(BigInteger.TEN) // needs a leading 0x00 in DER
        val high = n.subtract(BigInteger.valueOf(5))
        fun derInt(v: BigInteger): ByteArray { val b = v.toByteArray(); return byteArrayOf(0x02, b.size.toByte()) + b }
        val body = derInt(r) + derInt(high)
        val (rr, ss) = DeviceKey.derToLowS(byteArrayOf(0x30, body.size.toByte()) + body)
        assertEquals(32, rr.size)
        assertEquals(r, BigInteger(1, rr))
        assertEquals(BigInteger.valueOf(5), BigInteger(1, ss)) // n - s
        assertTrue(BigInteger(1, ss) <= n.shiftRight(1))
    }

    @Test fun hex() = assertArrayEquals(byteArrayOf(0x0a, 0xff.toByte()), "0x0aFF".hexToBytes())
}
