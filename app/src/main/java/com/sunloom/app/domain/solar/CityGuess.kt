package com.sunloom.app.domain.solar

import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/**
 * A first guess at the city for sunrise and sunset, from the device's time zone (Article III). Sunloom never asks for the
 * location, and a zone says little about latitude, so this is only a starting point the person can change in one click.
 */
object CityGuess {
    // Several bundled cities share a zone (India has three); the first listed is not always the one people expect.
    private val PREFERRED_IN_ZONE = mapOf("Asia/Kolkata" to "New Delhi")

    fun forZoneId(zoneId: String, at: Instant): CityCoordinates? =
        runCatching { ZoneId.of(zoneId) }.getOrNull()?.let { forZone(it, at) }

    /**
     * A bundled city in the zone itself, else the one on the nearest standard clock. The standard (not summer) offset is
     * used so the guess does not change with the season.
     */
    fun forZone(zone: ZoneId, at: Instant): CityCoordinates? {
        val cities = SolarCalculator.BUNDLED_CITIES
        val inZone = cities.filter { it.zoneId == zone.id }
        if (inZone.isNotEmpty()) return inZone.firstOrNull { it.cityName == PREFERRED_IN_ZONE[zone.id] } ?: inZone.first()
        val standardOffsetHours = zone.rules.getStandardOffset(at).totalSeconds / 3600.0
        return cities.minByOrNull { abs(it.utcOffsetHours - standardOffsetHours) }
    }
}
