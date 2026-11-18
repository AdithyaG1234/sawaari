package app.sawaari.group

import app.sawaari.geo.GeoPoint
import app.sawaari.nav.RidePlan
import app.sawaari.plans.Plans
import app.sawaari.util.nowMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Ride groups over Firebase Realtime Database, shared code for the iPhone app. It reads and writes
 * exactly the same data as the Android app's GroupRepository, so riders on both phones share groups:
 *
 *   groups/{code}/meta              {name, leader, createdAt}
 *   groups/{code}/slots/{n}         uid    (numbered seats: 1-3 free, more need the leader's Sawaari Pro)
 *   groups/{code}/members/{uid}     {name, role, slot, joinedAt, breakAt?, sosAt?, rerouteAt?, phone?, iceName?, icePhone?}
 *   subs/{uid}                      {until, plan}  (written only by the server after checking the store)
 *   groups/{code}/locations/{uid}   {lat, lon, speed, bearing, ts}
 *   groups/{code}/plan              {start?, stops?, end, by, ts}   (leader only)
 *   groups/{code}/meet              {lat, lon, time?, note?, by, ts} (leader only)
 *   groups/{code}/alerts/{id}       {uid, name, lat?, lon?, ts}
 *   chats/{code}/{id}               {uid, name, ts, text?, voiceMs?}
 */
class GroupClient(
    private val backend: GroupBackend,
    private val prefs: KeyValueStore,
    private val scope: CoroutineScope,
    private val now: () -> Long = ::nowMillis,
) {
    val configured: Boolean = backend.isConfigured()

    private val _group = MutableStateFlow<RideGroup?>(null)
    val group: StateFlow<RideGroup?> = _group.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val dismissed = mutableSetOf<String>()
    private val _alerts = MutableStateFlow<List<SosAlert>>(emptyList())
    /** SOS alerts from other riders in the last few hours that this rider hasn't dismissed. */
    val alerts: StateFlow<List<SosAlert>> = _alerts.asStateFlow()

    private val _myName = MutableStateFlow(prefs.getString(KEY_NAME).orEmpty())
    val myName: StateFlow<String> = _myName.asStateFlow()

    private val _chat = MutableStateFlow<List<ChatMessage>>(emptyList())
    /** The last [CHAT_KEEP] chat messages of my group, oldest first. */
    val chat: StateFlow<List<ChatMessage>> = _chat.asStateFlow()

    val myUid: String? get() = if (configured) backend.currentUid() else null

    private var groupHandle: Long? = null
    private var chatHandle: Long? = null
    private var lastPublish = 0L

    init {
        val saved = prefs.getString(KEY_CODE)
        if (configured && saved != null) scope.launch { runCatching { signIn(); listen(saved) } }
    }

    fun clearError() { _error.value = null }

    fun setMyName(name: String) {
        val clean = name.trim().take(40)
        _myName.value = clean
        prefs.putString(KEY_NAME, clean)
        val code = _group.value?.code ?: return
        val uid = myUid ?: return
        backend.write("groups/$code/members/$uid/name", JsonPrimitive(clean).toString()) {}
    }

    fun create(groupName: String) = action {
        val uid = signIn()
        var code: String
        do { code = newCode() } while (read("groups/$code/meta") != null)
        write("groups/$code/meta", buildJsonObject {
            put("name", groupName.trim().ifBlank { "Weekend ride" }.take(60))
            put("leader", uid)
            put("createdAt", now())
        })
        takeSeat(code, uid, Role.LEADER, leaderUid = uid)
        enter(code)
    }

    fun join(rawCode: String) = action {
        val code = rawCode.trim().uppercase()
        if (code.length != CODE_LENGTH) throw IllegalArgumentException("Codes are $CODE_LENGTH letters, like K7QM2P.")
        val uid = signIn()
        val meta = read("groups/$code/meta") as? JsonObject ?: throw IllegalArgumentException("No ride group with code $code.")
        val leader = (meta["leader"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        takeSeat(code, uid, Role.RIDER, leader)
        enter(code)
    }

    /**
     * Claim the lowest free seat and join in one write, so the server can check the seat limit.
     * Two riders may race for the same seat; the loser just tries the next one.
     */
    private suspend fun takeSeat(code: String, uid: String, role: String, leaderUid: String) {
        val pro = Plans.subscriptionActive(
            ((read("subs/$leaderUid") as? JsonObject)?.get("until") as? JsonPrimitive)?.longOrNull, now(),
        )
        repeat(3) {
            val slots = (read("groups/$code/slots") as? JsonObject).orEmpty()
            val mine = slots.entries.firstOrNull { (it.value as? JsonPrimitive)?.contentOrNull == uid }?.key
            val seat = mine ?: Plans.freeSeat(slots.keys, pro)?.toString()
                ?: throw IllegalArgumentException(Plans.GROUP_FULL_MESSAGE)
            val ok = runCatching {
                update("groups/$code", buildJsonObject {
                    put("slots/$seat", uid)
                    put("members/$uid", JsonObject(member(role) + ("slot" to JsonPrimitive(seat))))
                })
            }.isSuccess
            if (ok) return
        }
        // Reads worked but the server refused every seat: the group filled up (or the leader's Pro ended).
        throw IllegalArgumentException(Plans.GROUP_FULL_MESSAGE)
    }

    fun leave() = action {
        val code = _group.value?.code ?: prefs.getString(KEY_CODE) ?: return@action
        stopListening()
        _group.value = null
        _alerts.value = emptyList()
        prefs.putString(KEY_CODE, null)
        myUid?.let { uid ->
            backend.write("groups/$code/locations/$uid", null) {}
            backend.write("groups/$code/members/$uid", null) {}
            freeSeats(code, uid)
        }
    }

    /** Leader only: take [uid] out of the group, freeing their seat (e.g. someone who changed phones). */
    fun removeRider(uid: String) = action {
        val g = _group.value ?: return@action
        update("groups/${g.code}", buildJsonObject {
            put("locations/$uid", JsonNull)
            put("members/$uid", JsonNull)
        })
        freeSeats(g.code, uid)
    }

    private suspend fun freeSeats(code: String, uid: String) {
        val slots = runCatching { read("groups/$code/slots") as? JsonObject }.getOrNull() ?: return
        slots.filter { (it.value as? JsonPrimitive)?.contentOrNull == uid }.keys.forEach { n ->
            backend.write("groups/$code/slots/$n", null) {}
        }
    }

    /** Leader only: make [uid] the sweep (last rider). The previous sweep becomes a rider. */
    fun makeSweep(uid: String) = action {
        val g = _group.value ?: return@action
        update("groups/${g.code}", buildJsonObject {
            g.riders.filter { it.role == Role.SWEEP && it.uid != uid }.forEach { put("members/${it.uid}/role", Role.RIDER) }
            put("members/$uid/role", Role.SWEEP)
        })
    }

    /** Leader only: hand the leader role to [uid]. I become a rider. */
    fun makeLeader(uid: String) = action {
        val g = _group.value ?: return@action
        val me = myUid ?: return@action
        update("groups/${g.code}", buildJsonObject {
            put("meta/leader", uid)
            put("members/$uid/role", Role.LEADER)
            put("members/$me/role", Role.RIDER)
        })
    }

    /** Take over as leader while the leader is in trouble (see [RideGroup.leaderInTrouble]). */
    fun takeOverLead() = action {
        val g = _group.value ?: return@action
        val me = myUid ?: return@action
        update("groups/${g.code}", buildJsonObject {
            put("meta/leader", me)
            put("members/$me/role", Role.LEADER)
            if (g.leader != null && g.leaderUid != me) put("members/${g.leaderUid}/role", Role.RIDER)
        })
    }

    /** Leader only: make [uid] a marshal, medic or plain rider. */
    fun setRole(uid: String, role: String) = action {
        val g = _group.value ?: return@action
        require(role in Role.ASSIGNABLE) { "That role can't be given here." }
        write("groups/${g.code}/members/$uid/role", JsonPrimitive(role))
    }

    /** Send a text (or quick) message to the group. */
    fun sendText(text: String) = action {
        val t = text.trim().take(500)
        if (t.isEmpty()) return@action
        val code = _group.value?.code ?: return@action
        val uid = myUid ?: return@action
        try {
            push("chats/$code", buildJsonObject {
                put("uid", uid)
                put("name", _myName.value.ifBlank { "Rider" })
                put("ts", now())
                put("text", t)
            })
        } catch (e: Exception) {
            throw IllegalArgumentException("Couldn't send. Check your internet.")
        }
    }

    /**
     * Send a voice note: [base64Audio] is the recorded AAC (.m4a) file in Base64, the same format and place
     * (chatVoice/{code}/{message id}) as on Android, so riders on either phone can play it.
     */
    fun sendVoice(base64Audio: String, durationMs: Long) = action {
        val code = _group.value?.code ?: return@action
        val uid = myUid ?: return@action
        require(base64Audio.length <= VOICE_MAX_BASE64) { "That voice note is too long. Keep it under 30 seconds." }
        val id = try {
            push("chats/$code", buildJsonObject {
                put("uid", uid)
                put("name", _myName.value.ifBlank { "Rider" })
                put("ts", now())
                put("voiceMs", durationMs.coerceAtMost(30_000L))
            })
        } catch (e: Exception) {
            throw IllegalArgumentException("Couldn't send. Check your internet.")
        }
        write("chatVoice/$code/$id", JsonPrimitive(base64Audio))
    }

    /** The audio of a voice note in Base64, or null if it can't be loaded. */
    suspend fun loadVoice(id: String): String? {
        val code = _group.value?.code ?: return null
        return runCatching { (read("chatVoice/$code/$id") as? JsonPrimitive)?.contentOrNull }.getOrNull()
    }

    /** Tell the group I'm stopping for a break (or that I'm riding again). */
    fun setBreak(on: Boolean) {
        val code = _group.value?.code ?: return
        val uid = myUid ?: return
        backend.write("groups/$code/members/$uid/breakAt", if (on) now().toString() else null) {}
    }

    /** Leader only: share [plan] so every rider can load the same route. */
    fun sharePlan(plan: RidePlan) = action {
        val g = _group.value ?: return@action
        val end = plan.end ?: return@action
        write("groups/${g.code}/plan", buildJsonObject {
            put("end", point(end))
            put("stops", JsonArray(plan.stops.map { point(it) }))
            put("by", _myName.value.ifBlank { "Leader" })
            put("ts", now())
            plan.start?.let { put("start", point(it)) }
        })
    }

    fun clearSharedPlan() = action {
        val g = _group.value ?: return@action
        write("groups/${g.code}/plan", null)
    }

    /** Leader only: share where and when to meet. [time] is 0 if no time is set. */
    fun setMeetingPoint(point: GeoPoint, time: Long, note: String) = action {
        val g = _group.value ?: return@action
        write("groups/${g.code}/meet", buildJsonObject {
            put("lat", point.lat); put("lon", point.lon); put("time", time); put("note", note.trim().take(120))
            put("by", _myName.value.ifBlank { "Leader" }); put("ts", now())
        })
    }

    fun clearMeetingPoint() = action {
        val g = _group.value ?: return@action
        write("groups/${g.code}/meet", null)
    }

    /** Share (or stop sharing, with blanks) my phone number and one emergency contact with the group. */
    fun shareContactDetails(phone: String, iceName: String, icePhone: String) = action {
        val g = _group.value ?: return@action
        val uid = myUid ?: return@action
        fun v(s: String, max: Int) = s.trim().take(max).ifBlank { null }?.let { JsonPrimitive(it) } ?: JsonNull
        update("groups/${g.code}", JsonObject(mapOf(
            "members/$uid/phone" to v(phone, 20),
            "members/$uid/iceName" to v(iceName, 40),
            "members/$uid/icePhone" to v(icePhone, 20),
        )))
    }

    /** My navigation had to reroute: lets the group know I may have taken a wrong turn. */
    fun reportReroute() {
        val code = _group.value?.code ?: return
        val uid = myUid ?: return
        backend.write("groups/$code/members/$uid/rerouteAt", now().toString()) {}
    }

    /** Tell everyone in the group that I need help. Returns false if I'm not in a group. */
    fun sendSos(lat: Double?, lon: Double?): Boolean {
        val code = _group.value?.code ?: return false
        val uid = myUid ?: return false
        val t = now()
        val data = buildJsonObject {
            put("uid", uid); put("name", _myName.value.ifBlank { "Rider" }); put("ts", t)
            if (lat != null && lon != null) { put("lat", lat); put("lon", lon) }
        }
        backend.push("groups/$code/alerts", data.toString()) { _, _ -> }
        // Marks me as in trouble, so if I'm the leader another rider can take over.
        backend.write("groups/$code/members/$uid/sosAt", t.toString()) {}
        return true
    }

    fun dismissAlert(id: String) {
        dismissed += id
        _alerts.value = _alerts.value.filterNot { it.id == id }
    }

    /**
     * Share my position with the group, at most every [PUBLISH_INTERVAL_MS].
     * [speedMps] and [bearing] are negative when unknown.
     */
    fun publish(lat: Double, lon: Double, speedMps: Double, bearing: Double) {
        val code = _group.value?.code ?: return
        val uid = myUid ?: return
        val t = now()
        if (t - lastPublish < PUBLISH_INTERVAL_MS) return
        lastPublish = t
        // Riding again at speed ends a break automatically.
        val onBreak = _group.value?.riders?.firstOrNull { it.uid == uid }?.breakAt?.let { it > 0 } == true
        if (onBreak && speedMps * 3.6 > BREAK_END_KMH) setBreak(false)
        backend.write("groups/$code/locations/$uid", buildJsonObject {
            put("lat", lat); put("lon", lon)
            put("speed", if (speedMps >= 0) speedMps else 0.0)
            put("bearing", if (bearing >= 0) bearing else 0.0)
            put("ts", t)
        }.toString()) {}
    }

    // --- internals ---

    private fun action(block: suspend () -> Unit) {
        if (!configured) { _error.value = "Group rides aren't set up in this build yet."; return }
        scope.launch {
            _busy.value = true
            try {
                block()
            } catch (e: IllegalArgumentException) {
                _error.value = e.message
            } catch (e: Exception) {
                _error.value = "Couldn't reach the group server. Check your internet and try again."
            } finally {
                _busy.value = false
            }
        }
    }

    private suspend fun signIn(): String = backend.currentUid() ?: suspendCancellableCoroutine { c ->
        backend.signIn { uid, error ->
            if (uid != null) c.resume(uid) else c.resumeWithException(IllegalStateException(error ?: "Sign-in failed"))
        }
    }

    private suspend fun read(path: String): JsonElement? = suspendCancellableCoroutine { c ->
        backend.read(path) { json, error ->
            if (error != null) c.resumeWithException(IllegalStateException(error)) else c.resume(parse(json))
        }
    }

    private suspend fun write(path: String, value: JsonElement?) = suspendCancellableCoroutine { c ->
        backend.write(path, value?.toString()) { error ->
            if (error != null) c.resumeWithException(IllegalStateException(error)) else c.resume(Unit)
        }
    }

    private suspend fun update(path: String, values: JsonObject) = suspendCancellableCoroutine { c ->
        backend.update(path, values.toString()) { error ->
            if (error != null) c.resumeWithException(IllegalStateException(error)) else c.resume(Unit)
        }
    }

    private suspend fun push(path: String, value: JsonObject): String = suspendCancellableCoroutine { c ->
        backend.push(path, value.toString()) { key, error ->
            if (key != null) c.resume(key) else c.resumeWithException(IllegalStateException(error ?: "Write failed"))
        }
    }

    private fun member(role: String) = buildJsonObject {
        put("name", _myName.value.ifBlank { "Rider" })
        put("role", role)
        put("joinedAt", now())
    }

    private fun point(p: GeoPoint) = buildJsonObject { put("lat", p.lat); put("lon", p.lon) }

    private fun enter(code: String) {
        prefs.putString(KEY_CODE, code)
        lastPublish = 0
        listen(code)
    }

    private fun listen(code: String) {
        stopListening()
        groupHandle = backend.observe("groups/$code", null, 0, onValue = { json ->
            val g = parse(json) as? JsonObject
            val uid = myUid
            if (g == null || g["meta"] !is JsonObject || (uid != null && g.obj("members")?.containsKey(uid) != true)) {
                // Group deleted or we were removed.
                _group.value = null
                _alerts.value = emptyList()
                prefs.putString(KEY_CODE, null)
                stopListening()
            } else {
                _group.value = parseGroup(code, g)
                _alerts.value = parseAlerts(g)
            }
        }, onError = { message ->
            _error.value = "Lost access to the group ($message)."
        })
        chatHandle = backend.observe("chats/$code", "ts", CHAT_KEEP, onValue = { json ->
            _chat.value = parseChat(parse(json))
        }, onError = { /* Older group servers without chat rules: chat just stays empty. */ })
    }

    private fun stopListening() {
        groupHandle?.let(backend::stopObserving)
        chatHandle?.let(backend::stopObserving)
        groupHandle = null
        chatHandle = null
        _chat.value = emptyList()
    }

    internal fun parseGroup(code: String, s: JsonObject): RideGroup {
        val meta = s.obj("meta")
        val locations = s.obj("locations")
        val riders = s.obj("members").orEmpty().mapNotNull { (uid, value) ->
            val m = value as? JsonObject ?: return@mapNotNull null
            val loc = locations?.obj(uid)
            Rider(
                uid = uid,
                name = m.str("name").orEmpty().ifBlank { "Rider" },
                role = m.str("role") ?: Role.RIDER,
                lat = loc?.num("lat"),
                lon = loc?.num("lon"),
                speedKmh = ((loc?.num("speed") ?: 0.0) * 3.6).toFloat(),
                updatedAt = loc?.long("ts") ?: 0L,
                breakAt = m.long("breakAt") ?: 0L,
                sosAt = m.long("sosAt") ?: 0L,
                rerouteAt = m.long("rerouteAt") ?: 0L,
                phone = m.str("phone").orEmpty(),
                iceName = m.str("iceName").orEmpty(),
                icePhone = m.str("icePhone").orEmpty(),
            )
        }.sortedWith(compareBy({ roleOrder(it.role) }, { it.name.lowercase() }))
        val plan = s.obj("plan")
        return RideGroup(
            code = code,
            name = meta?.str("name") ?: "Ride group",
            leaderUid = meta?.str("leader").orEmpty(),
            riders = riders,
            plan = plan?.let(::parsePlan),
            planBy = plan?.str("by").orEmpty(),
            planTs = plan?.long("ts") ?: 0L,
            meet = s.obj("meet")?.let(::parseMeet),
        )
    }

    private fun parseMeet(m: JsonObject): MeetingPoint? {
        val lat = m.num("lat") ?: return null
        val lon = m.num("lon") ?: return null
        return MeetingPoint(GeoPoint(lat, lon), m.long("time") ?: 0L, m.str("note").orEmpty(), m.str("by").orEmpty())
    }

    private fun parsePlan(p: JsonObject): RidePlan? {
        fun pt(e: JsonElement?): GeoPoint? {
            val o = e as? JsonObject ?: return null
            return GeoPoint(o.num("lat") ?: return null, o.num("lon") ?: return null)
        }
        val end = pt(p["end"]) ?: return null
        // Firebase returns a short list as an array, or as an object keyed "0", "1", ... when it has gaps.
        val stops = when (val s = p["stops"]) {
            is JsonArray -> s.mapNotNull { pt(it) }
            is JsonObject -> s.entries.sortedBy { it.key.toIntOrNull() ?: 0 }.mapNotNull { pt(it.value) }
            else -> emptyList()
        }
        return RidePlan(start = pt(p["start"]), stops = stops, end = end)
    }

    private fun parseAlerts(s: JsonObject): List<SosAlert> {
        val cutoff = now() - ALERT_WINDOW_MS
        val me = myUid
        return s.obj("alerts").orEmpty().mapNotNull { (id, value) ->
            val a = value as? JsonObject ?: return@mapNotNull null
            val uid = a.str("uid") ?: return@mapNotNull null
            val ts = a.long("ts") ?: 0L
            if (uid == me || ts < cutoff || id in dismissed) return@mapNotNull null
            SosAlert(id, uid, a.str("name") ?: "A rider", a.num("lat"), a.num("lon"), ts)
        }.sortedByDescending { it.ts }
    }

    private fun parseChat(e: JsonElement?): List<ChatMessage> {
        val entries: List<Pair<String, JsonElement>> = when (e) {
            is JsonObject -> e.entries.map { it.key to it.value }
            else -> emptyList()
        }
        return entries.mapNotNull { (id, value) ->
            val m = value as? JsonObject ?: return@mapNotNull null
            ChatMessage(
                id = id,
                uid = m.str("uid") ?: return@mapNotNull null,
                name = m.str("name").orEmpty().ifBlank { "Rider" },
                ts = m.long("ts") ?: 0L,
                text = m.str("text").orEmpty(),
                voiceMs = m.long("voiceMs") ?: 0L,
            )
        }.sortedBy { it.ts }
    }

    private fun roleOrder(role: String) = when (role) { Role.LEADER -> 0; Role.MARSHAL -> 1; Role.MEDIC -> 2; Role.SWEEP -> 4; else -> 3 }

    companion object {
        /** 90 KB of audio (Android's limit, about 30 s) in Base64. */
        const val VOICE_MAX_BASE64 = 120_000

        const val CODE_LENGTH = 6
        const val CHAT_KEEP = 60
        const val PUBLISH_INTERVAL_MS = 10_000L
        const val BREAK_END_KMH = 25.0
        const val ALERT_WINDOW_MS = 3 * 60 * 60_000L
        private const val KEY_CODE = "group.code"
        private const val KEY_NAME = "group.name"
        // No 0/O or 1/I/L, so codes are easy to read out loud. Same as Android.
        private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        fun newCode(): String = (1..CODE_LENGTH).map { ALPHABET.random() }.joinToString("")

        private val json = Json { ignoreUnknownKeys = true }
        internal fun parse(text: String?): JsonElement? =
            text?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }?.takeUnless { it is JsonNull }
    }
}

private fun JsonObject.obj(key: String) = this[key] as? JsonObject
private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
private fun JsonObject.num(key: String) = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
private fun JsonObject.long(key: String) = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
@Suppress("unused") private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull
