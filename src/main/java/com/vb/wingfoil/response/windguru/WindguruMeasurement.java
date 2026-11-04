package com.vb.wingfoil.response.windguru;

/**
 * A single normalized wind reading unzipped from one index of {@link WindguruStationData}.
 * Built manually from the parallel columns (not deserialized), so it intentionally carries no
 * Micronaut {@code @Serdeable} annotation — same shape as {@code OneChipMeasurement}.
 * <p>
 * Speeds are metres per second: Windguru publishes knots, and the provider converts each speed once
 * while building this record (see {@code WindguruDataProvider#knotsToMetresPerSecond(float)}).
 *
 * @param min       minimum wind speed in metres per second, converted from Windguru's knots
 * @param avg       average wind speed in metres per second, converted from Windguru's knots
 * @param gust      maximum (gust) wind speed in metres per second, converted from Windguru's knots
 * @param dir       wind direction in degrees, exactly as published
 * @param timestamp reading time as the epoch seconds published in {@code unixtime}
 */
public record WindguruMeasurement(float min, float avg, float gust, float dir, long timestamp) {}
