package org.beachsync.proto

import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** One satellite's orbit, as the Satellites card describes it (hand-off 42). [heightKm] is above the equator. */
class Orbit(val name: String, val heightKm: Int, val words: String, val tiltDeg: Int) {
    val radiusKm get() = heightKm + SatFacts.EARTH_EQ_KM
    /** Speed on a circular orbit of this size, km/s. */
    val speedKmS get() = sqrt(SatFacts.GM / radiusKm)
}

/** Who runs a system: its countries (ISO code and name), operator, and roughly how many satellites it has. */
class Fleet(val countries: List<Pair<String, String>>, val operator: String, val size: String) {
    constructor(code: String, country: String, operator: String, size: String) : this(listOf(code to country), operator, size)
}

/** The flag emoji for an ISO 3166 two-letter code, from its regional indicator letters. */
fun flagOf(code: String): String = code.uppercase().map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")

/**
 * What the app knows about each navigation satellite without a network: its system's orbit and operator, and its distance worked out
 * from where it is in the sky. Craft, launch year and weight need a per-satellite table, which isn't in the app yet. Plain Kotlin.
 */
object SatFacts {
    const val EARTH_KM = 6371.0
    const val EARTH_EQ_KM = 6378.0
    const val GM = 398600.4418          // km³/s²
    const val C_KM_S = 299792.458

    private val MEO_GPS = Orbit("Medium Earth orbit", 20180, "Once round the Earth every 11 h 58 m, tilted 55° to the equator", 55)
    private val MEO_GAL = Orbit("Medium Earth orbit", 23222, "Once round the Earth every 14 h 05 m, tilted 56° to the equator", 56)
    private val MEO_GLO = Orbit("Medium Earth orbit", 19100, "Once round the Earth every 11 h 16 m, tilted 65° to the equator", 65)
    private val MEO_BDS = Orbit("Medium Earth orbit", 21528, "Once round the Earth every 12 h 53 m, tilted 55° to the equator", 55)
    private val GEO = Orbit("Geostationary orbit", 35786, "Turns with the Earth, so it stays over one spot on the equator", 0)
    private fun igso(tilt: Int) = Orbit("Inclined geosynchronous orbit", 35786, "Once round every 23 h 56 m, tilted $tilt°, tracing a figure of eight over one region", tilt)

    // BeiDou PRNs in geostationary and inclined geosynchronous orbits; the rest are medium Earth orbit.
    private val BDS_GEO = setOf(1, 2, 3, 4, 5, 59, 60, 61, 62, 63)
    private val BDS_IGSO = setOf(6, 7, 8, 9, 10, 13, 16, 31, 38, 39, 40, 56)

    /** [system] is a GnssStatus.CONSTELLATION_* value: 1 GPS, 2 SBAS, 3 GLONASS, 4 QZSS, 5 BeiDou, 6 Galileo, 7 NavIC. */
    fun orbit(system: Int, svid: Int): Orbit = when (system) {
        1 -> MEO_GPS
        3 -> MEO_GLO
        6 -> MEO_GAL
        5 -> when (svid) { in BDS_GEO -> GEO; in BDS_IGSO -> igso(55); else -> MEO_BDS }
        4 -> if (svid == 199) GEO else igso(41)   // QZS-3 is geostationary
        7 -> if (svid in setOf(3, 6, 7)) GEO else igso(29)
        else -> GEO                                   // SBAS
    }

    fun fleet(system: Int): Fleet? = when (system) {
        1 -> Fleet("US", "United States", "Run by the US Space Force", "about 31")
        3 -> Fleet("RU", "Russia", "Run by Roscosmos", "about 24")
        6 -> Fleet(listOf("GB" to "United Kingdom", "DE" to "Germany"), "Run by the EU Space Programme", "about 27")
        5 -> Fleet("CN", "China", "Run by the China Satellite Navigation Office", "about 45")
        4 -> Fleet("JP", "Japan", "Run by the Cabinet Office of Japan", "about 5")
        7 -> Fleet("IN", "India", "Run by ISRO", "about 7")
        else -> null
    }

    /**
     * Distance in km from a phone on the ground to a satellite seen at elevation [elDeg] on an orbit of radius [rsKm]: the root of
     * |you + d·u| = rs along the line of sight. Exact for a circular orbit; GNSS orbits are nearly circular.
     */
    fun rangeKm(elDeg: Double, rsKm: Double): Double {
        val s = sin(Math.toRadians(elDeg))
        val r = EARTH_KM
        return -r * s + sqrt(r * r * s * s + rsKm * rsKm - r * r)
    }
}

/**
 * A satellite's distance over time, for its rate on the card: a straight-line fit over the last [windowS] seconds. Chips often report
 * elevation in whole degrees, so the distance moves in steps; the fit needs a minute and two different distances before it answers.
 */
class RangeTrack(private val windowS: Double = 300.0) {
    private val t = ArrayDeque<Double>()
    private val d = ArrayDeque<Double>()

    fun add(timeS: Double, km: Double) {
        t.addLast(timeS); d.addLast(km)
        while (t.isNotEmpty() && timeS - t.first() > windowS) { t.removeFirst(); d.removeFirst() }
    }

    /** km/s, positive when moving away; null until there's enough to say. */
    fun rateKmS(): Double? {
        if (t.size < 2 || t.last() - t.first() < 60.0 || d.distinct().size < 2) return null
        val mt = t.average(); val md = d.average()
        var num = 0.0; var den = 0.0
        for (i in t.indices) { num += (t[i] - mt) * (d[i] - md); den += (t[i] - mt) * (t[i] - mt) }
        return num / max(den, 1e-9)
    }
}
