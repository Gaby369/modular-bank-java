package com.modularbank.transfers.shared.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class TraceContextStore {

    private static final TextMapSetter<Map<String, String>> SETTER =
        (carrier, key, value) -> carrier.put(key, value);

    private static final TextMapGetter<Map<String, String>> GETTER =
        new TextMapGetter<>() {

            @Override
            public Iterable<String> keys(
                Map<String, String> carrier
            ) {
                return carrier.keySet();
            }

            @Override
            public String get(
                Map<String, String> carrier,
                String key
            ) {
                if (carrier == null) {
                    return null;
                }

                return carrier.get(key);
            }
        };

    private final TextMapPropagator propagator;

    public TraceContextStore(OpenTelemetry openTelemetry) {
        this.propagator =
            openTelemetry
                .getPropagators()
                .getTextMapPropagator();
    }

    public StoredTraceContext capture() {
        Map<String, String> carrier = new HashMap<>();

        propagator.inject(
            Context.current(),
            carrier,
            SETTER
        );

        return new StoredTraceContext(
            carrier.get("traceparent"),
            carrier.get("tracestate")
        );
    }

    public Scope restore(
        String traceparent,
        String tracestate
    ) {
        Map<String, String> carrier = new HashMap<>();

        if (
            traceparent != null
                && !traceparent.isBlank()
        ) {
            carrier.put("traceparent", traceparent);
        }

        if (
            tracestate != null
                && !tracestate.isBlank()
        ) {
            carrier.put("tracestate", tracestate);
        }

        Context extracted = propagator.extract(
            Context.root(),
            carrier,
            GETTER
        );

        return extracted.makeCurrent();
    }

    public record StoredTraceContext(
        String traceparent,
        String tracestate
    ) {
    }
}
