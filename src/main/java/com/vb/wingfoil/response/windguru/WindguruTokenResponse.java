package com.vb.wingfoil.response.windguru;

import io.micronaut.serde.annotation.Serdeable;

/**
 * Envelope of the Windguru {@code q=token_refresh} answer, narrowed to the one field this backend acts on.
 * <p>
 * The live payload also carries the reserved top-level {@code return}, a {@code message}, and a {@code wgk}
 * rotation block inside {@code data}; none of them are mapped — unknown values in that payload are ignored, so
 * Windguru can add diagnostics without changing what this provider reads.
 *
 * @param data token body, {@code null} when the answer carried none
 */
@Serdeable
public record WindguruTokenResponse(WindguruTokenData data) {}
