package app.sawaari.group

import android.content.Context
import android.location.Location
import app.sawaari.geo.GeoPoint
import app.sawaari.nav.RidePlan
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

object Role {
    const val LEADER = "leader"
    const val SWEEP = "sweep"
    const val RIDER = "rider"
}

data class Rider(
    val uid: String,
    val name: String,
    val role: String,
    val lat: Double?,
    val lon: Double?,
    val speedKmh: Float,
    val updatedAt: Long,
    /** When they said they're taking a break, or 0. */
    val breakAt: Long = 0L,
    /** When they last sent an SOS, or 0. */
    val sosAt: Long = 0L,
    /** When their navigation last had to reroute (missed a turn), or 0. */
    val rerouteAt: Long = 0L,
    /** Their own number and an emergency contact, only if they chose to share them with the group. */
    val phone: String = "",
    val iceName: String = "",
    val icePhone: String = "",
)

/** Where and when the group meets before the ride. Set by the leader. */
data class MeetingPoint(val point: GeoPoint, val time: Long, val note: String, val by: String)

data class SosAlert(
    val id: String,
    val uid: String,
    val name: String,
    val lat: Double?,
    val lon: Double?,
    val ts: Long,
)

data class RideGroup(
    val code: String,
    val name: String,
    val leaderUid: String,
    val riders: List<Rider>,
    /** The ride plan the leader shared, if any. */
    val plan: RidePlan? = null,
    val planBy: String = "",
    val planTs: Long = 0L,
    val meet: MeetingPoint? = null,
) {
    val leader: Rider? get() = riders.firstOrNull { it.uid == leaderUid }

    /**
     * True when the leader can't lead right now: no location update for [LEADER_SILENT_MS],
     * an SOS in the last hour, or they've left. Then any rider may take over (the rules allow it too).
     */
    fun leaderInTrouble(now: Long): Boolean {
        val l = leader ?: return true
        return (l.updatedAt > 0 && now - l.updatedAt > LEADER_SILENT_MS) || (l.sosAt > 0 && now - l.sosAt < SOS_TAKEOVER_MS)
    }

    companion object {
        const val LEADER_SILENT_MS = 10 * 60_000L
        const val SOS_TAKEOVER_MS = 60 * 60_000L
    }
}

/**
 * Ride groups over Firebase Realtime Database: create or join with a 6-letter code,
 * share your live location while the app is open, and see everyone else's.
 *
 * Data layout (see database.rules.json):
 *   groups/{code}/meta              {name, leader, createdAt}
 *   groups/{code}/members/{uid}     {name, role, joinedAt, breakAt?, sosAt?}
 *   groups/{code}/locations/{uid}   {lat, lon, speed, bearing, ts}
 *   groups/{code}/plan              {start?, stops?, end, by, ts}   (leader only)
 *   groups/{code}/meet              {lat, lon, time?, note?, by, ts} (leader only)
 *   groups/{code}/alerts/{id}       {uid, name, lat?, lon?, ts}
 */
/**
 * The app's Realtime Database. google-services.json only carries the database address if it was
 * downloaded after the database was created, so fall back to the Singapore (asia-southeast1) default.
 */
fun sawaariDatabase(): FirebaseDatabase {
    val options = FirebaseApp.getInstance().options
    val url = options.databaseUrl ?: "https://${options.projectId}-default-rtdb.asia-southeast1.firebasedatabase.app"
    return FirebaseDatabase.getInstance(url)
}

class GroupRepository(context: Context, private val scope: CoroutineScope) {

    private val prefs = context.getSharedPreferences("group", Context.MODE_PRIVATE)

    /** False until the app is built with a google-services.json. */
    val configured: Boolean = FirebaseApp.getApps(context).isNotEmpty()

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { sawaariDatabase() }

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

    private val _myName = MutableStateFlow(prefs.getString(KEY_NAME, "").orEmpty())
    val myName: StateFlow<String> = _myName.asStateFlow()

    val myUid: String? get() = if (configured) auth.currentUser?.uid else null

    private var listenerRef: DatabaseReference? = null
    private var listener: ValueEventListener? = null
    private var lastPublish = 0L

    init {
        val saved = prefs.getString(KEY_CODE, null)
        if (configured && saved != null) scope.launch { runCatching { signIn(); listen(saved) } }
    }

    fun clearError() { _error.value = null }

    fun setMyName(name: String) {
        val clean = name.trim().take(40)
        _myName.value = clean
        prefs.edit().putString(KEY_NAME, clean).apply()
        val code = _group.value?.code ?: return
        val uid = myUid ?: return
        db.getReference("groups/$code/members/$uid/name").setValue(clean)
    }

    fun create(groupName: String) = action {
        val uid = signIn()
        var code: String
        do { code = newCode() } while (db.getReference("groups/$code/meta").get().await().exists())
        db.getReference("groups/$code/meta").setValue(
            mapOf("name" to groupName.trim().ifBlank { "Weekend ride" }.take(60), "leader" to uid, "createdAt" to System.currentTimeMillis())
        ).await()
        db.getReference("groups/$code/members/$uid").setValue(member(Role.LEADER)).await()
        enter(code)
    }

    fun join(rawCode: String) = action {
        val code = rawCode.trim().uppercase()
        if (code.length != CODE_LENGTH) throw IllegalArgumentException("Codes are $CODE_LENGTH letters, like K7QM2P.")
        val uid = signIn()
        if (!db.getReference("groups/$code/meta").get().await().exists()) throw IllegalArgumentException("No ride group with code $code.")
        db.getReference("groups/$code/members/$uid").setValue(member(Role.RIDER)).await()
        enter(code)
    }

    fun leave() = action {
        val code = _group.value?.code ?: prefs.getString(KEY_CODE, null) ?: return@action
        stopListening()
        _group.value = null
        _alerts.value = emptyList()
        prefs.edit().remove(KEY_CODE).apply()
        myUid?.let { uid ->
            db.getReference("groups/$code/locations/$uid").removeValue()
            db.getReference("groups/$code/members/$uid").removeValue()
        }
    }

    /** Leader only: make [uid] the sweep (last rider). The previous sweep becomes a rider. */
    fun makeSweep(uid: String) = action {
        val g = _group.value ?: return@action
        val updates = mutableMapOf<String, Any>()
        g.riders.filter { it.role == Role.SWEEP && it.uid != uid }.forEach { updates["members/${it.uid}/role"] = Role.RIDER }
        updates["members/$uid/role"] = Role.SWEEP
        db.getReference("groups/${g.code}").updateChildren(updates).await()
    }

    /** Leader only: hand the leader role to [uid]. I become a rider. */
    fun makeLeader(uid: String) = action {
        val g = _group.value ?: return@action
        val me = myUid ?: return@action
        db.getReference("groups/${g.code}").updateChildren(
            mapOf("meta/leader" to uid, "members/$uid/role" to Role.LEADER, "members/$me/role" to Role.RIDER)
        ).await()
    }

    /**
     * Take over as leader while the leader is in trouble (see [RideGroup.leaderInTrouble]).
     * The old leader becomes a rider; if I was the sweep, the group needs a new sweep.
     */
    fun takeOverLead() = action {
        val g = _group.value ?: return@action
        val me = myUid ?: return@action
        val updates = mutableMapOf<String, Any>("meta/leader" to me, "members/$me/role" to Role.LEADER)
        if (g.leader != null && g.leaderUid != me) updates["members/${g.leaderUid}/role"] = Role.RIDER
        db.getReference("groups/${g.code}").updateChildren(updates).await()
    }

    /** Tell the group I'm stopping for a break (or that I'm riding again). */
    fun setBreak(on: Boolean) {
        val code = _group.value?.code ?: return
        val uid = myUid ?: return
        val ref = db.getReference("groups/$code/members/$uid/breakAt")
        if (on) ref.setValue(System.currentTimeMillis()) else ref.removeValue()
    }

    /** Leader only: share [plan] so every rider can load the same route. */
    fun sharePlan(plan: RidePlan) = action {
        val g = _group.value ?: return@action
        val end = plan.end ?: return@action
        fun pt(p: GeoPoint) = mapOf("lat" to p.lat, "lon" to p.lon)
        val data = mutableMapOf<String, Any>(
            "end" to pt(end),
            "stops" to plan.stops.map { pt(it) },
            "by" to _myName.value.ifBlank { "Leader" },
            "ts" to System.currentTimeMillis(),
        )
        plan.start?.let { data["start"] = pt(it) }
        db.getReference("groups/${g.code}/plan").setValue(data).await()
    }

    /** Leader only: share where and when to meet. [time] is 0 if no time is set. */
    fun setMeetingPoint(point: GeoPoint, time: Long, note: String) = action {
        val g = _group.value ?: return@action
        db.getReference("groups/${g.code}/meet").setValue(
            mapOf(
                "lat" to point.lat, "lon" to point.lon, "time" to time, "note" to note.trim().take(120),
                "by" to _myName.value.ifBlank { "Leader" }, "ts" to System.currentTimeMillis(),
            )
        ).await()
    }

    fun clearMeetingPoint() = action {
        val g = _group.value ?: return@action
        db.getReference("groups/${g.code}/meet").removeValue().await()
    }

    /** Share (or stop sharing, with blanks) my phone number and one emergency contact with the group. */
    fun shareContactDetails(phone: String, iceName: String, icePhone: String) = action {
        val g = _group.value ?: return@action
        val uid = myUid ?: return@action
        val base = "members/$uid"
        val updates = mutableMapOf<String, Any?>(
            "$base/phone" to phone.trim().take(20).ifBlank { null },
            "$base/iceName" to iceName.trim().take(40).ifBlank { null },
            "$base/icePhone" to icePhone.trim().take(20).ifBlank { null },
        )
        db.getReference("groups/${g.code}").updateChildren(updates).await()
        prefs.edit().putBoolean(KEY_SHARE_CONTACTS, phone.isNotBlank() || icePhone.isNotBlank()).apply()
    }

    /** My navigation had to reroute: lets the group know I may have taken a wrong turn. */
    fun reportReroute() {
        val code = _group.value?.code ?: return
        val uid = myUid ?: return
        db.getReference("groups/$code/members/$uid/rerouteAt").setValue(System.currentTimeMillis())
    }

    fun clearSharedPlan() = action {
        val g = _group.value ?: return@action
        db.getReference("groups/${g.code}/plan").removeValue().await()
    }

    /** Tell everyone in the group that I need help. Returns false if I'm not in a group. */
    fun sendSos(location: Location?): Boolean {
        val code = _group.value?.code ?: return false
        val uid = myUid ?: return false
        val now = System.currentTimeMillis()
        val data = mutableMapOf<String, Any>("uid" to uid, "name" to _myName.value.ifBlank { "Rider" }, "ts" to now)
        location?.let { data["lat"] = it.latitude; data["lon"] = it.longitude }
        db.getReference("groups/$code/alerts").push().setValue(data)
        // Marks me as in trouble, so if I'm the leader another rider can take over.
        db.getReference("groups/$code/members/$uid/sosAt").setValue(now)
        return true
    }

    fun dismissAlert(id: String) {
        dismissed += id
        _alerts.value = _alerts.value.filterNot { it.id == id }
    }

    /** Share my position with the group, at most every [PUBLISH_INTERVAL_MS]. */
    fun publish(location: Location) {
        val code = _group.value?.code ?: return
        val uid = myUid ?: return
        val now = System.currentTimeMillis()
        if (now - lastPublish < PUBLISH_INTERVAL_MS) return
        lastPublish = now
        // Riding again at speed ends a break automatically.
        val onBreak = _group.value?.riders?.firstOrNull { it.uid == uid }?.breakAt?.let { it > 0 } == true
        if (onBreak && location.hasSpeed() && location.speed * 3.6f > BREAK_END_KMH) setBreak(false)
        db.getReference("groups/$code/locations/$uid").setValue(
            mapOf(
                "lat" to location.latitude,
                "lon" to location.longitude,
                "speed" to if (location.hasSpeed()) location.speed.toDouble() else 0.0,
                "bearing" to if (location.hasBearing()) location.bearing.toDouble() else 0.0,
                "ts" to now,
            )
        )
    }

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

    private suspend fun signIn(): String =
        auth.currentUser?.uid ?: auth.signInAnonymously().await().user?.uid
            ?: throw IllegalStateException("Sign-in failed")

    private fun member(role: String) = mapOf(
        "name" to _myName.value.ifBlank { "Rider" },
        "role" to role,
        "joinedAt" to System.currentTimeMillis(),
    )

    private fun enter(code: String) {
        prefs.edit().putString(KEY_CODE, code).apply()
        lastPublish = 0
        listen(code)
    }

    private fun listen(code: String) {
        stopListening()
        val ref = db.getReference("groups/$code")
        val l = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.child("meta").exists() || myUid?.let { !snapshot.child("members").hasChild(it) } == true) {
                    // Group deleted or we were removed.
                    _group.value = null
                    _alerts.value = emptyList()
                    prefs.edit().remove(KEY_CODE).apply()
                    stopListening()
                    return
                }
                _group.value = parse(code, snapshot)
                _alerts.value = parseAlerts(snapshot)
            }
            override fun onCancelled(error: DatabaseError) {
                _error.value = "Lost access to the group (${error.message})."
            }
        }
        ref.addValueEventListener(l)
        listenerRef = ref
        listener = l
    }

    private fun stopListening() {
        listener?.let { listenerRef?.removeEventListener(it) }
        listener = null
        listenerRef = null
    }

    private fun parse(code: String, s: DataSnapshot): RideGroup {
        val meta = s.child("meta")
        val locations = s.child("locations")
        val riders = s.child("members").children.mapNotNull { m ->
            val uid = m.key ?: return@mapNotNull null
            val loc = locations.child(uid)
            Rider(
                uid = uid,
                name = m.child("name").getValue(String::class.java).orEmpty().ifBlank { "Rider" },
                role = m.child("role").getValue(String::class.java) ?: Role.RIDER,
                lat = loc.child("lat").getValue(Double::class.java),
                lon = loc.child("lon").getValue(Double::class.java),
                speedKmh = ((loc.child("speed").getValue(Double::class.java) ?: 0.0) * 3.6).toFloat(),
                updatedAt = loc.child("ts").getValue(Long::class.java) ?: 0L,
                breakAt = m.child("breakAt").getValue(Long::class.java) ?: 0L,
                sosAt = m.child("sosAt").getValue(Long::class.java) ?: 0L,
                rerouteAt = m.child("rerouteAt").getValue(Long::class.java) ?: 0L,
                phone = m.child("phone").getValue(String::class.java).orEmpty(),
                iceName = m.child("iceName").getValue(String::class.java).orEmpty(),
                icePhone = m.child("icePhone").getValue(String::class.java).orEmpty(),
            )
        }.sortedWith(compareBy({ roleOrder(it.role) }, { it.name.lowercase() }))
        return RideGroup(
            code = code,
            name = meta.child("name").getValue(String::class.java) ?: "Ride group",
            leaderUid = meta.child("leader").getValue(String::class.java).orEmpty(),
            riders = riders,
            plan = parsePlan(s.child("plan")),
            planBy = s.child("plan/by").getValue(String::class.java).orEmpty(),
            planTs = s.child("plan/ts").getValue(Long::class.java) ?: 0L,
            meet = parseMeet(s.child("meet")),
        )
    }

    private fun parseMeet(m: DataSnapshot): MeetingPoint? {
        val lat = m.child("lat").getValue(Double::class.java) ?: return null
        val lon = m.child("lon").getValue(Double::class.java) ?: return null
        return MeetingPoint(
            GeoPoint(lat, lon),
            m.child("time").getValue(Long::class.java) ?: 0L,
            m.child("note").getValue(String::class.java).orEmpty(),
            m.child("by").getValue(String::class.java).orEmpty(),
        )
    }

    private fun parsePlan(p: DataSnapshot): RidePlan? {
        fun pt(s: DataSnapshot): GeoPoint? {
            val lat = s.child("lat").getValue(Double::class.java) ?: return null
            val lon = s.child("lon").getValue(Double::class.java) ?: return null
            return GeoPoint(lat, lon)
        }
        val end = pt(p.child("end")) ?: return null
        val stops = p.child("stops").children.sortedBy { it.key?.toIntOrNull() ?: 0 }.mapNotNull { pt(it) }
        return RidePlan(start = pt(p.child("start")), stops = stops, end = end)
    }

    private fun parseAlerts(s: DataSnapshot): List<SosAlert> {
        val cutoff = System.currentTimeMillis() - ALERT_WINDOW_MS
        return s.child("alerts").children.mapNotNull { a ->
            val id = a.key ?: return@mapNotNull null
            val uid = a.child("uid").getValue(String::class.java) ?: return@mapNotNull null
            val ts = a.child("ts").getValue(Long::class.java) ?: 0L
            if (uid == myUid || ts < cutoff || id in dismissed) return@mapNotNull null
            SosAlert(id, uid, a.child("name").getValue(String::class.java) ?: "A rider",
                a.child("lat").getValue(Double::class.java), a.child("lon").getValue(Double::class.java), ts)
        }.sortedByDescending { it.ts }
    }

    private fun roleOrder(role: String) = when (role) { Role.LEADER -> 0; Role.SWEEP -> 2; else -> 1 }

    companion object {
        const val CODE_LENGTH = 6
        const val PUBLISH_INTERVAL_MS = 10_000L
        const val BREAK_END_KMH = 25f
        const val ALERT_WINDOW_MS = 3 * 60 * 60_000L
        private const val KEY_CODE = "code"
        private const val KEY_NAME = "name"
        private const val KEY_SHARE_CONTACTS = "share_contacts"
        // No 0/O or 1/I/L, so codes are easy to read out loud.
        private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        fun newCode(): String = (1..CODE_LENGTH).map { ALPHABET.random() }.joinToString("")
    }
}
