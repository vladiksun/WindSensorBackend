package com.vb.wingfoil.response.windguru;

import io.micronaut.serde.annotation.Serdeable;

/**
 * The two values this backend reads out of Windguru's {@code q=token_refresh} payload.
 * <p>
 * Only {@code token} and its advertised lifetime are mapped: the surrounding diagnostics ({@code return},
 * {@code message}) and the rotation block {@code wgk} stay unread, so a change to them cannot break token
 * acquisition and no reserved-word mapping is needed for the top-level {@code return} key.
 *
 * @param token access token to send as {@code X-WG-Token}; blank or absent means no usable token
 * @param ttl   lifetime Windguru advertises for that token in seconds, {@code null} when it advertises none
 */
@Serdeable
public record WindguruTokenData(String token, Integer ttl) {}
