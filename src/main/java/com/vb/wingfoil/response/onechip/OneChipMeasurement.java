package com.vb.wingfoil.response.onechip;

/**
 * A single normalized wind reading parsed from a 1chip.ru past-data table row.
 * Built manually from HTML (not deserialized), so it intentionally carries no
 * Jackson {@code @Serdeable} annotation.
 */
public record OneChipMeasurement(float min, float avg, float gust, float dir, long timestamp) {}
