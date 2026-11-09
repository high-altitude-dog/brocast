package org.beachsync.proto

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Sunrise and sunset for a place and day (hand-off 52), from NOAA's general solar position equations: good to a minute or two,
 * worked out on the phone with no network. Plain Kotlin.
 */
object Sun {
    sealed class Day {
        /** Sunrise and sunset as UTC ms. */
        class Times(val riseMs: Long, val setMs: Long) : Day()
        object AlwaysUp : Day()
        object NeverUp : Day()
    }

    /** [dayStartUtcMs]: midnight UTC of the day; [lat], [lon] in degrees (east positive). */
    fun day(dayStartUtcMs: Long, lat: Double, lon: Double): Day {
        val dayOfYear = java.time.Instant.ofEpochMilli(dayStartUtcMs).atZone(java.time.ZoneOffset.UTC).dayOfYear
        val g = 2 * PI / 365.0 * (dayOfYear - 1)
        val eqTime = 229.18 * (0.000075 + 0.001868 * cos(g) - 0.032077 * sin(g) - 0.014615 * cos(2 * g) - 0.040849 * sin(2 * g))
        val decl = 0.006918 - 0.399912 * cos(g) + 0.070257 * sin(g) - 0.006758 * cos(2 * g) + 0.000907 * sin(2 * g) - 0.002697 * cos(3 * g) + 0.00148 * sin(3 * g)
        val phi = Math.toRadians(lat)
        val x = cos(Math.toRadians(90.833)) / (cos(phi) * cos(decl)) - tan(phi) * tan(decl)
        if (x > 1) return Day.NeverUp
        if (x < -1) return Day.AlwaysUp
        val ha = Math.toDegrees(acos(x))
        val rise = 720 - 4 * (lon + ha) - eqTime
        val set = 720 - 4 * (lon - ha) - eqTime
        return Day.Times(dayStartUtcMs + (rise * 60_000).toLong(), dayStartUtcMs + (set * 60_000).toLong())
    }

    /** A rough place for a time zone when the phone has no position: its main city if known, else the zone's offset as longitude. */
    fun zonePlace(zone: java.util.TimeZone): Pair<Double, Double> = ZONES[zone.id] ?: (40.0 to zone.rawOffset / 3_600_000.0 * 15.0)

    private val ZONES = mapOf(
        "Europe/London" to (51.5 to -0.13), "Europe/Paris" to (48.86 to 2.35), "Europe/Berlin" to (52.52 to 13.4), "Europe/Madrid" to (40.42 to -3.7),
        "Europe/Rome" to (41.9 to 12.5), "Europe/Amsterdam" to (52.37 to 4.9), "Europe/Lisbon" to (38.72 to -9.14), "Europe/Athens" to (37.98 to 23.73),
        "America/New_York" to (40.71 to -74.0), "America/Chicago" to (41.88 to -87.63), "America/Denver" to (39.74 to -104.99),
        "America/Los_Angeles" to (34.05 to -118.24), "America/Lima" to (-12.05 to -77.04), "America/Bogota" to (4.71 to -74.07),
        "America/Mexico_City" to (19.43 to -99.13), "America/Sao_Paulo" to (-23.55 to -46.63), "America/Argentina/Buenos_Aires" to (-34.6 to -58.38),
        "Asia/Tokyo" to (35.68 to 139.69), "Asia/Kolkata" to (28.61 to 77.21), "Asia/Bangkok" to (13.76 to 100.5), "Asia/Dubai" to (25.2 to 55.27),
        "Asia/Singapore" to (1.35 to 103.82), "Australia/Sydney" to (-33.87 to 151.21), "Africa/Johannesburg" to (-26.2 to 28.05),
        "Africa/Lagos" to (6.52 to 3.38), "Africa/Cairo" to (30.04 to 31.24),
    )
}
