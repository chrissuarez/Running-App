package com.example.runningapp.map

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * On-device sunrise/sunset (#40) - no setting, no network. Implements the public-domain
 * "Almanac for Computers, 1990" sunrise equation, as commonly republished at
 * https://edwilliams.org/sunrise_sunset_algorithm.txt.
 *
 * Accurate to within a few minutes - fine for picking a map day/night preset, not intended for
 * anything precision-sensitive.
 */
object SunriseSunsetCalculator {

    private const val ZENITH_DEGREES = 90.833

    sealed class SunriseSunset {
        data class Times(val sunrise: Instant, val sunset: Instant) : SunriseSunset()
        object PolarDay : SunriseSunset()
        object PolarNight : SunriseSunset()
    }

    fun isDaytime(
        latitude: Double,
        longitude: Double,
        epochMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Boolean {
        val localDate = Instant.ofEpochMilli(epochMillis).atZone(zoneId).toLocalDate()
        return when (val result = sunriseSunsetUtc(latitude, longitude, localDate)) {
            is SunriseSunset.Times -> {
                val now = Instant.ofEpochMilli(epochMillis)
                !now.isBefore(result.sunrise) && now.isBefore(result.sunset)
            }
            SunriseSunset.PolarDay -> true
            SunriseSunset.PolarNight -> false
        }
    }

    /**
     * Sunrise/sunset for [date], expressed as UTC instants. [date] is treated as if it were also
     * the UTC calendar date - close enough for map styling; the only error window is a
     * timezone-offset-sized sliver around local midnight, when the sun is down anyway outside
     * polar regions.
     */
    fun sunriseSunsetUtc(latitude: Double, longitude: Double, date: LocalDate): SunriseSunset {
        val lngHour = longitude / 15.0
        val sunriseCosHourAngle = cosHourAngle(latitude, lngHour, date.dayOfYear, isSunrise = true)

        // cosHourAngle > 1 means the sun's declination never reaches this latitude's horizon
        // (polar night); < -1 means it never leaves it (polar day). Classified from the sunrise
        // side alone so day/night can't disagree with itself at the poles.
        if (sunriseCosHourAngle > 1.0) return SunriseSunset.PolarNight
        if (sunriseCosHourAngle < -1.0) return SunriseSunset.PolarDay

        val sunriseHour = eventUtcHour(sunriseCosHourAngle, lngHour, date.dayOfYear, isSunrise = true)
        val sunsetCosHourAngle = cosHourAngle(latitude, lngHour, date.dayOfYear, isSunrise = false)
        val sunsetHour = if (sunsetCosHourAngle > 1.0 || sunsetCosHourAngle < -1.0) {
            null
        } else {
            eventUtcHour(sunsetCosHourAngle, lngHour, date.dayOfYear, isSunrise = false)
        }

        // Sunrise and sunset are computed independently (each uses its own approximate time of
        // day), so right at a polar boundary they can rarely disagree - treat that as polar day.
        if (sunsetHour == null) return SunriseSunset.PolarDay

        val utcMidnight = date.atStartOfDay(ZoneOffset.UTC)
        val sunrise = utcMidnight.plusSeconds((sunriseHour * 3600.0).toLong()).toInstant()
        val sunset = utcMidnight.plusSeconds((sunsetHour * 3600.0).toLong()).toInstant()
        return SunriseSunset.Times(sunrise, sunset)
    }

    private fun cosHourAngle(latitude: Double, lngHour: Double, dayOfYear: Int, isSunrise: Boolean): Double {
        val t = approximateTime(lngHour, dayOfYear, isSunrise)
        val trueLongitude = sunTrueLongitudeDegrees(t)
        val sinDeclination = 0.39782 * sinDeg(trueLongitude)
        val cosDeclination = cos(asin(sinDeclination))
        return (cosDeg(ZENITH_DEGREES) - (sinDeclination * sinDeg(latitude))) / (cosDeclination * cosDeg(latitude))
    }

    private fun eventUtcHour(
        cosHourAngle: Double,
        lngHour: Double,
        dayOfYear: Int,
        isSunrise: Boolean
    ): Double {
        val t = approximateTime(lngHour, dayOfYear, isSunrise)
        val trueLongitude = sunTrueLongitudeDegrees(t)

        var rightAscension = atanDeg(0.91764 * tanDeg(trueLongitude))
        rightAscension = normalizeDegrees(rightAscension)
        val longitudeQuadrant = floor(trueLongitude / 90.0) * 90.0
        val rightAscensionQuadrant = floor(rightAscension / 90.0) * 90.0
        rightAscension = (rightAscension + (longitudeQuadrant - rightAscensionQuadrant)) / 15.0

        val hourAngleDegrees = if (isSunrise) 360.0 - acosDeg(cosHourAngle) else acosDeg(cosHourAngle)
        val hourAngleHours = hourAngleDegrees / 15.0

        val localMeanTime = hourAngleHours + rightAscension - (0.06571 * t) - 6.622
        // localMeanTime drifts by roughly a full day's worth of hours as dayOfYear grows (the
        // -0.06571*t term), so it must be folded back into a single day's clock time first.
        // Deliberately NOT wrapped again after subtracting lngHour: a UTC offset outside
        // [0, 24) at that point is exactly how this event lands on the UTC day before/after
        // `date` (e.g. sunset west of Greenwich in summer, or sunrise east of it). Re-wrapping
        // here would collapse that back onto `date` and can invert sunrise/sunset order -
        // utcMidnight.plusSeconds in sunriseSunsetUtc carries the rollover correctly as-is.
        return normalizeHours(localMeanTime) - lngHour
    }

    private fun approximateTime(lngHour: Double, dayOfYear: Int, isSunrise: Boolean): Double {
        val baseHour = if (isSunrise) 6.0 else 18.0
        return dayOfYear + ((baseHour - lngHour) / 24.0)
    }

    private fun sunTrueLongitudeDegrees(t: Double): Double {
        val meanAnomaly = (0.9856 * t) - 3.289
        val trueLongitude = meanAnomaly +
            (1.916 * sinDeg(meanAnomaly)) +
            (0.020 * sinDeg(2 * meanAnomaly)) +
            282.634
        return normalizeDegrees(trueLongitude)
    }

    private fun normalizeDegrees(value: Double): Double {
        var result = value % 360.0
        if (result < 0.0) result += 360.0
        return result
    }

    private fun normalizeHours(value: Double): Double {
        var result = value % 24.0
        if (result < 0.0) result += 24.0
        return result
    }

    private fun sinDeg(degrees: Double) = sin(Math.toRadians(degrees))
    private fun cosDeg(degrees: Double) = cos(Math.toRadians(degrees))
    private fun tanDeg(degrees: Double) = tan(Math.toRadians(degrees))
    private fun atanDeg(value: Double) = Math.toDegrees(atan(value))
    private fun acosDeg(value: Double) = Math.toDegrees(acos(value))
}
