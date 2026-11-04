package com.vb.wingfoil.response.windguru;

/**
 * A single normalized wind reading unzipped from one index of {@link WindguruStationData}.
 * Built manually from the parallel columns (not deserialized), so it intentionally carries no
 * Micronaut {@code @Serdeable} annotation — same shape as {@code OneChipMeasurement}.
 *
 * @param min       minimum wind speed in knots as published by Windguru
 * @param avg       average wind speed in knots
 * @param gust      maximum (gust) wind speed in knots
 * @param dir       wind direction in degrees
 * @param timestamp reading time as the epoch seconds published in {@code unixtime}
 */
public record WindguruMeasurement(float min, float avg, float gust, float dir, long timestamp) {}
