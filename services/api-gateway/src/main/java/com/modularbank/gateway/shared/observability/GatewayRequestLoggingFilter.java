package com.modularbank.gateway.shared.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.handler.TracingObservationHandler;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Component
public class GatewayRequestLoggingFilter implements GlobalFilter, Ordered {

    public static final String HEADER_NAME = "X-Correlation-Id";

    private static final Logger LOGGER =
        LoggerFactory.getLogger(GatewayRequestLoggingFilter.class);

    private static final String CORRELATION_MDC_KEY = "correlationId";

    private static final Pattern SAFE_VALUE =
        Pattern.compile("[A-Za-z0-9._-]{1,100}");

    private static final Set<String> EXCLUDED_PATH_PREFIXES = Set.of(
        "/actuator/health",
        "/actuator/prometheus"
    );

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(
        ServerWebExchange exchange,
        GatewayFilterChain chain
    ) {
        ServerHttpRequest request = exchange.getRequest();

        String path = request.getPath().value();
        String method = request.getMethod().name();

        String correlationId = resolveCorrelationId(
            request.getHeaders().getFirst(HEADER_NAME)
        );

        ServerHttpRequest mutatedRequest = request.mutate()
            .header(HEADER_NAME, correlationId)
            .build();

        exchange.getResponse()
            .getHeaders()
            .add(HEADER_NAME, correlationId);

        ServerWebExchange mutatedExchange = exchange.mutate()
            .request(mutatedRequest)
            .build();

        long startedAt = System.nanoTime();

        // filter() runs while the GlobalFilter chain is being assembled,
        // before Reactor subscribes to it -- at that point there is no
        // active span in any thread-local, and reading Span.current()
        // (whether here or later inside doFinally) always returns an
        // invalid span. The HTTP server span only exists in the Reactor
        // Context once the chain is actually subscribed, so it has to be
        // read from there via deferContextual, not from a thread-local.
        return Mono.deferContextual(contextView -> {
            String[] traceAndSpanId = readTraceAndSpanId(contextView);
            String traceId = traceAndSpanId[0];
            String spanId = traceAndSpanId[1];

            return chain.filter(mutatedExchange)
                .doFinally(signalType -> {
                    if (isExcluded(path)) {
                        return;
                    }

                    logRequest(
                        mutatedExchange,
                        method,
                        path,
                        correlationId,
                        traceId,
                        spanId,
                        startedAt
                    );
                });
        });
    }

    private String[] readTraceAndSpanId(ContextView contextView) {
        Observation observation = contextView.getOrDefault(
            ObservationThreadLocalAccessor.KEY,
            null
        );

        if (observation == null) {
            return new String[] { "", "" };
        }

        TracingObservationHandler.TracingContext tracingContext =
            observation.getContext()
                .get(TracingObservationHandler.TracingContext.class);

        if (tracingContext == null) {
            return new String[] { "", "" };
        }

        Span span = tracingContext.getSpan();

        if (span == null) {
            return new String[] { "", "" };
        }

        return new String[] {
            span.context().traceId(),
            span.context().spanId()
        };
    }

    private void logRequest(
        ServerWebExchange exchange,
        String method,
        String path,
        String correlationId,
        String traceId,
        String spanId,
        long startedAt
    ) {
        long durationMs = TimeUnit.NANOSECONDS.toMillis(
            System.nanoTime() - startedAt
        );

        HttpStatusCode statusCode =
            exchange.getResponse().getStatusCode();

        int status = statusCode != null
            ? statusCode.value()
            : 0;

        MDC.put(CORRELATION_MDC_KEY, correlationId);

        try {
            LOGGER.info(
                "Gateway request completed",
                StructuredArguments.kv("event", "gateway_request_completed"),
                StructuredArguments.kv("method", method),
                StructuredArguments.kv("path", path),
                StructuredArguments.kv("status", status),
                StructuredArguments.kv("durationMs", durationMs),
                StructuredArguments.kv("traceId", traceId),
                StructuredArguments.kv("spanId", spanId)
            );

        } finally {
            MDC.remove(CORRELATION_MDC_KEY);
        }
    }

    private boolean isExcluded(String path) {
        return EXCLUDED_PATH_PREFIXES.stream()
            .anyMatch(path::startsWith);
    }

    private String resolveCorrelationId(String requestedValue) {
        if (
            requestedValue != null
                && SAFE_VALUE.matcher(requestedValue).matches()
        ) {
            return requestedValue;
        }

        return UUID.randomUUID().toString();
    }
}
