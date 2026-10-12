package com.anzen.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.fragment.app.FragmentActivity
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

data class AppState(
    val health: Health? = null,
    val account: Account? = null,
    val events: List<Event> = emptyList(),
    val activity: List<Activity> = emptyList(),
    val loading: Boolean = true,
    /** Reason name of the last refresh failure; data from before it stays visible but is marked stale. */
    val error: String? = null,
    val hasKey: Boolean = false,
)

/**
 * Single source of truth for everything shown across screens. All money and status values come from the
 * contract via the service; nothing here is computed optimistically.
 */
@Singleton
class Repository @Inject constructor(
    private val api: Api,
    val key: DeviceKey,
    val prefs: Prefs,
    private val reminders: Reminders,
) {
    private val _state = MutableStateFlow(AppState(hasKey = key.exists()))
    val state: StateFlow<AppState> = _state

    val keyId: String? get() = if (key.exists()) key.keyId else null

    suspend fun refresh() {
        _state.update { it.copy(loading = true, hasKey = key.exists()) }
        try {
            val health = api.health()
            val id = keyId
            val events = api.events("", id)
            val account = id?.let { api.account(it) }
            val activity = id?.let { api.activity(it) } ?: emptyList()
            _state.update { AppState(health, account, events, activity, loading = false, error = null, hasKey = id != null) }
            account?.commitments?.let { if (prefs.reminders) reminders.schedule(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = reasonOf(e)) }
        }
    }

    suspend fun search(query: String): List<Event> = api.events(query, keyId)
    suspend fun event(id: Long): Event = api.event(id, keyId)
    suspend fun proof(eventId: Long, keyId: String): Proof = api.proof(eventId, keyId)
    suspend fun health(): Health = _state.value.health ?: api.health()

    fun createKey() {
        key.create()
        _state.update { it.copy(hasKey = true) }
    }

    fun removeKey() {
        reminders.cancel(_state.value.account?.commitments.orEmpty())
        key.delete()
        _state.update { AppState(health = it.health, loading = false, hasKey = false) }
    }

    private fun requireKey(): String = keyId ?: throw AnzenException("KeyMissing")

    // ------------------------------------------------------------------ actions

    /**
     * Runs one financial action through its whole lifecycle, reporting each state:
     * authorize on this phone (if [sign] is set) -> submit -> wait for inclusion -> re-read contract state until
     * [verified] holds. A timeout leaves it StillPending, never Failed or Verified.
     */
    suspend fun perform(
        activity: FragmentActivity?,
        sign: Signing?,
        submit: suspend (P256Signature?) -> String,
        verified: suspend () -> Boolean,
        onState: (TxState) -> Unit,
    ) {
        var tx: String? = null
        try {
            val sig = sign?.let {
                onState(TxState.Authorizing)
                key.sign(activity ?: throw AnzenException("AuthUnavailable"), it.title, it.subtitle, it.message())
            }
            onState(TxState.Submitting)
            tx = submit(sig)
            onState(TxState.Pending(tx))
            when (awaitTx(tx)) {
                "confirmed" -> {
                    onState(TxState.Confirmed(tx))
                    repeat(20) {
                        if (verified()) {
                            onState(TxState.Verified(tx))
                            refresh()
                            return
                        }
                        delay(500)
                    }
                    onState(TxState.StillPending(tx)) // included, but state not yet readable
                }
                "failed" -> onState(TxState.Failed("TxFailed", tx))
                else -> onState(TxState.StillPending(tx))
            }
            refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (e: AuthorizationCancelled) {
            onState(TxState.Cancelled)
        } catch (e: Exception) {
            log("action", reasonOf(e))
            onState(TxState.Failed(reasonOf(e), tx))
        }
    }

    class Signing(val title: String, val subtitle: String, val message: suspend () -> ByteArray)

    /** Polls the relay record until the transaction is included or fails. */
    private suspend fun awaitTx(tx: String, timeoutMs: Long = 45_000): String {
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            val s = runCatching { api.tx(tx).status }.getOrNull()
            if (s == "confirmed" || s == "failed") return s
            delay(800)
        }
        return "pending"
    }

    suspend fun commit(activity: FragmentActivity, e: Event, onState: (TxState) -> Unit) {
        val h = health()
        var deadline = 0L
        perform(activity,
            Signing("Commit ${Format.amount(e.amount)} ${h.asset.symbol}", "to “${e.title}” · ${h.network}") {
                val acct = api.account(requireKey())
                deadline = h.now.coerceAtLeast(System.currentTimeMillis() / 1000) + 600
                Messages.commit(h.chainId, h.treasury, e.id, e.amount, acct.nonce, deadline)
            },
            submit = { api.commit(e.id, deadline, it!!) },
            verified = { event(e.id).me?.status in setOf("committed", "waitlisted") },
            onState = onState)
    }

    suspend fun checkIn(activity: FragmentActivity, e: Event, code: VenueCode, onState: (TxState) -> Unit) {
        val h = health()
        perform(activity,
            Signing("Check in to “${e.title}”", "Signs this venue's code for event #${e.id}") {
                Messages.checkIn(h.chainId, h.treasury, e.id, code.challenge.hexToBytes(), code.expiresAt)
            },
            submit = { api.checkIn(e.id, code, it!!) },
            verified = { event(e.id).me?.attended == true },
            onState = onState)
    }

    suspend fun cancelEvent(activity: FragmentActivity, e: Event, onState: (TxState) -> Unit) {
        val h = health()
        var deadline = 0L
        perform(activity,
            Signing("Cancel “${e.title}”", "Everyone who committed can take a full refund") {
                val acct = api.account(requireKey())
                deadline = h.now.coerceAtLeast(System.currentTimeMillis() / 1000) + 600
                Messages.cancel(h.chainId, h.treasury, e.id, acct.nonce, deadline)
            },
            submit = { api.cancel(e.id, deadline, it!!) },
            verified = { event(e.id).cancelled },
            onState = onState)
    }

    suspend fun settle(e: Event, onState: (TxState) -> Unit) = perform(null, null,
        submit = { api.settle(e.id, requireKey()) },
        verified = { event(e.id).me?.status == "settled" },
        onState = onState)

    suspend fun faucet(onState: (TxState) -> Unit) {
        val before = _state.value.account?.balance ?: 0
        perform(null, null,
            submit = { api.faucet(requireKey()) },
            verified = { api.account(requireKey()).balance > before },
            onState = onState)
    }

    suspend fun createEvent(activity: FragmentActivity, d: EventDraft, onState: (TxState) -> Unit, onCreated: (Long) -> Unit) {
        val h = health()
        val policy = DraftRules.policy(d, h.issuer)
        val terms = Messages.EventTerms(
            organizer = requireKey().hexToBytes(), policyHash = Messages.sha256(policy.toByteArray()), issuer = h.issuer,
            amount = DraftRules.amountBase(d.amountUnits)!!, capacity = d.capacity.toInt(),
            commitDeadline = d.commitDeadline, checkInStart = d.checkInStart, checkInEnd = d.checkInEnd, noShow = d.noShow,
        )
        var deadline = 0L
        var txHash = ""
        perform(activity,
            Signing("Publish “${d.title.trim()}”", "Terms are fixed once published · ${h.network}") {
                val acct = api.account(requireKey())
                deadline = h.now.coerceAtLeast(System.currentTimeMillis() / 1000) + 600
                Messages.createV2(h.chainId, h.treasury, acct.nonce, deadline, terms, d.category, d.lateFeeBps)
            },
            submit = { api.createEvent(policy, terms, d.category, d.lateFeeBps, deadline, it!!).also { tx -> txHash = tx } },
            verified = { api.tx(txHash).eventId?.also(onCreated) != null },
            onState = onState)
    }

    /** Approves (cap > 0) or revokes (agent = zero address, cap 0) the AI agent's budget. */
    suspend fun delegate(activity: FragmentActivity, agent: String, agentId: java.math.BigInteger, cap: Long, expiry: Long,
                         onState: (TxState) -> Unit) {
        val h = health()
        var deadline = 0L
        val revoking = cap == 0L
        perform(activity,
            Signing(if (revoking) "Revoke your agent's budget" else "Let your agent spend up to ${Format.amount(cap)} ${h.asset.symbol}",
                if (revoking) "It won't be able to reserve anything" else "Reservations only, until ${Format.date(expiry)} · ${h.network}") {
                val acct = api.account(requireKey())
                deadline = h.now.coerceAtLeast(System.currentTimeMillis() / 1000) + 600
                Messages.delegate(h.chainId, h.treasury, agent, agentId, cap, expiry, acct.nonce, deadline)
            },
            submit = { api.delegate(agent, agentId, cap, expiry, deadline, it!!) },
            verified = { api.account(requireKey()).delegation?.let { d -> d.cap == cap && d.agent.equals(agent, true) } == true },
            onState = onState)
    }

    /** Gives the place back (or leaves the waitlist). Only the owner's key can; the agent never can. */
    suspend fun release(activity: FragmentActivity, e: Event, onState: (TxState) -> Unit) {
        val h = health()
        val t = releaseTerms(e)
        var deadline = 0L
        perform(activity,
            Signing(if (e.me?.status == "waitlisted") "Leave the waitlist" else "Give your place back",
                "${Format.amount(t.refund)} ${h.asset.symbol} back" + (if (t.fee > 0) " · up to ${Format.amount(t.fee)} late fee" else "") + " · “${e.title}”") {
                val acct = api.account(requireKey())
                deadline = h.now.coerceAtLeast(System.currentTimeMillis() / 1000) + 600
                Messages.release(h.chainId, h.treasury, e.id, acct.nonce, deadline)
            },
            submit = { api.release(e.id, deadline, it!!) },
            verified = { event(e.id).me?.status == "released" },
            onState = onState)
    }

    /** An Agent Pass: budget plus rules, bound by one signature. */
    suspend fun grantPass(activity: FragmentActivity, agent: String, agentId: java.math.BigInteger, cap: Long, expiry: Long,
                          rules: Messages.PassRules, onState: (TxState) -> Unit) {
        val h = health()
        var deadline = 0L
        perform(activity,
            Signing("Agent Pass: up to ${Format.amount(cap)} ${h.asset.symbol}", "${Categories.describe(rules.categories)} · " +
                "until ${Format.date(expiry)} · ${h.network}") {
                val acct = api.account(requireKey())
                deadline = h.now.coerceAtLeast(System.currentTimeMillis() / 1000) + 600
                Messages.grantPass(h.chainId, h.treasury, agent, agentId, cap, expiry, rules, acct.nonce, deadline)
            },
            submit = { api.pass(agent, agentId, cap, expiry, rules, deadline, it!!) },
            verified = { api.account(requireKey()).delegation?.let { d -> d.cap == cap && d.agent.equals(agent, true) } == true },
            onState = onState)
    }

    /** Guest (silver tier): signs a fresh code for the venue's staff to scan. Valid [PresenceCode.VALID_SECONDS]. */
    suspend fun presenceCode(activity: FragmentActivity, e: Event): PresenceCode {
        val h = api.health() // fresh chain time: the code is checked against it
        val issuedAt = h.now
        val sig = key.sign(activity, "Show check-in code", "For venue staff to scan · “${e.title}”",
            Messages.present(h.chainId, h.treasury, e.id, issuedAt))
        return PresenceCode(e.id, sig.qx, sig.qy, issuedAt, sig.auth)
    }

    /** Organizer: countersigns one scanned guest code and submits the check-in. */
    suspend fun staffCheckIn(activity: FragmentActivity, e: Event, guest: PresenceCode, onState: (TxState) -> Unit) {
        val h = health()
        var before = -1
        perform(activity,
            Signing("Check in this guest", "Countersign their code for “${e.title}”") {
                before = event(e.id).attended
                Messages.staff(h.chainId, h.treasury, e.id, guest.keyId, guest.issuedAt)
            },
            submit = { api.staffCheckIn(e.id, guest, it!!) },
            verified = { event(e.id).attended > before },
            onState = onState)
    }

    suspend fun dashboard(): Dashboard = api.dashboard(requireKey())

    /** Organizer: signs once to open a desk, then fetches fresh venue codes with the returned token. */
    suspend fun openDesk(activity: FragmentActivity, e: Event): Desk {
        val h = health()
        val deadline = h.now.coerceAtLeast(System.currentTimeMillis() / 1000) + 300
        val sig = key.sign(activity, "Open check-in desk", "Show rotating venue codes for “${e.title}”",
            Messages.desk(h.chainId, h.treasury, e.id, deadline))
        return api.openDesk(e.id, deadline, sig)
    }

    suspend fun venueCode(eventId: Long, token: String): Challenge = api.challenge(eventId, token)

    companion object {
        fun reasonOf(e: Throwable): String = when (e) {
            is AnzenException -> e.reason
            is AuthorizationUnavailable -> "AuthUnavailable"
            else -> "Client"
        }

        // Never logs keys, signatures or personal data.
        fun log(stage: String, result: String) = Log.i("ANZEN", "stage=$stage result=$result")
    }
}

/** Non-sensitive preferences and the event draft. Nothing secret is stored here (the key lives in Keystore). */
@Singleton
class Prefs @Inject constructor(private val sp: SharedPreferences) {
    private val json = Json { ignoreUnknownKeys = true }

    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false)
        set(v) = sp.edit().putBoolean("onboarded", v).apply()

    var reminders: Boolean
        get() = sp.getBoolean("reminders", true)
        set(v) = sp.edit().putBoolean("reminders", v).apply()

    /** "system" | "light" | "dark" */
    var appearance: String
        get() = sp.getString("appearance", "system") ?: "system"
        set(v) = sp.edit().putString("appearance", v).apply()

    var draft: EventDraft?
        get() = sp.getString("draft", null)?.let { runCatching { json.decodeFromString<EventDraft>(it) }.getOrNull() }
        set(v) = sp.edit().apply { if (v == null) remove("draft") else putString("draft", json.encodeToString(v)) }.apply()
}

@InstallIn(SingletonComponent::class)
@Module
object CoreModule {
    @Provides @Singleton
    fun prefs(@ApplicationContext ctx: Context): SharedPreferences = ctx.getSharedPreferences("anzen", Context.MODE_PRIVATE)

    @Provides @Singleton
    fun deviceKey(@ApplicationContext ctx: Context) = DeviceKey(ctx)

    @Provides @Singleton
    fun httpClient(): HttpClient = HttpClient(Android) {
        engine {
            connectTimeout = 10_000
            socketTimeout = 60_000 // settle may wait for finalize to confirm
        }
    }
}
