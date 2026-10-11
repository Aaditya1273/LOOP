package com.anzen.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

// ------------------------------------------------------------------ venue code

/** What the desk's QR (or an anzen://checkin link) carries. Issued and signed by the event's check-in issuer. */
data class VenueCode(val eventId: Long, val challenge: String, val expiresAt: Long, val issuerSig: String) {
    companion object {
        private val HEX32 = Regex("^0x[0-9a-fA-F]{64}$")
        private val HEX65 = Regex("^0x[0-9a-fA-F]{130}$")

        /** Null for anything that isn't a well-formed ANZEN venue code. */
        fun parse(raw: String): VenueCode? = runCatching {
            val uri = URI(raw.trim())
            if (uri.scheme != "anzen" || uri.host != "checkin") return null
            val q = uri.rawQuery.split("&").associate { it.substringBefore("=") to it.substringAfter("=") }
            val code = VenueCode(q.getValue("e").toLong(), q.getValue("c"), q.getValue("x").toLong(), q.getValue("s"))
            code.takeIf { HEX32.matches(it.challenge) && HEX65.matches(it.issuerSig) && it.eventId > 0 }
        }.getOrNull()
    }
}

// ------------------------------------------------------------------ what the user can do next

enum class NextAction { Commit, Full, Closed, WaitForCheckIn, CheckIn, Missed, Settle, ViewResult, RefundAvailable, Cancelled }

/** The single primary action for an event, from onchain phase + this account's participation. */
fun nextAction(e: Event): NextAction {
    val status = e.me?.status ?: "none"
    return when {
        status == "settled" -> NextAction.ViewResult
        e.cancelled -> if (status == "none") NextAction.Cancelled else NextAction.RefundAvailable
        status == "none" -> when {
            e.phase != "open" -> NextAction.Closed
            e.committed >= e.capacity -> NextAction.Full
            else -> NextAction.Commit
        }
        e.phase == "awaiting_settlement" || e.phase == "finalized" -> NextAction.Settle
        status == "committed" && e.phase == "check_in" -> NextAction.CheckIn
        status == "committed" -> NextAction.WaitForCheckIn
        else -> NextAction.WaitForCheckIn // checked in, waiting for check-in to close
    }
}

/** The one commitment that needs the user most: check in now > settle > take a refund > next check-in. */
fun mostUrgent(commitments: List<Event>): Event? {
    val rank = mapOf(NextAction.CheckIn to 0, NextAction.Settle to 1, NextAction.RefundAvailable to 2, NextAction.WaitForCheckIn to 3)
    return commitments.filter { nextAction(it) in rank }
        .minWithOrNull(compareBy<Event>({ rank.getValue(nextAction(it)) }, { it.checkInStart }))
}

// ------------------------------------------------------------------ settlement outcome

enum class Outcome { Returned, Rewarded, NoShowApplied, CancelledRefundPending, Refunded, AwaitingSettlement, InProgress, NotParticipating }

fun outcome(e: Event): Outcome {
    val me = e.me ?: return Outcome.NotParticipating
    return when {
        me.status == "none" -> Outcome.NotParticipating
        me.status == "settled" && e.cancelled -> Outcome.Refunded
        me.status == "settled" && me.attended -> if (me.settledAmount > e.amount) Outcome.Rewarded else Outcome.Returned
        me.status == "settled" -> Outcome.NoShowApplied
        e.cancelled -> Outcome.CancelledRefundPending
        e.phase == "awaiting_settlement" || e.phase == "finalized" -> Outcome.AwaitingSettlement
        else -> Outcome.InProgress
    }
}

// ------------------------------------------------------------------ transactions

/** A financial action's lifecycle. Never collapsed into a success boolean. */
sealed interface TxState {
    data object Review : TxState
    data object Authorizing : TxState
    data object Submitting : TxState
    data class Pending(val tx: String) : TxState
    /** Included onchain; the app is re-reading contract state to confirm the expected result. */
    data class Confirmed(val tx: String) : TxState
    /** Contract state now shows the expected result. */
    data class Verified(val tx: String) : TxState
    /** Not confirmed within the wait; it may still land. Never treated as failure or success. */
    data class StillPending(val tx: String) : TxState
    data class Failed(val reason: String, val tx: String? = null) : TxState
    data object Cancelled : TxState
}

// ------------------------------------------------------------------ event draft

@Serializable
data class EventDraft(
    val step: Int = 0,
    val title: String = "",
    val description: String = "",
    val creator: String = "",
    val artwork: Int = 0,
    val startsAt: Long = 0,
    val timezone: String = ZoneId.systemDefault().id,
    val venue: String = "",
    val commitDeadline: Long = 0,
    val checkInStart: Long = 0,
    val checkInEnd: Long = 0,
    val amountUnits: String = "10",
    val capacity: String = "20",
    val noShow: Int = 1,
    val disputeContact: String = "",
)

object DraftRules {
    const val STEPS = 6

    /** Problems blocking [step]; empty means the step is complete. */
    fun problems(d: EventDraft, step: Int, now: Long): List<String> = buildList {
        when (step) {
            0 -> {
                if (d.title.isBlank()) add("Add a title.")
                if (d.title.length > 80) add("Keep the title under 80 characters.")
                if (d.creator.isBlank()) add("Add the creator's name.")
                if (d.description.length > 2000) add("Keep the description under 2,000 characters.")
            }
            1 -> {
                if (d.venue.isBlank()) add("Add where people meet.")
                if (d.startsAt <= now) add("The event must start in the future.")
                if (d.commitDeadline <= now) add("Commitments must close in the future.")
                if (d.checkInStart < d.commitDeadline) add("Check-in can't open before commitments close.")
                if (d.checkInEnd <= d.checkInStart) add("Check-in must close after it opens.")
                if (d.checkInEnd - d.checkInStart > 24 * 3600) add("Keep the check-in window under 24 hours.")
            }
            2 -> {
                val amount = amountBase(d.amountUnits)
                if (amount == null || amount <= 0) add("Enter a commitment amount above zero.")
                else if (amount > 1_000_000_000) add("Test commitments are capped at 1,000 tUSD.")
                val cap = d.capacity.toIntOrNull()
                if (cap == null || cap !in 1..10_000) add("Capacity must be between 1 and 10,000.")
            }
            3 -> if (d.disputeContact.isBlank()) add("Add a contact for disputes.")
            4 -> if (d.noShow !in 0..1) add("Choose what happens to a no-show's commitment.")
            5 -> (0..4).forEach { addAll(problems(d, it, now)) }
        }
    }

    /** "12.5" -> 12_500_000 (6 decimals). Null if not a plain positive decimal with at most 6 places. */
    fun amountBase(units: String): Long? {
        val m = Regex("""^(\d{1,7})(?:\.(\d{1,6}))?$""").matchEntire(units.trim()) ?: return null
        return m.groupValues[1].toLong() * 1_000_000 + m.groupValues[2].padEnd(6, '0').toLong()
    }

    /** The published policy document. Its sha256 goes onchain; every term here is checked against the signed config. */
    fun policy(d: EventDraft, issuer: String): String = Json.encodeToString(Policy(
        version = 1, title = d.title.trim(), description = d.description.trim(), creator = d.creator.trim(),
        venue = d.venue.trim(), startsAt = d.startsAt, timezone = d.timezone, artwork = d.artwork,
        disputeContact = d.disputeContact.trim(), amount = amountBase(d.amountUnits)!!, capacity = d.capacity.toInt(),
        commitDeadline = d.commitDeadline, checkInStart = d.checkInStart, checkInEnd = d.checkInEnd,
        noShow = if (d.noShow == 1) "split" else "organizer", issuer = issuer,
    ))

    @Serializable
    private data class Policy(
        val version: Int, val title: String, val description: String, val creator: String, val venue: String,
        val startsAt: Long, val timezone: String, val artwork: Int, val disputeContact: String, val amount: Long,
        val capacity: Int, val commitDeadline: Long, val checkInStart: Long, val checkInEnd: Long, val noShow: String,
        val issuer: String,
    )
}

// ------------------------------------------------------------------ formatting

object Format {
    /** 12_500_000 -> "12.5" (6 decimals, tabular-friendly, no trailing zeros). */
    fun amount(base: Long, decimals: Int = 6): String {
        val unit = Math.pow(10.0, decimals.toDouble()).toLong()
        val whole = base / unit
        val frac = (base % unit).toString().padStart(decimals, '0').trimEnd('0')
        val w = "%,d".format(Locale.US, whole)
        return if (frac.isEmpty()) w else "$w.$frac"
    }

    fun shortHex(h: String, head: Int = 6, tail: Int = 4) =
        if (h.length > head + tail + 1) "${h.take(head)}…${h.takeLast(tail)}" else h

    fun dateTime(epoch: Long, zone: String? = null): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withZone(zone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault())
            .format(Instant.ofEpochSecond(epoch))

    fun date(epoch: Long): String = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(epoch))

    fun time(epoch: Long): String = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        .withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(epoch))

    /** "in 3 h", "in 2 days", "5 min ago". */
    fun relative(epoch: Long, now: Long): String {
        val d = epoch - now
        val a = kotlin.math.abs(d)
        val s = when {
            a < 60 -> return if (d >= 0) "now" else "just now"
            a < 3600 -> "${a / 60} min"
            a < 86_400 -> "${a / 3600} h"
            else -> "${a / 86_400} day" + if (a / 86_400 > 1) "s" else ""
        }
        return if (d >= 0) "in $s" else "$s ago"
    }
}

// ------------------------------------------------------------------ errors

object ErrorCopy {
    fun of(reason: String): String = when (reason) {
        "Offline" -> "Can't reach ANZEN. Check your connection and try again."
        "ChainUnavailable" -> "The network isn't responding. Nothing was submitted. Try again in a moment."
        "InvalidSignature" -> "The signature didn't verify, so nothing was submitted."
        "Expired" -> "This expired before it was submitted. Nothing changed; try again."
        "UnknownEvent" -> "This event doesn't exist on this network."
        "WrongPhase" -> "That isn't possible at this stage of the event."
        "EventFull" -> "This event is full."
        "AlreadyCommitted" -> "You've already committed to this event."
        "NotCommitted" -> "This account has no open commitment for this event, or it's already checked in."
        "InsufficientBalance" -> "Not enough test dollars in this account for this commitment."
        "NotOrganizer" -> "Only the organizer's key can do this."
        "AlreadySettled" -> "This outcome was already applied."
        "PolicyMismatch", "InvalidConfig" -> "The event terms are inconsistent and weren't published."
        "InvalidDesk" -> "This check-in desk has closed. Open it again from the event."
        "FaucetUsed" -> "This account already received its test dollars."
        "FaucetDisabled" -> "Test dollars aren't available on this network."
        "InvalidCode" -> "That code isn't an ANZEN check-in code."
        "CodeForOtherEvent" -> "That code is for a different event."
        "Reverted" -> "The network rejected the transaction. Nothing changed."
        "TxFailed" -> "The transaction failed onchain. Nothing changed."
        "KeyMissing" -> "This phone has no ANZEN key. Set one up in Profile."
        "NotDelegated" -> "Your agent has no active budget. Approve one in Profile > AI agent."
        "BudgetExceeded" -> "That would go over the budget you gave your agent."
        "AuthUnavailable" -> "Authorization isn't available. Check the phone's screen lock."
        else -> "Something went wrong ($reason)."
    }
}

// ------------------------------------------------------------------ links

object Links {
    private val KEY = Regex("^0x[0-9a-fA-F]{64}$")
    private val ADDRESS = Regex("^0x[0-9a-fA-F]{40}$")

    /** anzen:// link -> app route, or null if it isn't one the app understands. */
    fun route(link: String): String? = runCatching {
        val uri = URI(link.trim())
        if (uri.scheme != "anzen") return null
        val q = uri.rawQuery?.split("&")?.associate { it.substringBefore("=") to it.substringAfter("=") }.orEmpty()
        when (uri.host) {
            "checkin" -> VenueCode.parse(link)?.let { "checkin/${it.eventId}?code=${java.net.URLEncoder.encode(link.trim(), "UTF-8")}" }
            "event" -> uri.path.trim('/').toLong().takeIf { it > 0 }?.let { "event/$it" }
            "delegate" -> {
                val agent = q.getValue("agent")
                val cap = q["cap"]?.toLongOrNull()?.coerceIn(1, 1000) ?: 20
                val days = q["days"]?.toIntOrNull()?.coerceIn(1, 30) ?: 7
                if (ADDRESS.matches(agent)) "agent?agent=$agent&cap=$cap&days=$days" else null
            }
            "proof" -> {
                val e = q.getValue("e").toLong()
                val k = q.getValue("k")
                if (e > 0 && KEY.matches(k)) "proof/$e/$k" else null
            }
            else -> null
        }
    }.getOrNull()

    fun proof(eventId: Long, keyId: String) = "anzen://proof?e=$eventId&k=$keyId"
}
