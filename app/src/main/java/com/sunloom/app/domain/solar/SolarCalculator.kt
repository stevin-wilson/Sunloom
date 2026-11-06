package com.sunloom.app.domain.solar

import com.sunloom.app.domain.model.BrightnessClass
import com.sunloom.app.domain.model.ScheduleMode
import com.sunloom.app.domain.model.UserPreferences
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

data class CityCoordinates(
    val cityName: String,
    val countryName: String,
    val region: String,
    val latitude: Double,
    val longitude: Double,
    val utcOffsetHours: Double,
    val timeZoneCode: String,
    val zoneId: String
) {
    val name: String
        get() = "$cityName, $countryName"

    val utcOffsetLabel: String
        get() {
            val totalMinutes = (utcOffsetHours * 60.0).roundToInt()
            val sign = if (totalMinutes >= 0) "+" else "-"
            val absMins = abs(totalMinutes)
            val h = absMins / 60
            val m = absMins % 60
            return "UTC$sign%02d:%02d".format(h, m)
        }

    val dropdownLabel: String
        get() = "$utcOffsetLabel ($timeZoneCode) • $cityName, $countryName"
}

data class DaylightWindow(
    val sunriseTime: LocalTime,
    val sunsetTime: LocalTime,
    val isPolarDay: Boolean = false,
    val isPolarNight: Boolean = false
)

data class SolarSnapshot(
    val nowZoned: ZonedDateTime,
    val daylightWindow: DaylightWindow,
    val targetBrightness: BrightnessClass
)

/**
 * Article III §§1–4 (Constitution v0.4): Offline Solar & Schedule Calculator.
 * Computes local sunrise and sunset times using NOAA astronomical formulas,
 * IANA timezone / DST rules, polar day/night handling, midnight-wrapping fixed clocks,
 * and quiet-hour deferral without ever requesting GPS or location permissions.
 */
class SolarCalculator {

    fun resolveZoneId(
        preferences: UserPreferences,
        fallbackZone: ZoneId = ZoneId.systemDefault()
    ): ZoneId {
        val matchedCity = BUNDLED_CITIES.firstOrNull {
            it.name.equals(preferences.selectedCityName, ignoreCase = true) ||
                preferences.selectedCityName.startsWith(it.cityName, ignoreCase = true)
        }
        return if (matchedCity != null) {
            runCatching { ZoneId.of(matchedCity.zoneId) }.getOrDefault(fallbackZone)
        } else {
            fallbackZone
        }
    }

    /**
     * Computes the DST-accurate UTC offset in hours for [date] in [zoneId].
     */
    fun resolveUtcOffsetHours(date: LocalDate, zoneId: ZoneId): Double {
        val noonInstant = date.atTime(12, 0).atZone(zoneId).toInstant()
        val offset = zoneId.rules.getOffset(noonInstant)
        return offset.totalSeconds / 3600.0
    }

    fun calculateDaylightWindow(
        date: LocalDate,
        latitude: Double,
        longitude: Double,
        utcOffsetHours: Double
    ): DaylightWindow {
        val dayOfYear = date.dayOfYear
        val safeLat = latitude.coerceIn(-89.9, 89.9)
        val latRad = Math.toRadians(safeLat)

        // Fractional year gamma in radians
        val gamma = (2.0 * Math.PI / 365.0) * (dayOfYear - 1)

        // Equation of time (in minutes)
        val eqTime = 229.18 * (
            0.000075 +
                0.001868 * cos(gamma) -
                0.032077 * sin(gamma) -
                0.014615 * cos(2 * gamma) -
                0.040849 * sin(2 * gamma)
            )

        // Solar declination angle (in radians)
        val decl = 0.006918 -
            0.399912 * cos(gamma) +
            0.070257 * sin(gamma) -
            0.006758 * cos(2 * gamma) +
            0.000907 * sin(2 * gamma) -
            0.002697 * cos(3 * gamma) +
            0.00148 * sin(3 * gamma)

        // Zenith angle for official sunrise/sunset (90.833 degrees)
        val zenithRad = Math.toRadians(90.833)
        val rawCosHourAngle = (cos(zenithRad) / (cos(latRad) * cos(decl))) -
            tan(latRad) * tan(decl)

        // Explicit polar day / polar night handling (no silent +-65 deg clamping)
        if (rawCosHourAngle < -1.0) {
            return DaylightWindow(
                sunriseTime = LocalTime.of(0, 0),
                sunsetTime = LocalTime.of(23, 59),
                isPolarDay = true,
                isPolarNight = false
            )
        }
        if (rawCosHourAngle > 1.0) {
            return DaylightWindow(
                sunriseTime = LocalTime.of(12, 0),
                sunsetTime = LocalTime.of(12, 0),
                isPolarDay = false,
                isPolarNight = true
            )
        }

        val hourAngleDeg = Math.toDegrees(acos(rawCosHourAngle.coerceIn(-1.0, 1.0)))

        // UTC sunrise and sunset in minutes from midnight
        val sunriseUtcMinutes = 720.0 - 4.0 * (longitude + hourAngleDeg) - eqTime
        val sunsetUtcMinutes = 720.0 - 4.0 * (longitude - hourAngleDeg) - eqTime

        val sunriseLocalMinutes = ((sunriseUtcMinutes + utcOffsetHours * 60.0) % 1440.0 + 1440.0) % 1440.0
        val sunsetLocalMinutes = ((sunsetUtcMinutes + utcOffsetHours * 60.0) % 1440.0 + 1440.0) % 1440.0

        return DaylightWindow(
            sunriseTime = minutesToLocalTime(sunriseLocalMinutes),
            sunsetTime = minutesToLocalTime(sunsetLocalMinutes),
            isPolarDay = false,
            isPolarNight = false
        )
    }

    fun resolveSolarSnapshot(
        preferences: UserPreferences,
        isSystemInDarkTheme: Boolean,
        nowZonedOverride: ZonedDateTime? = null
    ): SolarSnapshot {
        val nowZoned = nowZonedOverride ?: run {
            val defaultZone = runCatching { ZoneId.systemDefault() }.getOrElse { ZoneId.of("UTC") }
            val zone = resolveZoneId(preferences, defaultZone)
            ZonedDateTime.now(zone)
        }
        val date = nowZoned.toLocalDate()
        val offsetHours = resolveUtcOffsetHours(date, nowZoned.zone)
        val daylightWindow = calculateDaylightWindow(
            date = date,
            latitude = preferences.latitude,
            longitude = preferences.longitude,
            utcOffsetHours = offsetHours
        )
        val targetBrightness = resolveTargetBrightness(
            preferences = preferences,
            date = date,
            currentLocalTime = nowZoned.toLocalTime(),
            utcOffsetHours = offsetHours,
            isSystemInDarkTheme = isSystemInDarkTheme,
            precomputedWindow = daylightWindow
        )
        return SolarSnapshot(
            nowZoned = nowZoned,
            daylightWindow = daylightWindow,
            targetBrightness = targetBrightness
        )
    }

    fun resolveTargetBrightness(
        preferences: UserPreferences,
        date: LocalDate,
        currentLocalTime: LocalTime,
        utcOffsetHours: Double,
        isSystemInDarkTheme: Boolean,
        precomputedWindow: DaylightWindow? = null
    ): BrightnessClass {
        return when (preferences.scheduleMode) {
            ScheduleMode.SUNRISE_SUNSET -> {
                val window = precomputedWindow ?: calculateDaylightWindow(
                    date = date,
                    latitude = preferences.latitude,
                    longitude = preferences.longitude,
                    utcOffsetHours = utcOffsetHours
                )
                when {
                    window.isPolarDay -> BrightnessClass.LIGHT
                    window.isPolarNight -> BrightnessClass.DARK
                    isTimeWithinWindow(currentLocalTime, window.sunriseTime, window.sunsetTime) ->
                        BrightnessClass.LIGHT
                    else -> BrightnessClass.DARK
                }
            }
            ScheduleMode.FIXED_CLOCK -> {
                val lightStart = LocalTime.of(preferences.fixedLightHour.coerceIn(0, 23), 0)
                val darkStart = LocalTime.of(preferences.fixedDarkHour.coerceIn(0, 23), 0)
                if (isTimeWithinWindow(currentLocalTime, lightStart, darkStart)) {
                    BrightnessClass.LIGHT
                } else {
                    BrightnessClass.DARK
                }
            }
            ScheduleMode.FOLLOW_OS_THEME -> {
                if (isSystemInDarkTheme) BrightnessClass.DARK else BrightnessClass.LIGHT
            }
            ScheduleMode.MANUAL_ONLY -> {
                if (isSystemInDarkTheme) BrightnessClass.DARK else BrightnessClass.LIGHT
            }
        }
    }

    /**
     * Checks whether [time] is inside `[startInclusive, endExclusive)`, supporting both
     * normal daytime spans (`07:00..19:00`) and overnight midnight-wrapping spans (`20:00..06:00`).
     */
    fun isTimeWithinWindow(time: LocalTime, startInclusive: LocalTime, endExclusive: LocalTime): Boolean {
        if (startInclusive == endExclusive) return true
        return if (startInclusive.isBefore(endExclusive)) {
            !time.isBefore(startInclusive) && time.isBefore(endExclusive)
        } else {
            !time.isBefore(startInclusive) || time.isBefore(endExclusive)
        }
    }

    /**
     * Computes the next scheduled transition timestamp strictly after [nowZoned] for
     * `SUNRISE_SUNSET` or `FIXED_CLOCK` modes.
     */
    fun computeNextTransitionZonedTime(
        preferences: UserPreferences,
        nowZoned: ZonedDateTime
    ): ZonedDateTime? {
        if (!preferences.autoChangeEnabled || preferences.isPaused) return null
        val zone = nowZoned.zone
        val candidateTimes = ArrayList<ZonedDateTime>()

        when (preferences.scheduleMode) {
            ScheduleMode.SUNRISE_SUNSET -> {
                for (dayDelta in 0L..2L) {
                    val date = nowZoned.toLocalDate().plusDays(dayDelta)
                    val offsetHours = resolveUtcOffsetHours(date, zone)
                    val window = calculateDaylightWindow(
                        date = date,
                        latitude = preferences.latitude,
                        longitude = preferences.longitude,
                        utcOffsetHours = offsetHours
                    )
                    if (!window.isPolarDay && !window.isPolarNight) {
                        candidateTimes.add(date.atTime(window.sunriseTime).atZone(zone))
                        candidateTimes.add(date.atTime(window.sunsetTime).atZone(zone))
                    } else {
                        candidateTimes.add(date.plusDays(1).atStartOfDay(zone))
                    }
                }
            }
            ScheduleMode.FIXED_CLOCK -> {
                val lightTime = LocalTime.of(preferences.fixedLightHour.coerceIn(0, 23), 0)
                val darkTime = LocalTime.of(preferences.fixedDarkHour.coerceIn(0, 23), 0)
                for (dayDelta in 0L..2L) {
                    val date = nowZoned.toLocalDate().plusDays(dayDelta)
                    candidateTimes.add(date.atTime(lightTime).atZone(zone))
                    candidateTimes.add(date.atTime(darkTime).atZone(zone))
                }
            }
            ScheduleMode.FOLLOW_OS_THEME,
            ScheduleMode.MANUAL_ONLY -> return null
        }

        return candidateTimes
            .filter { it.isAfter(nowZoned.plusSeconds(30)) }
            .minOrNull()
    }

    fun computeNextTransitionDelayMs(
        preferences: UserPreferences,
        nowZoned: ZonedDateTime
    ): Long? {
        val next = computeNextTransitionZonedTime(preferences, nowZoned) ?: return null
        return Duration.between(nowZoned, next).toMillis().coerceAtLeast(60_000L)
    }

    private fun minutesToLocalTime(totalMinutes: Double): LocalTime {
        val rounded = totalMinutes.roundToInt().coerceIn(0, 1439)
        val hours = rounded / 60
        val mins = rounded % 60
        return LocalTime.of(hours, mins)
    }

    companion object {
        val BUNDLED_CITIES: List<CityCoordinates> = listOf(
            CityCoordinates("Honolulu", "United States", "Americas", 21.3069, -157.8583, -10.0, "HST", "Pacific/Honolulu"),
            CityCoordinates("Anchorage", "United States", "Americas", 61.2181, -149.9003, -9.0, "AKST", "America/Anchorage"),
            CityCoordinates("Los Angeles", "United States", "Americas", 34.0522, -118.2437, -8.0, "PST", "America/Los_Angeles"),
            CityCoordinates("San Francisco", "United States", "Americas", 37.7749, -122.4194, -8.0, "PST", "America/Los_Angeles"),
            CityCoordinates("Seattle", "United States", "Americas", 47.6062, -122.3321, -8.0, "PST", "America/Los_Angeles"),
            CityCoordinates("Vancouver", "Canada", "Americas", 49.2827, -123.1207, -8.0, "PST", "America/Vancouver"),
            CityCoordinates("Denver", "United States", "Americas", 39.7392, -104.9903, -7.0, "MST", "America/Denver"),
            CityCoordinates("Phoenix", "United States", "Americas", 33.4484, -112.0740, -7.0, "MST", "America/Phoenix"),
            CityCoordinates("Chicago", "United States", "Americas", 41.8781, -87.6298, -6.0, "CST", "America/Chicago"),
            CityCoordinates("Mexico City", "Mexico", "Americas", 19.4326, -99.1332, -6.0, "CST", "America/Mexico_City"),
            CityCoordinates("Bogotá", "Colombia", "Americas", 4.7110, -74.0721, -5.0, "COT", "America/Bogota"),
            CityCoordinates("New York", "United States", "Americas", 40.7128, -74.0060, -5.0, "EST", "America/New_York"),
            CityCoordinates("Toronto", "Canada", "Americas", 43.6532, -79.3832, -5.0, "EST", "America/Toronto"),
            CityCoordinates("Santiago", "Chile", "Americas", -33.4489, -70.6693, -4.0, "CLT", "America/Santiago"),
            CityCoordinates("Buenos Aires", "Argentina", "Americas", -34.6037, -58.3816, -3.0, "ART", "America/Argentina/Buenos_Aires"),
            CityCoordinates("São Paulo", "Brazil", "Americas", -23.5505, -46.6333, -3.0, "BRT", "America/Sao_Paulo"),
            CityCoordinates("Dublin", "Ireland", "Europe", 53.3498, -6.2603, 0.0, "GMT", "Europe/Dublin"),
            CityCoordinates("Lisbon", "Portugal", "Europe", 38.7223, -9.1393, 0.0, "WET", "Europe/Lisbon"),
            CityCoordinates("London", "United Kingdom", "Europe", 51.5074, -0.1278, 0.0, "GMT", "Europe/London"),
            CityCoordinates("Amsterdam", "Netherlands", "Europe", 52.3676, 4.9041, 1.0, "CET", "Europe/Amsterdam"),
            CityCoordinates("Berlin", "Germany", "Europe", 52.5200, 13.4050, 1.0, "CET", "Europe/Berlin"),
            CityCoordinates("Madrid", "Spain", "Europe", 40.4168, -3.7038, 1.0, "CET", "Europe/Madrid"),
            CityCoordinates("Paris", "France", "Europe", 48.8566, 2.3522, 1.0, "CET", "Europe/Paris"),
            CityCoordinates("Rome", "Italy", "Europe", 41.9028, 13.4964, 1.0, "CET", "Europe/Rome"),
            CityCoordinates("Stockholm", "Sweden", "Europe", 59.3293, 18.0686, 1.0, "CET", "Europe/Stockholm"),
            CityCoordinates("Warsaw", "Poland", "Europe", 52.2297, 21.0122, 1.0, "CET", "Europe/Warsaw"),
            CityCoordinates("Zurich", "Switzerland", "Europe", 47.3769, 8.5417, 1.0, "CET", "Europe/Zurich"),
            CityCoordinates("Athens", "Greece", "Europe", 37.9838, 23.7275, 2.0, "EET", "Europe/Athens"),
            CityCoordinates("Cairo", "Egypt", "Africa & Middle East", 30.0444, 31.2357, 2.0, "EET", "Africa/Cairo"),
            CityCoordinates("Johannesburg", "South Africa", "Africa & Middle East", -26.2041, 28.0473, 2.0, "SAST", "Africa/Johannesburg"),
            CityCoordinates("Istanbul", "Turkey", "Europe", 41.0082, 28.9784, 3.0, "TRT", "Europe/Istanbul"),
            CityCoordinates("Nairobi", "Kenya", "Africa & Middle East", -1.2921, 36.8219, 3.0, "EAT", "Africa/Nairobi"),
            CityCoordinates("Dubai", "United Arab Emirates", "Africa & Middle East", 25.2048, 55.2708, 4.0, "GST", "Asia/Dubai"),
            CityCoordinates("Bengaluru", "India", "Asia & Pacific", 12.9716, 77.5946, 5.5, "IST", "Asia/Kolkata"),
            CityCoordinates("Mumbai", "India", "Asia & Pacific", 19.0760, 72.8777, 5.5, "IST", "Asia/Kolkata"),
            CityCoordinates("New Delhi", "India", "Asia & Pacific", 28.6139, 77.2090, 5.5, "IST", "Asia/Kolkata"),
            CityCoordinates("Bangkok", "Thailand", "Asia & Pacific", 13.7563, 100.5018, 7.0, "ICT", "Asia/Bangkok"),
            CityCoordinates("Jakarta", "Indonesia", "Asia & Pacific", -6.2088, 106.8456, 7.0, "WIB", "Asia/Jakarta"),
            CityCoordinates("Hong Kong", "China", "Asia & Pacific", 22.3193, 114.1694, 8.0, "HKT", "Asia/Hong_Kong"),
            CityCoordinates("Singapore", "Singapore", "Asia & Pacific", 1.3521, 103.8198, 8.0, "SGT", "Asia/Singapore"),
            CityCoordinates("Taipei", "Taiwan", "Asia & Pacific", 25.0330, 121.5654, 8.0, "CST", "Asia/Taipei"),
            CityCoordinates("Seoul", "South Korea", "Asia & Pacific", 37.5665, 126.9780, 9.0, "KST", "Asia/Seoul"),
            CityCoordinates("Tokyo", "Japan", "Asia & Pacific", 35.6762, 139.6503, 9.0, "JST", "Asia/Tokyo"),
            CityCoordinates("Melbourne", "Australia", "Asia & Pacific", -37.8136, 144.9631, 10.0, "AEST", "Australia/Melbourne"),
            CityCoordinates("Sydney", "Australia", "Asia & Pacific", -33.8688, 151.2093, 10.0, "AEST", "Australia/Sydney"),
            CityCoordinates("Auckland", "New Zealand", "Asia & Pacific", -36.8485, 174.7633, 12.0, "NZST", "Pacific/Auckland")
        ).sortedWith(compareBy<CityCoordinates> { it.utcOffsetHours }.thenBy { it.cityName })
    }
}
