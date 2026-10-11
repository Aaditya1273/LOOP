package com.anzen

import com.anzen.core.DraftRules
import com.anzen.core.ErrorCopy
import com.anzen.core.Event
import com.anzen.core.EventDraft
import com.anzen.core.Format
import com.anzen.core.Links
import com.anzen.core.Me
import com.anzen.core.NextAction
import com.anzen.core.Outcome
import com.anzen.core.VenueCode
import com.anzen.core.mostUrgent
import com.anzen.core.nextAction
import com.anzen.core.outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesTest {
    private val now = 1_800_000_000L

    private fun event(phase: String = "open", status: String = "none", attended: Boolean = false, settled: Long = 0,
                      passport: Long = 0, committed: Int = 1, capacity: Int = 20, cancelled: Boolean = false,
                      checkInStart: Long = now + 7200, id: Long = 1) = Event(
        id = id, title = "Rooftop", description = "", creator = "Mira", venue = "Studio 4", startsAt = now + 9000,
        timezone = "UTC", artwork = 0, disputeContact = "x", organizer = "0x" + "11".repeat(32),
        policyHash = "0x" + "22".repeat(32), policyVerified = true, issuer = "0x" + "33".repeat(20), amount = 10_000_000,
        capacity = capacity, commitDeadline = now + 3600, checkInStart = checkInStart, checkInEnd = checkInStart + 3600,
        noShow = "split", committed = committed, attended = 0, cancelled = cancelled, finalized = phase == "finalized",
        attendeePayout = 0, phase = phase, now = now, createTx = "0x" + "44".repeat(32),
        me = Me(status, attended, if (attended) now else 0, settled, passport))

    private val code = "anzen://checkin?e=7&c=0x${"ab".repeat(32)}&x=1800000045&s=0x${"cd".repeat(65)}"

    // ---- venue codes

    @Test fun parsesVenueCode() {
        val c = VenueCode.parse(code)!!
        assertEquals(7L, c.eventId)
        assertEquals(1_800_000_045L, c.expiresAt)
    }

    @Test fun rejectsMalformedCodes() {
        assertNull(VenueCode.parse("https://example.com"))
        assertNull(VenueCode.parse("anzen://checkin?e=7&c=0x12&x=1&s=0x34")) // short challenge/sig
        assertNull(VenueCode.parse("anzen://checkin?e=7"))
        assertNull(VenueCode.parse("anzen://checkin?e=-1&c=0x${"ab".repeat(32)}&x=1&s=0x${"cd".repeat(65)}"))
        assertNull(VenueCode.parse(""))
    }

    @Test fun routesLinks() {
        assertTrue(Links.route(code)!!.startsWith("checkin/7?code="))
        assertEquals("event/12", Links.route("anzen://event/12"))
        val k = "0x" + "aa".repeat(32)
        assertEquals("proof/3/$k", Links.route(Links.proof(3, k)))
        assertNull(Links.route("anzen://proof?e=3&k=0x12"))
        assertEquals("agent?agent=0x1c3c7d2915352282C0419F125580B288e58f6c17&cap=20&days=7",
            Links.route("anzen://delegate?agent=0x1c3c7d2915352282C0419F125580B288e58f6c17&cap=20&days=7"))
        assertEquals("agent?agent=0x1c3c7d2915352282C0419F125580B288e58f6c17&cap=1000&days=30", // clamped
            Links.route("anzen://delegate?agent=0x1c3c7d2915352282C0419F125580B288e58f6c17&cap=99999&days=365"))
        assertNull(Links.route("anzen://delegate?agent=0xnothex&cap=20"))
        assertNull(Links.route("anzen://wallet/drain"))
        assertNull(Links.route("javascript:alert(1)"))
    }

    // ---- next action and outcomes

    @Test fun nextActionFollowsPhaseAndStatus() {
        assertEquals(NextAction.Commit, nextAction(event()))
        assertEquals(NextAction.Full, nextAction(event(committed = 20)))
        assertEquals(NextAction.Closed, nextAction(event(phase = "closed")))
        assertEquals(NextAction.WaitForCheckIn, nextAction(event(phase = "closed", status = "committed")))
        assertEquals(NextAction.CheckIn, nextAction(event(phase = "check_in", status = "committed")))
        assertEquals(NextAction.WaitForCheckIn, nextAction(event(phase = "check_in", status = "checked_in", attended = true)))
        assertEquals(NextAction.Settle, nextAction(event(phase = "awaiting_settlement", status = "committed")))
        assertEquals(NextAction.ViewResult, nextAction(event(phase = "finalized", status = "settled")))
        assertEquals(NextAction.RefundAvailable, nextAction(event(phase = "cancelled", status = "committed", cancelled = true)))
        assertEquals(NextAction.Cancelled, nextAction(event(phase = "cancelled", cancelled = true)))
    }

    @Test fun outcomesNeverClaimSuccessBeforeSettlement() {
        assertEquals(Outcome.InProgress, outcome(event(phase = "check_in", status = "checked_in", attended = true)))
        assertEquals(Outcome.AwaitingSettlement, outcome(event(phase = "awaiting_settlement", status = "checked_in", attended = true)))
        assertEquals(Outcome.Returned, outcome(event(phase = "finalized", status = "settled", attended = true, settled = 10_000_000, passport = 1)))
        assertEquals(Outcome.Rewarded, outcome(event(phase = "finalized", status = "settled", attended = true, settled = 15_000_000, passport = 1)))
        assertEquals(Outcome.NoShowApplied, outcome(event(phase = "finalized", status = "settled")))
        assertEquals(Outcome.CancelledRefundPending, outcome(event(phase = "cancelled", status = "committed", cancelled = true)))
        assertEquals(Outcome.Refunded, outcome(event(phase = "cancelled", status = "settled", cancelled = true, settled = 10_000_000)))
        assertEquals(Outcome.NotParticipating, outcome(event()))
    }

    @Test fun mostUrgentPrefersCheckInThenSettlement() {
        val waiting = event(id = 1, phase = "closed", status = "committed", checkInStart = now + 100)
        val settle = event(id = 2, phase = "awaiting_settlement", status = "checked_in", attended = true)
        val checkIn = event(id = 3, phase = "check_in", status = "committed")
        assertEquals(3L, mostUrgent(listOf(waiting, settle, checkIn))!!.id)
        assertEquals(2L, mostUrgent(listOf(waiting, settle))!!.id)
        assertEquals(1L, mostUrgent(listOf(waiting))!!.id)
        assertNull(mostUrgent(listOf(event(phase = "finalized", status = "settled"))))
    }

    // ---- draft validation

    private val good = EventDraft(title = "Rooftop", creator = "Mira", venue = "Studio 4", startsAt = now + 9000,
        commitDeadline = now + 3600, checkInStart = now + 7200, checkInEnd = now + 10_800, disputeContact = "hi@x.io")

    @Test fun completeDraftHasNoProblems() = assertEquals(emptyList<String>(), DraftRules.problems(good, 5, now))

    @Test fun contradictoryScheduleIsBlocked() {
        assertTrue(DraftRules.problems(good.copy(checkInStart = now + 1000), 1, now).any { "before commitments close" in it })
        assertTrue(DraftRules.problems(good.copy(checkInEnd = good.checkInStart), 1, now).any { "close after it opens" in it })
        assertTrue(DraftRules.problems(good.copy(startsAt = now - 1), 1, now).isNotEmpty())
        assertTrue(DraftRules.problems(good.copy(title = ""), 5, now).isNotEmpty()) // review re-checks every step
    }

    @Test fun amountsParseExactly() {
        assertEquals(12_500_000L, DraftRules.amountBase("12.5"))
        assertEquals(1L, DraftRules.amountBase("0.000001"))
        assertNull(DraftRules.amountBase("0.0000001"))
        assertNull(DraftRules.amountBase("1,000"))
        assertNull(DraftRules.amountBase("-1"))
        assertNull(DraftRules.amountBase(""))
        assertTrue(DraftRules.problems(good.copy(amountUnits = "0"), 2, now).isNotEmpty())
        assertTrue(DraftRules.problems(good.copy(capacity = "0"), 2, now).isNotEmpty())
    }

    // ---- formatting and errors

    @Test fun formatsAmounts() {
        assertEquals("12.5", Format.amount(12_500_000))
        assertEquals("1,000", Format.amount(1_000_000_000))
        assertEquals("0.000001", Format.amount(1))
        assertEquals("0", Format.amount(0))
    }

    @Test fun formatsRelativeTimes() {
        assertEquals("in 2 h", Format.relative(now + 7200, now))
        assertEquals("5 min ago", Format.relative(now - 300, now))
        assertEquals("in 3 days", Format.relative(now + 3 * 86_400, now))
    }

    @Test fun everyContractErrorHasCopy() {
        listOf("InvalidSignature", "Expired", "WrongPhase", "EventFull", "AlreadyCommitted", "NotCommitted", "InsufficientBalance",
            "NotOrganizer", "AlreadySettled", "UnknownEvent", "InvalidConfig", "Offline", "ChainUnavailable").forEach {
            assertNotNull(ErrorCopy.of(it))
            assertTrue("$it has specific copy", !ErrorCopy.of(it).startsWith("Something went wrong"))
        }
    }
}
