package com.anzen.core

import com.anzen.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** A failure with the server's (or the contract's) reason name; see [ErrorCopy] for the user-facing text. */
class AnzenException(val reason: String, val detail: String = "") : Exception("$reason $detail")

@Serializable data class Asset(val symbol: String, val decimals: Int, val label: String)

@Serializable
data class Health(
    val chainId: Long, val network: String, val explorer: String? = null, val treasury: String, val token: String,
    val issuer: String, val relayer: String, val relayerFunded: Boolean, val faucet: Boolean, val asset: Asset,
    val now: Long, val vm: String = "evm", val programId: String? = null,
) {
    val solana get() = vm == "svm"
    /** What holds and settles the money, in words. */
    val contractName get() = if (solana) "the ANZEN program on Solana" else "the treasury contract"
    /** Explorer link: the template's {tx} replaced, or the hash appended to a prefix. */
    fun txLink(tx: String): String? = explorer?.let { if ("{tx}" in it) it.replace("{tx}", tx) else it + tx }
}

@Serializable
data class Me(val status: String, val attended: Boolean, val checkedInAt: Long, val settledAmount: Long, val passportId: Long,
              /** ERC-8004 id of the agent that reserved this place; null when the owner did. */
              val agentId: Long? = null,
              /** 1 = guest scanned the venue code, 2 = venue staff scanned the guest's code. */
              val tier: Int = 0, val byAgent: Boolean = false)

/** An agent's show-up rate as reported by the ANZEN treasury itself (ERC-8004 reputation, client = treasury). */
@Serializable data class Reputation(val count: Long, val showUpRate: Double? = null, val registry: String? = null)

@Serializable
data class Event(
    val id: Long, val title: String, val description: String, val creator: String, val venue: String,
    val startsAt: Long, val timezone: String, val artwork: Int, val disputeContact: String,
    val organizer: String, val policyHash: String, val policyVerified: Boolean, val issuer: String,
    val amount: Long, val capacity: Int, val commitDeadline: Long, val checkInStart: Long, val checkInEnd: Long,
    val noShow: String, val committed: Int, val attended: Int, val cancelled: Boolean, val finalized: Boolean,
    val attendeePayout: Long, val phase: String, val now: Long, val createTx: String, val me: Me? = null,
    val category: Int = 0, val categoryName: String = "", val lateFeeBps: Int = 0,
    val waitlisted: Int = 0, val refilled: Int = 0, val released: Int = 0, val recovered: Long = 0,
)

@Serializable data class PassRulesJson(val categories: Long = 0, val maxLateFeeBps: Int = 10_000, val maxActive: Int = 0, val maxLeadDays: Int = 0)

/** The owner's show-up record, bound to their key (agents come and go; this stays). */
@Serializable data class ShowUpRecord(val attended: Int = 0, val missed: Int = 0, val releasedInTime: Int = 0, val streak: Int = 0,
                                      val bestStreak: Int = 0, val shields: Int = 0)

@Serializable data class DashboardEvent(val id: Long, val title: String, val capacity: Int, val committed: Int, val attended: Int,
                                        val refilled: Int, val released: Int, val recovered: Long, val showRate: Double? = null)
@Serializable data class DashboardTotals(val events: Int, val seats: Int, val attended: Int, val refilled: Int, val released: Int,
                                         val recovered: Long, val showRate: Double? = null)
@Serializable data class Dashboard(val events: List<DashboardEvent>, val totals: DashboardTotals)

@Serializable
data class Delegation(val agent: String, val cap: Long, val spent: Long, val expiry: Long, val active: Boolean, val remaining: Long,
                      val agentId: Long? = null, val reputation: Reputation? = null,
                      val rules: PassRulesJson? = null, val activeBookings: Int = 0)

@Serializable
data class Account(
    val keyId: String, val balance: Long, val nonce: Long, val faucetUsed: Boolean,
    val commitments: List<Event>, val organized: List<Event>, val delegation: Delegation? = null,
    val record: ShowUpRecord = ShowUpRecord(), val score: Int = 75, val scoreBasis: Int = 0,
)

@Serializable
data class Activity(val action: String, val tx: String, val status: String, val eventId: Long? = null,
                    val eventTitle: String? = null, val at: Double, val explorer: String? = null)

@Serializable data class TxStatus(val tx: String, val action: String, val status: String, val block: Long? = null,
                                  val eventId: Long? = null, val explorer: String? = null)

@Serializable data class TxRef(val action: String, val tx: String, val status: String, val block: Long? = null,
                               val explorer: String? = null)

@Serializable
data class Proof(val event: Event, val keyId: String, val participation: Me, val transactions: List<TxRef>,
                 val chainId: Long, val treasury: String, val limitations: List<String>)

@Serializable data class Desk(val token: String, val checkInStart: Long, val checkInEnd: Long)

@Serializable data class Challenge(val eventId: Long, val challenge: String, val expiresAt: Long, val issuerSig: String,
                                   val uri: String)

@Serializable private data class Submitted(val tx: String)
@Serializable private data class ErrorBody(val error: String, val detail: String = "")

@Singleton
class Api @Inject constructor(private val http: HttpClient) {
    private val json = Json { ignoreUnknownKeys = true }
    private val base = BuildConfig.BACKEND_URL.trimEnd('/')

    suspend fun health(): Health = get("/health")
    suspend fun events(query: String, key: String?): List<Event> =
        get("/events?q=${query.encodeURLParameter()}" + (key?.let { "&key=$it" } ?: ""))
    suspend fun event(id: Long, key: String?): Event = get("/events/$id" + (key?.let { "?key=$it" } ?: ""))
    suspend fun account(key: String): Account = get("/accounts/$key")
    suspend fun activity(key: String): List<Activity> = get("/accounts/$key/activity")
    suspend fun proof(eventId: Long, key: String): Proof = get("/proof/$eventId/$key")
    suspend fun tx(hash: String): TxStatus = get("/tx/$hash")
    suspend fun challenge(eventId: Long, token: String): Challenge = get("/events/$eventId/challenge?token=$token")
    suspend fun dashboard(key: String): Dashboard = get("/organizers/$key/dashboard")

    suspend fun release(eventId: Long, deadline: Long, sig: P256Signature) =
        submit("/events/$eventId/release", signed(sig, "deadline" to deadline))

    suspend fun staffCheckIn(eventId: Long, guest: PresenceCode, staff: P256Signature) = submit("/events/$eventId/staff-checkin",
        buildJsonObject {
            put("guest", buildJsonObject { put("qx", guest.qx); put("qy", guest.qy); put("auth", guest.auth) })
            put("issuedAt", guest.issuedAt)
            put("staff", signed(staff))
        })

    suspend fun pass(agent: String, agentId: java.math.BigInteger, cap: Long, expiry: Long, rules: Messages.PassRules, deadline: Long,
                     sig: P256Signature): String {
        val r = buildJsonObject {
            put("categories", rules.categories); put("maxLateFeeBps", rules.maxLateFeeBps)
            put("maxActive", rules.maxActive); put("maxLeadDays", rules.maxLeadDays)
        }
        return submit("/pass", JsonObject(signed(sig, "agent" to agent, "agentId" to agentId, "cap" to cap, "expiry" to expiry,
            "deadline" to deadline) + ("rules" to r)))
    }

    suspend fun faucet(key: String): String = submit("/faucet", buildJsonObject { put("keyId", key) })
    suspend fun settle(eventId: Long, key: String): String = submit("/events/$eventId/settle", buildJsonObject { put("keyId", key) })

    suspend fun commit(eventId: Long, deadline: Long, sig: P256Signature) =
        submit("/events/$eventId/commit", signed(sig, "deadline" to deadline))

    suspend fun cancel(eventId: Long, deadline: Long, sig: P256Signature) =
        submit("/events/$eventId/cancel", signed(sig, "deadline" to deadline))

    suspend fun checkIn(eventId: Long, c: VenueCode, sig: P256Signature) = submit("/events/$eventId/checkin",
        signed(sig, "challenge" to c.challenge, "expiresAt" to c.expiresAt, "issuerSig" to c.issuerSig))

    suspend fun delegate(agent: String, agentId: java.math.BigInteger, cap: Long, expiry: Long, deadline: Long, sig: P256Signature) =
        submit("/delegate", signed(sig, "agent" to agent, "agentId" to agentId, "cap" to cap, "expiry" to expiry, "deadline" to deadline))

    suspend fun openDesk(eventId: Long, deadline: Long, sig: P256Signature): Desk =
        json.decodeFromString(post("/events/$eventId/desk", signed(sig, "deadline" to deadline)))

    suspend fun createEvent(policy: String, terms: Messages.EventTerms, category: Int, lateFeeBps: Int, deadline: Long,
                            sig: P256Signature): String {
        val config = buildJsonObject {
            put("organizer", terms.organizer.toHex()); put("policyHash", terms.policyHash.toHex())
            put("issuer", terms.issuer); put("amount", terms.amount); put("capacity", terms.capacity)
            put("commitDeadline", terms.commitDeadline); put("checkInStart", terms.checkInStart)
            put("checkInEnd", terms.checkInEnd); put("noShow", terms.noShow)
        }
        val published = buildJsonObject { put("category", category); put("lateFeeBps", lateFeeBps) }
        return submit("/events", JsonObject(signed(sig, "deadline" to deadline, "policy" to policy) + ("config" to config) +
            ("terms" to published)))
    }

    private fun signed(sig: P256Signature, vararg extra: Pair<String, Any>) = buildJsonObject {
        put("qx", sig.qx); put("qy", sig.qy); put("auth", sig.auth)
        extra.forEach { (k, v) -> put(k, when (v) { is Number -> JsonPrimitive(v); else -> JsonPrimitive(v.toString()) }) }
    }

    private suspend fun submit(path: String, body: JsonObject): String =
        json.decodeFromString<Submitted>(post(path, body)).tx

    private suspend inline fun <reified T> get(path: String): T = json.decodeFromString(call { http.get("$base$path") })

    private suspend fun post(path: String, body: JsonObject): String = call {
        http.post("$base$path") { contentType(ContentType.Application.Json); setBody(json.encodeToString(body)) }
    }

    private suspend fun call(request: suspend () -> HttpResponse): String {
        val response = try {
            request()
        } catch (e: IOException) {
            throw AnzenException("Offline", e.javaClass.simpleName)
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            val err = runCatching { json.decodeFromString<ErrorBody>(text) }.getOrNull()
            throw AnzenException(err?.error ?: "Http${response.status.value}", err?.detail ?: "")
        }
        return text
    }
}
