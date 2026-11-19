package com.vb.wingfoil;

import com.vb.wingfoil.tiles.TileProxyException;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.hateoas.JsonError;
import io.micronaut.http.hateoas.Link;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import jakarta.inject.Singleton;

@Singleton
@Requires(classes = ExceptionHandler.class)
public class GlobalExceptionHandler implements ExceptionHandler<Throwable, HttpResponse<JsonError>> {

    @Override
    public HttpResponse<JsonError> handle(HttpRequest request, Throwable exception) {
        // The message rendered here is the one on the exception itself: an application failure carries a short
        // client-safe message, while everything it was mapped from stays in its cause chain and in the logs.
        var error = new JsonError(exception.getMessage()).link(Link.SELF, Link.of(request.getUri()));
        var status =
                switch (exception) {
                    case AppException appException -> appException.statusCode();
                    case TileProxyException tileProxyException ->
                        tileProxyException.status().getCode();
                    default -> HttpStatus.INTERNAL_SERVER_ERROR.getCode();
                };

        // Micronaut renders any numeric code exactly, which is what an upstream status needs: a provider that
        // answered 403 has to be answered 403, even for a code with no named constant. A null reason keeps the
        // standard phrase for that code.
        return HttpResponse.<JsonError>status(status, null).body(error);
    }
}
