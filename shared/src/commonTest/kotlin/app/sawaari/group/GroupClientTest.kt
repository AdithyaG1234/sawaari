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
import app.sawaari.plans.Plans
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An in-memory Realtime Database, enough to check the group logic and its data layout. */
class FakeBackend(private var uid: String, private val d: FakeDb = FakeDb()) : GroupBackend {
    /** The data and watchers, shared by every signed-in user of the same fake database. */
    class FakeDb {
        var root: JsonObject = JsonObject(emptyMap())
        val watchers = mutableMapOf<Long, Pair<String, (String?) -> Unit>>()
        var next = 1L
        var pushId = 0
    }
    private var root: JsonObject
        get() = d.root
        set(v) { d.root = v }
    private val watchers get() = d.watchers

    fun switchUser(newUid: String) { uid = newUid }

    /** Another phone signed in as [other], on the same database. */
    fun asUser(other: String) = FakeBackend(other, d)

    override fun isConfigured() = true
    override fun currentUid() = uid
    override fun signIn(callback: (String?, String?) -> Unit) = callback(uid, null)
    override fun read(path: String, callback: (String?, String?) -> Unit) = callback(get(path)?.toString(), null)
    override fun write(path: String, json: String?, callback: (String?) -> Unit) {
        set(path, json?.let { Json.parseToJsonElement(it) }); callback(null)
    }
    override fun update(path: String, json: String, callback: (String?) -> Unit) {
        // Like Firebase, a multi-path update lands all at once: watchers see it only when it's complete.
        Json.parseToJsonElement(json).jsonObject.forEach { (k, v) -> set("$path/$k", v.takeUnless { it is JsonNull }, notify = false) }
        notifyWatchers()
        callback(null)
    }
    override fun push(path: String, json: String, callback: (String?, String?) -> Unit) {
        val key = "k${d.pushId++}"
        set("$path/$key", Json.parseToJsonElement(json)); callback(key, null)
    }
    override fun observe(path: String, orderByChild: String?, limitToLast: Int, onValue: (String?) -> Unit, onError: (String) -> Unit): Long {
        val h = d.next++
        watchers[h] = path to onValue
        onValue(get(path)?.toString())
        return h
    }
    override fun stopObserving(handle: Long) { watchers.remove(handle) }

    fun get(path: String): JsonElement? =
        path.split('/').fold(root as JsonElement?) { e, k -> (e as? JsonObject)?.get(k) }

    private fun notifyWatchers() = watchers.values.toList().forEach { (p, cb) -> cb(get(p)?.toString()) }

    private fun set(path: String, value: JsonElement?, notify: Boolean = true) {
        fun put(o: JsonObject?, keys: List<String>): JsonObject? {
            val m = (o ?: JsonObject(emptyMap())).toMutableMap()
            val k = keys.first()
            val v = if (keys.size == 1) value else put(m[k] as? JsonObject, keys.drop(1))
            if (v == null || (v is JsonObject && v.isEmpty())) m.remove(k) else m[k] = v
            return JsonObject(m)
        }
        root = put(root, path.split('/')) ?: JsonObject(emptyMap())
        if (notify) notifyWatchers()
    }

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

    @Test fun scheduleAndAnyRiderStartsTheRide() {
        val db = FakeBackend("leaderUid")
        val leader = GroupClient(db, MemoryStore(), scope) { clock }
        leader.create("Goa")
        val code = leader.group.value!!.code
        leader.sharePlan(
            RidePlan(stops = listOf(GeoPoint(15.0, 74.0), GeoPoint(15.2, 74.1)), end = GeoPoint(15.5, 73.8)),
            names = mapOf(GeoPoint(15.5, 73.8) to "Panaji"),
        )
        assertEquals(mapOf("end" to "Panaji"), leader.group.value?.planNames)
        leader.saveSchedule(RideSchedule(start = StopTime(clock + 60_000), stops = mapOf(1 to StopTime(clock + 120_000, "Lunch")), end = StopTime(clock + 300_000)))
        val s = leader.group.value?.schedule
        assertEquals(clock + 60_000, s?.start?.at)
        assertEquals("Lunch", s?.stops?.get(1)?.name)
        assertNull(s?.stops?.get(0))
        assertEquals(clock + 300_000, s?.end?.at)
        assertNull(leader.group.value?.ride)

        db.switchUser("riderUid")
        val rider = GroupClient(db, MemoryStore(), scope) { clock }
        rider.join(code)
        rider.startRide()
        assertEquals(RideStatus(RideStatus.STARTED, clock, "Rider", "riderUid"), rider.group.value?.ride)
        rider.endRide()
        assertTrue(rider.group.value?.ride?.ended == true)

        rider.shareGear(mapOf("d7" to "Puncture repair kit"))
        assertEquals(mapOf("riderUid" to listOf("Puncture repair kit")), rider.group.value?.sharedGear)
        rider.shareGear(emptyMap())
        assertTrue(rider.group.value?.sharedGear?.isEmpty() == true)

        db.switchUser("leaderUid")
        leader.saveSchedule(null)
        assertNull(db.get("groups/$code/schedule"))
    }

    @Test fun clearingTheRideClearsItsSchedule() {
        val db = FakeBackend("leaderUid")
        val leader = GroupClient(db, MemoryStore(), scope) { clock }
        leader.create("Goa")
        val code = leader.group.value!!.code
        val plan = RidePlan(stops = listOf(GeoPoint(15.0, 74.0)), end = GeoPoint(15.5, 73.8))
        val times = RideSchedule(start = StopTime(clock + 60_000), end = StopTime(clock + 300_000))

        // "Clear schedule" keeps the route but drops the times and started/ended.
        leader.sharePlan(plan); leader.saveSchedule(times); leader.startRide()
        leader.clearSchedule()
        assertNotNull(leader.group.value?.plan)
        assertNull(leader.group.value?.schedule)
        assertNull(leader.group.value?.ride)

        // "Clear ride" removes the route, the schedule and the ride state together.
        leader.saveSchedule(times); leader.endRide()
        leader.clearSharedPlan()
        assertNull(db.get("groups/$code/plan"))
        assertNull(db.get("groups/$code/schedule"))
        assertNull(db.get("groups/$code/ride"))
        assertTrue(app.sawaari.ride.Schedule.upcoming(leader.group.value!!, clock).isEmpty())
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

@OptIn(ExperimentalCoroutinesApi::class)
class GroupSeatsTest {
    private val scope = TestScope(UnconfinedTestDispatcher())
    private val clock = 1_000_000L

    private fun joinAs(db: FakeBackend, uid: String, code: String): GroupClient =
        GroupClient(db.asUser(uid), MemoryStore(), scope, pricingOn = true) { clock }.also { it.join(code) }

    private fun givePro(db: FakeBackend, vararg uids: String) =
        uids.forEach { db.write("subs/$it", """{"until":${clock + 1000},"plan":"yearly"}""") {} }

    @Test fun freeGroupStopsAtThreeRiders() {
        val db = FakeBackend("lead")
        val leader = GroupClient(db, MemoryStore(), scope, pricingOn = true) { clock }
        leader.create("Ride")
        val code = leader.group.value!!.code
        assertEquals("\"lead\"", db.get("groups/$code/slots/1").toString())
        assertEquals("\"1\"", db.get("groups/$code/members/lead/slot").toString())

        joinAs(db, "r2", code)
        joinAs(db, "r3", code)
        val fourth = joinAs(db, "r4", code)
        assertNull(fourth.group.value)
        assertEquals(Plans.NEED_PRO_TO_BE_FOURTH, fourth.error.value)
        assertNull(db.get("groups/$code/members/r4"))
    }

    @Test fun pastThreeEveryoneNeedsPro() {
        val db = FakeBackend("lead")
        givePro(db, "lead", "r2", "r4", "r6")
        val leader = GroupClient(db, MemoryStore(), scope, pricingOn = true) { clock }
        leader.create("Ride")
        val code = leader.group.value!!.code
        val r2 = joinAs(db, "r2", code)
        val r3 = joinAs(db, "r3", code)
        // r4 has Pro, but r3 (one of the first three) doesn't.
        assertEquals(Plans.OTHERS_NEED_PRO, joinAs(db, "r4", code).error.value)
        assertNull(db.get("groups/$code/pro"))

        r3.leave()
        assertNull(db.get("groups/$code/slots/3"))
        givePro(db, "r3")
        joinAs(db, "r3", code)
        val r4 = joinAs(db, "r4", code)
        assertEquals("\"r4\"", db.get("groups/$code/slots/4").toString())
        assertEquals("true", db.get("groups/$code/pro").toString())

        // Now it's a Pro group: a rider without Pro can't take even a freed seat.
        r2.leave()
        assertNull(db.get("groups/$code/slots/2"))
        assertEquals(Plans.NEED_PRO_GROUP_IS_BIG, joinAs(db, "r5", code).error.value)
        joinAs(db, "r6", code)
        assertEquals("\"r6\"", db.get("groups/$code/slots/2").toString())

        r4.leave()
        assertNull(db.get("groups/$code/slots/4"))
        assertNull(db.get("groups/$code/members/r4"))
    }

    @Test fun withPricingOffAnyoneJoinsFree() {
        val db = FakeBackend("lead")
        val leader = GroupClient(db, MemoryStore(), scope, pricingOn = false) { clock }
        leader.create("Goa trip")
        val code = leader.group.value!!.code
        (2..6).forEach { n ->
            val r = GroupClient(db.asUser("r$n"), MemoryStore(), scope, pricingOn = false) { clock }.also { it.join(code) }
            assertNull(r.error.value)
        }
        assertEquals("\"r6\"", db.get("groups/$code/slots/6").toString())
        assertNull(db.get("groups/$code/pro"))
        assertEquals(6, leader.group.value?.riders?.size)
    }

    @Test fun leaderRemovesARiderAndFreesTheSeat() {
        val db = FakeBackend("lead")
        val leader = GroupClient(db, MemoryStore(), scope, pricingOn = true) { clock }
        leader.create("Ride")
        val code = leader.group.value!!.code
        joinAs(db, "r2", code)
        leader.removeRider("r2")
        assertNull(db.get("groups/$code/slots/2"))
        assertNull(db.get("groups/$code/members/r2"))
    }
}
