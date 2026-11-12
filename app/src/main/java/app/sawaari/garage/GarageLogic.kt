package app.sawaari.garage

import kotlin.math.max

/** Something on the bike that needs doing every so many km and/or days. */
data class ServiceItem(
    val id: String,
    val name: String,
    val everyKm: Int?,
    val everyDays: Int?,
    /** Odometer (km) and time (epoch ms) when it was last done. */
    val lastKm: Double,
    val lastAt: Long,
)

/** What kind of vehicle it is: decides the usual service jobs and whether it has papers. */
enum class VehicleKind(val label: String, val hasPapers: Boolean) {
    MOTORCYCLE("Motorcycle", true), CAR("Car", true), BICYCLE("Bicycle", false);

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: MOTORCYCLE
    }
}

/** A vehicle in the garage (motorcycle, car or bicycle). Its odometer is [baseKm] plus every ride recorded on it. */
data class Bike(
    val id: Long,
    val name: String,
    val baseKm: Double,
    val items: List<ServiceItem>,
    /** Papers and their expiry dates (epoch ms), keyed by [Papers] name. */
    val papers: Map<String, Long> = emptyMap(),
    val kind: VehicleKind = VehicleKind.MOTORCYCLE,
)

enum class Level { OK, SOON, DUE }

/** How a service item or paper stands right now, with a short line to show the rider. */
data class Status(val level: Level, val text: String)

object Papers {
    const val INSURANCE = "Insurance"
    const val PUC = "Pollution (PUC)"
    const val RC = "Registration (RC)"
    val ALL = listOf(INSURANCE, PUC, RC)
}

object GarageLogic {
    const val DAY_MS = 86_400_000L
    /** Papers show as "soon" this many days before they expire. */
    const val PAPER_SOON_DAYS = 15

    /** The usual jobs for [kind], done "now" so the counters start from today. */
    fun defaultItems(odoKm: Double, now: Long, kind: VehicleKind = VehicleKind.MOTORCYCLE) = when (kind) {
        VehicleKind.MOTORCYCLE -> listOf(
            ServiceItem("oil", "Engine oil change", 3000, 180, odoKm, now),
            ServiceItem("chain", "Chain clean and lube", 500, 30, odoKm, now),
            ServiceItem("air", "Air filter", 6000, 365, odoKm, now),
            ServiceItem("brakes", "Brake pads check", 5000, null, odoKm, now),
            ServiceItem("tyres", "Tyre condition check", 3000, 90, odoKm, now),
        )
        VehicleKind.CAR -> listOf(
            ServiceItem("oil", "Engine oil and filter", 10000, 365, odoKm, now),
            ServiceItem("air", "Air filter", 15000, 730, odoKm, now),
            ServiceItem("rotation", "Tyre rotation and alignment", 10000, null, odoKm, now),
            ServiceItem("brakes", "Brake check", 10000, 365, odoKm, now),
            ServiceItem("coolant", "Coolant", 40000, 730, odoKm, now),
            ServiceItem("ac", "AC filter", 15000, 365, odoKm, now),
        )
        VehicleKind.BICYCLE -> listOf(
            ServiceItem("chain", "Chain clean and lube", 300, 30, odoKm, now),
            ServiceItem("tyres", "Tyre pressure check", null, 14, odoKm, now),
            ServiceItem("brakes", "Brake pads check", 1000, 90, odoKm, now),
            ServiceItem("tuneup", "Full tune-up", 3000, 365, odoKm, now),
        )
    }

    fun status(item: ServiceItem, odoKm: Double, now: Long): Status {
        val kmLeft = item.everyKm?.let { item.lastKm + it - odoKm }
        val daysLeft = item.everyDays?.let { ((item.lastAt + it * DAY_MS - now).toDouble() / DAY_MS) }
        val kmSoon = item.everyKm?.let { max(100.0, it * 0.1) }
        val due = (kmLeft != null && kmLeft <= 0) || (daysLeft != null && daysLeft <= 0)
        val soon = (kmLeft != null && kmSoon != null && kmLeft <= kmSoon) || (daysLeft != null && daysLeft <= 7)
        val text = when {
            due -> {
                val over = listOfNotNull(
                    kmLeft?.takeIf { it <= -1 }?.let { km(-it) },
                    daysLeft?.takeIf { it <= -1 }?.let { days(-it) },
                )
                if (over.isEmpty()) "Due now" else "Overdue by " + over.joinToString(" and ")
            }
            else -> "Due in " + listOfNotNull(kmLeft?.let { km(it) }, daysLeft?.let { days(it) }).joinToString(" or ")
        }
        return Status(if (due) Level.DUE else if (soon) Level.SOON else Level.OK, text)
    }

    fun paperStatus(expires: Long, now: Long): Status {
        val left = (expires - now).toDouble() / DAY_MS
        return when {
            left < 0 -> Status(Level.DUE, "Expired ${days(-left)} ago")
            left <= PAPER_SOON_DAYS -> Status(Level.SOON, "Expires in ${days(left)}")
            else -> Status(Level.OK, "Valid for ${days(left)}")
        }
    }

    /** Odometer for [bike], given the km ridden on it since it was added (or last set). */
    fun odometer(bike: Bike, riddenKm: Double) = bike.baseKm + riddenKm

    /** The base to store so the odometer reads [newOdoKm] now. */
    fun baseFor(newOdoKm: Double, riddenKm: Double) = newOdoKm - riddenKm

    private fun km(v: Double) = String.format(java.util.Locale.ENGLISH, "%,d km", v.toLong().coerceAtLeast(1))
    private fun days(v: Double): String {
        val d = v.toLong().coerceAtLeast(1)
        return when {
            d >= 60 -> "${d / 30} months"
            else -> "$d day${if (d == 1L) "" else "s"}"
        }
    }
}
