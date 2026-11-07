package app.sawaari.group

import android.content.Context
import android.location.Location
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
)

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
)

/**
 * Ride groups over Firebase Realtime Database: create or join with a 6-letter code,
 * share your live location while the app is open, and see everyone else's.
 *
 * Data layout (see database.rules.json):
 *   groups/{code}/meta              {name, leader, createdAt}
 *   groups/{code}/members/{uid}     {name, role, joinedAt}
 *   groups/{code}/locations/{uid}   {lat, lon, speed, bearing, ts}
 */
class GroupRepository(context: Context, private val scope: CoroutineScope) {

    private val prefs = context.getSharedPreferences("group", Context.MODE_PRIVATE)

    /** False until the app is built with a google-services.json. */
    val configured: Boolean = FirebaseApp.getApps(context).isNotEmpty()

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy {
        // google-services.json only carries the database address if it was downloaded after
        // the database was created, so fall back to the Singapore (asia-southeast1) default.
        val options = FirebaseApp.getInstance().options
        val url = options.databaseUrl ?: "https://${options.projectId}-default-rtdb.asia-southeast1.firebasedatabase.app"
        FirebaseDatabase.getInstance(url)
    }

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

    /** Tell everyone in the group that I need help. Returns false if I'm not in a group. */
    fun sendSos(location: Location?): Boolean {
        val code = _group.value?.code ?: return false
        val uid = myUid ?: return false
        val data = mutableMapOf<String, Any>("uid" to uid, "name" to _myName.value.ifBlank { "Rider" }, "ts" to System.currentTimeMillis())
        location?.let { data["lat"] = it.latitude; data["lon"] = it.longitude }
        db.getReference("groups/$code/alerts").push().setValue(data)
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
            )
        }.sortedWith(compareBy({ roleOrder(it.role) }, { it.name.lowercase() }))
        return RideGroup(
            code = code,
            name = meta.child("name").getValue(String::class.java) ?: "Ride group",
            leaderUid = meta.child("leader").getValue(String::class.java).orEmpty(),
            riders = riders,
        )
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
        const val ALERT_WINDOW_MS = 3 * 60 * 60_000L
        private const val KEY_CODE = "code"
        private const val KEY_NAME = "name"
        // No 0/O or 1/I/L, so codes are easy to read out loud.
        private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        fun newCode(): String = (1..CODE_LENGTH).map { ALPHABET.random() }.joinToString("")
    }
}
