package com.vb.wingfoil.response.windguru;

import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.serde.config.naming.SnakeCaseStrategy;
import java.util.List;

/**
 * The columnar payload returned by Windguru's {@code iapi.php?q=station_data} endpoint.
 * <p>
 * Every published reading is stored as one element per parallel array, all arrays the same length and
 * ordered oldest-to-newest, so a reading is whatever index lines up across them. The wind columns are
 * boxed lists because Windguru publishes gaps as JSON {@code null} <em>inside</em> those arrays; a
 * primitive array would either fail to deserialize or silently turn a gap into a zero-speed reading.
 * Members the backend does not consume ({@code temperature}, {@code mslp}, {@code rh}, {@code gustiness},
 * {@code sunrise}, {@code sunset}, {@code startstamp}, {@code endstamp}, {@code tzoffset}) are ignored.
 */
@Serdeable(naming = SnakeCaseStrategy.class)
public record WindguruStationData(
        List<Long> unixtime,
        List<Float> windAvg,
        List<Float> windMax,
        List<Float> windMin,
        List<Float> windDirection) {}
