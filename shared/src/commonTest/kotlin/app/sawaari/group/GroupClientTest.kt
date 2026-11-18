package app.sawaari.group

import app.sawaari.geo.GeoPoint
import app.sawaari.nav.RidePlan
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An in-memory Realtime Database, enough to check the group logic and its data layout. */
class FakeBackend(private var uid: String) : GroupBackend {
    var root: JsonObject = JsonObject(emptMap())
    private val watchers = mutableMapOf<Long, Pair<String, (String?) -> Unit>>()
    private var next = 1L
    private var pushId = 0

    fun switchUser(newUid: String) { uid = newUid }

    override fun isConfigured() = true
    override fun currentUid() = uid
    override fun signIn(callback: (String?, String?) -> Unit) = callback(uid, null)
    override fun read(path: String, callback: (String?, String?) -> Unit) = callback(get(path)?.toString(), null)
    override fun write(path: String, json: String?, callback: (String?) -> Unit) {
        set(path, json?.let { Json.parseToJsonElement(it) }); callback(null)
    }
    override fun update(path: String, json: String, callback: (String?) -> Unit) {
        Json.parseToJsonElement(json).jsonObject.forEach { (k, v) -> set("$path/$k", v.takeUnless { it is JsonNull }) }
        callback(null)
    }
    override fun push(path: String, json: String, callback: (String?, String?) -> Unit) {
        val key = "k${pushId++}"
        set("$path/$key", Json.parseToJsonElement(json)); callback(key, null)
    }
    override fun observe(path: String, orderByChild: String?, limitToLast: Int, onValue: (String?) -> Unit, onError: (String) -> Unit): Long {
        val h = next++
        watchers[h] = path to onValue
        onValue(get(path)?.toString())
        return h
    }
    override fun stopObserving(handle: Long) { watchers.remove(handle) }

    fun get(path: String): JsonElement? =
        path.split('/').fold(root as JsonElement?) { e, k -> (e as? JsonObject)?.get(k) }

    private fun set(path: String, value: JsonElement?) {
        fun put(o: JsonObject?, keys: List<String>): JsonObject? {
            val m = (o ?: JsonObject(emptyMap())).toMutableMap()
            val k = keys.first()
            val v = if (keys.size == 1) value else put(m[k] as? JsonObject, keys.drop(1))
            if (v == null || (v is JsonObject && v.isEmpty())) m.remove(k) else m[k] = v
            return JsonObject(m)
        }
        root = put(root, path.split('/')) ?: JsonObject(emptyMap())
        watchers.values.toList().forEach { (p, cb) -> cb(get(p)?.toString()) }
    }

    private fun emptMap() = emptyMap<String, JsonElement>()
}

class MemoryStore : KeyValueStore {
    val values = mutableMapOf<String, String>()
    override fun getString(key: String) = values[key]
    override fun putString(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
}

@OptIn(ExperimentalCoroutinesApi::class)
class GroupClientTest {
    private val scope = TestScope(UnconfinedTestDispatcher())
    private var clock = 1_000_000L

    @Test fun createJoinAndSeeEachOther() {
        val db = FakeBackend("leaderUid")
        val leader = GroupClient(db, MemoryStore(), scope) { clock }
        leader.setMyName("Asha")
        leader.create("Coorg weekend")
        val g = assertNotNull(leader.group.value)
        assertEquals("Coorg weekend", g.name)
        assertEquals(6, g.code.length)
        assertEquals("leaderUid", g.leaderUid)
        // Same layout the Android app writes.
        assertEquals("\"leader\"", db.get("groups/${g.code}/members/leaderUid/role").toString())
        assertNotNull(db.get("groups/${g.code}/meta/createdAt"))

        db.switchUser("riderUid")
        val rider = GroupClient(db, MemoryStore(), scope) { clock }
        rider.setMyName("Ravi")
        rider.join(g.code.lowercase())
        assertEquals(listOf("Asha", "Ravi"), rider.group.value?.riders?.map { it.name })
        assertEquals(listOf(Role.LEADER, Role.RIDER), rider.group.value?.riders?.map { it.role })

        rider.publish(12.97, 77.59, 10.0, 90.0)
        val seen = leader.group.value!!.riders.first { it.uid == "riderUid" }
        assertEquals(12.97, seen.lat)
        assertEquals(36f, seen.speedKmh)
        assertEquals(clock, seen.updatedAt)
    }

    @Test fun joinWrongCodeShowsError() {
        val db = FakeBackend("u1")
        val c = GroupClient(db, MemoryStore(), scope) { clock }
        c.join("ABC")
        assertEquals("Codes are 6 letters, like K7QM2P.", c.error.value)
        c.join("ZZZZZZ")
        assertEquals("No ride group with code ZZZZZZ.", c.error.value)
        assertNull(c.group.value)
    }

    @Test fun planMeetingPointChatAndSos() {
        val db = FakeBackend("leaderUid")
        val leader = GroupClient(db, MemoryStore(), scope) { clock }
        leader.create("Ride")
        val code = leader.group.value!!.code
        leader.sharePlan(RidePlan(start = null, stops = listOf(GeoPoint(13.0, 77.6)), end = GeoPoint(13.37, 77.68)))
        assertEquals(GeoPoint(13.37, 77.68), leader.group.value?.plan?.end)
        assertEquals(1, leader.group.value?.plan?.stops?.size)
        leader.setMeetingPoint(GeoPoint(12.9, 77.5), 0L, "Petrol bunk")
        assertEquals("Petrol bunk", leader.group.value?.meet?.note)
        leader.sendText("Fuel stop")
        assertEquals(listOf("Fuel stop"), leader.chat.value.map { it.text })

        db.switchUser("riderUid")
        val rider = GroupClient(db, MemoryStore(), scope) { clock }
        rider.join(code)
        assertTrue(rider.sendSos(12.95, 77.6))
        // The leader sees the rider's SOS; the rider doesn't alert themselves.
        db.switchUser("leaderUid")
        leader.clearError()
        assertTrue(rider.alerts.value.isEmpty() || rider.alerts.value.none { it.uid == "riderUid" })
        assertEquals(clock, db.get("groups/$code/members/riderUid/sosAt").toString().toLong())
    }

    @Test fun voiceNotesUseTheAndroidLayout() = kotlinx.coroutines.test.runTest {
        val db = FakeBackend("leaderUid")
        val leader = GroupClient(db, MemoryStore(), scope) { clock }
        leader.create("Ride")
        val code = leader.group.value!!.code
        leader.sendVoice("AAAAGGZ0eXBNNEEg", 4200)
        val msg = leader.chat.value.single()
        assertEquals(4200L, msg.voiceMs)
        assertEquals("\"AAAAGGZ0eXBNNEEg\"", db.get("chatVoice/$code/${msg.id}").toString())
        assertEquals("AAAAGGZ0eXBNNEEg", leader.loadVoice(msg.id))
        leader.sendVoice("x".repeat(GroupClient.VOICE_MAX_BASE64 + 1), 31_000)
        assertEquals(1, leader.chat.value.size)
        assertNotNull(leader.error.value)
    }

    @Test fun leaveRemovesMe() {
        val db = FakeBackend("u1")
        val store = MemoryStore()
        val c = GroupClient(db, store, scope) { clock }
        c.create("Ride")
        val code = c.group.value!!.code
        assertEquals(code, store.values["group.code"])
        c.leave()
        assertNull(c.group.value)
        assertNull(db.get("groups/$code/members/u1"))
        assertNull(store.values["group.code"])
    }
}
