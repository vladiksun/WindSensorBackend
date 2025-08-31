package com.vb.wingfoil;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.client.ProxyHttpClient;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import io.micronaut.http.uri.UriBuilder;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.management.endpoint.EndpointSensitivityProcessor;
import io.micronaut.web.router.MethodBasedRouteMatch;
import io.micronaut.web.router.RouteMatch;
import io.micronaut.web.router.RouteMatchUtils;
import io.reactivex.rxjava3.core.Single;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;

@Filter("/**")
public class ProxyFilter implements HttpServerFilter {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final String WINDY_URL = "";

    private final ProxyHttpClient proxyHttpClient;

    private final Map<ExecutableMethod, Boolean> endpointMethods;

    public ProxyFilter(ProxyHttpClient proxyHttpClient, EndpointSensitivityProcessor endpointSensitivityProcessor) {
        this.proxyHttpClient = proxyHttpClient;
        this.endpointMethods = endpointSensitivityProcessor.getEndpointMethods();
    }

    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> originalRequest, ServerFilterChain chain) {
        if (shouldNotProxyRequest(originalRequest)) {
            // Continue with normal processing if conditions aren't met
            logger.debug("Passing through non-proxied request: {}", originalRequest.getPath());
            return chain.proceed(originalRequest);
        }

        return handleRequest(originalRequest)
                .toFlowable();
    }

    private boolean shouldNotProxyRequest(HttpRequest<?> originalRequest) {
        Optional<RouteMatch> routeMatch = RouteMatchUtils.findRouteMatch(originalRequest);
        if (routeMatch.isPresent() && routeMatch.get() instanceof MethodBasedRouteMatch) {
            ExecutableMethod methodFromRoute = ((MethodBasedRouteMatch) routeMatch.get()).getExecutableMethod();
            var isMatchNonApplicationRoute = endpointMethods.containsKey(methodFromRoute);

            return isMatchNonApplicationRoute;
        }

        return false;
    }

    public Single<MutableHttpResponse<?>> handleRequest(HttpRequest<?> originalRequest) {
        logger.debug("Proxying request to {}: {}", WINDY_URL, originalRequest.getPath());

        var uriBuilder = UriBuilder.of(WINDY_URL)
                .path(originalRequest.getPath());

        // Copy parameters, no need to copy headers and attributes
        originalRequest
                .getParameters()
                .forEach((name, values) -> values.forEach(
                        value -> uriBuilder.queryParam(name, value)));

        var uri = uriBuilder.build();

        // Build the proxy request
        var proxyRequest = originalRequest.mutate().uri(uri);

        // Proxy the request and handle the response
        return Single.fromPublisher(proxyHttpClient.proxy(proxyRequest))
                .onErrorReturn(this::createErrorResponse)
                .map(this::enrichWithHeaders);

    }

    private MutableHttpResponse<?> enrichWithHeaders(MutableHttpResponse<?> mutableHttpResponse) {
        return mutableHttpResponse;
    }

    private MutableHttpResponse<?> createErrorResponse(Throwable error) {
        return HttpResponse.status(HttpStatus.BAD_GATEWAY)
                .body("Proxy error: " + error.getMessage());
    }
}
