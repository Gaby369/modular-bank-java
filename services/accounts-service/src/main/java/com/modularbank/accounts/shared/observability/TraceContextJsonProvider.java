package com.modularbank.accounts.shared.observability;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.fasterxml.jackson.core.JsonGenerator;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import net.logstash.logback.composite.AbstractJsonProvider;

import java.io.IOException;

public class TraceContextJsonProvider
    extends AbstractJsonProvider<ILoggingEvent> {

    @Override
    public void writeTo(
        JsonGenerator generator,
        ILoggingEvent event
    ) throws IOException {

        SpanContext spanContext =
            Span.current().getSpanContext();

        if (spanContext.isValid()) {
            generator.writeStringField(
                "traceId",
                spanContext.getTraceId()
            );

            generator.writeStringField(
                "spanId",
                spanContext.getSpanId()
            );
        }
    }
}
