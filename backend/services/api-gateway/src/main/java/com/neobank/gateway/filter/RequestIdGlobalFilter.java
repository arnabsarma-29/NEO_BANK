package com.neobank.gateway.filter;

import com.github.f4b6a3.uuid.UuidCreator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class RequestIdGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log =
        LoggerFactory.getLogger (RequestIdGlobalFilter.class);

    private static final String REQUEST_ID_HEADER = "X-Request-ID";

    @Override
    public int getOrder () {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono <Void> filter (
        ServerWebExchange exchange,
        GatewayFilterChain chain) {

        String requestId = UuidCreator.getTimeOrderedEpoch ().toString ();
        long startTime = System.nanoTime ();

        ServerHttpRequest request = exchange.getRequest ()
            .mutate ()
            .headers (headers ->
                headers.set (REQUEST_ID_HEADER, requestId))
            .build ();

        ServerWebExchange updatedExchange = exchange.mutate ()
            .request (request)
            .build ();

        // Return the same ID to the caller.
        exchange.getResponse ().getHeaders ()
            .set (REQUEST_ID_HEADER, requestId);

        return chain.filter (updatedExchange)
            .doFinally (signal -> {
                var response = exchange.getResponse ();
                var status = response.getStatusCode ();

                long durationMs =
                    (System.nanoTime () - startTime) / 1_000_000;

                log.info (
                    "requestId={} method={} path={} status={} durationMs={} signal={}",
                    requestId,
                    exchange.getRequest ().getMethod (),
                    exchange.getRequest ().getURI ().getPath (),
                    status != null ? status.value () : "UNKNOWN",
                    durationMs,
                    signal
                );
            });
    }
}
