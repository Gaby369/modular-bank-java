package com.modularbank.gateway.shared.observability;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.handler.TracingObservationHandler;
import net.logstash.logback.encoder.LogstashEncoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GatewayRequestLoggingFilterTest {

    private static final Pattern UUID_PATTERN = Pattern.compile(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    );

    private static final String FAKE_TRACE_ID =
        "0123456789abcdef0123456789abcdef";

    private static final String FAKE_SPAN_ID =
        "0123456789abcdef";

    private final GatewayRequestLoggingFilter filter =
        new GatewayRequestLoggingFilter();

    private ch.qos.logback.classic.Logger logbackLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        logbackLogger = (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(GatewayRequestLoggingFilter.class);

        appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logbackLogger.detachAppender(appender);
    }

    @Test
    void shouldPropagateExistingCorrelationId() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/transfers")
                .header(GatewayRequestLoggingFilter.HEADER_NAME, "reto5-e2e-001")
                .build()
        );

        runFilter(exchange, HttpStatus.OK);

        assertThat(
            exchange.getResponse()
                .getHeaders()
                .getFirst(GatewayRequestLoggingFilter.HEADER_NAME)
        ).isEqualTo("reto5-e2e-001");
    }

    @Test
    void shouldGenerateCorrelationIdWhenMissing() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/transfers").build()
        );

        runFilter(exchange, HttpStatus.OK);

        String generated = exchange.getResponse()
            .getHeaders()
            .getFirst(GatewayRequestLoggingFilter.HEADER_NAME);

        assertThat(generated).isNotBlank();
        assertThat(UUID_PATTERN.matcher(generated).matches()).isTrue();
    }

    @Test
    void shouldPreserveHeaderInResponseAndLogRequestFields() throws Exception {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/transfers")
                .header(GatewayRequestLoggingFilter.HEADER_NAME, "step4-final-evidence-20260802")
                .build()
        );

        runFilter(exchange, HttpStatus.ACCEPTED);

        assertThat(appender.list).hasSize(1);

        JsonNode json = encodeToJson(appender.list.get(0));

        assertThat(json.get("event").asText()).isEqualTo("gateway_request_completed");
        assertThat(json.get("method").asText()).isEqualTo("POST");
        assertThat(json.get("path").asText()).isEqualTo("/transfers");
        assertThat(json.get("status").asInt()).isEqualTo(202);
        assertThat(json.has("durationMs")).isTrue();
        assertThat(json.get("correlationId").asText())
            .isEqualTo("step4-final-evidence-20260802");

        assertThat(json.get("traceId").asText()).isNotBlank();
        assertThat(json.get("traceId").asText()).isEqualTo(FAKE_TRACE_ID);
        assertThat(json.get("spanId").asText()).isNotBlank();
        assertThat(json.get("spanId").asText()).isEqualTo(FAKE_SPAN_ID);
    }

    @Test
    void shouldLogTraceIdAndSpanIdWhenDownstreamFails() throws Exception {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/transfers")
                .header(GatewayRequestLoggingFilter.HEADER_NAME, "error-case-001")
                .build()
        );

        runFilterWithinSpan(
            exchange,
            ex -> Mono.error(new RuntimeException("downstream failure"))
        );

        assertThat(appender.list).hasSize(1);

        JsonNode json = encodeToJson(appender.list.get(0));

        assertThat(json.get("event").asText()).isEqualTo("gateway_request_completed");
        assertThat(json.get("method").asText()).isEqualTo("POST");
        assertThat(json.get("path").asText()).isEqualTo("/transfers");
        assertThat(json.get("correlationId").asText()).isEqualTo("error-case-001");
        assertThat(json.get("traceId").asText()).isEqualTo(FAKE_TRACE_ID);
        assertThat(json.get("spanId").asText()).isEqualTo(FAKE_SPAN_ID);
    }

    @Test
    void shouldNotLogSensitiveHeadersOrValues() throws Exception {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/accounts")
                .header("Authorization", "Bearer super-secret-jwt-value")
                .header("Cookie", "session=super-secret-cookie")
                .build()
        );

        runFilter(exchange, HttpStatus.OK);

        JsonNode json = encodeToJson(appender.list.get(0));
        String rawJson = json.toString();

        assertThat(rawJson).doesNotContain("super-secret-jwt-value");
        assertThat(rawJson).doesNotContain("super-secret-cookie");
        assertThat(rawJson).doesNotContainIgnoringCase("authorization");
        assertThat(rawJson).doesNotContainIgnoringCase("cookie");
    }

    @Test
    void shouldSkipLoggingForActuatorHealthAndPrometheus() {
        MockServerWebExchange healthExchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/actuator/health").build()
        );
        runFilter(healthExchange, HttpStatus.OK);

        MockServerWebExchange prometheusExchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/actuator/prometheus").build()
        );
        runFilter(prometheusExchange, HttpStatus.OK);

        assertThat(appender.list).isEmpty();
    }

    private void runFilter(ServerWebExchange exchange, HttpStatus downstreamStatus) {
        runFilterWithinSpan(
            exchange,
            ex -> {
                ex.getResponse().setStatusCode(downstreamStatus);
                return Mono.empty();
            }
        );
    }

    /**
     * Runs the filter with a fake Observation carrying a fake tracing span,
     * written into the Reactor Context under the same key
     * (ObservationThreadLocalAccessor.KEY) that Spring's WebFlux tracing
     * instrumentation uses in production. This mirrors where the real HTTP
     * server span actually lives during a request: in the Reactor Context,
     * not in a thread-local -- which is exactly what the fix relies on.
     */
    private void runFilterWithinSpan(
        ServerWebExchange exchange,
        GatewayFilterChain chain
    ) {
        TraceContext fakeTraceContext = mock(TraceContext.class);
        when(fakeTraceContext.traceId()).thenReturn(FAKE_TRACE_ID);
        when(fakeTraceContext.spanId()).thenReturn(FAKE_SPAN_ID);

        Span fakeSpan = mock(Span.class);
        when(fakeSpan.context()).thenReturn(fakeTraceContext);

        TracingObservationHandler.TracingContext tracingContext =
            new TracingObservationHandler.TracingContext();
        tracingContext.setSpan(fakeSpan);

        Observation observation = Observation.createNotStarted(
            "test-observation",
            ObservationRegistry.create()
        );
        observation.getContext()
            .put(TracingObservationHandler.TracingContext.class, tracingContext);

        Mono<Void> result = filter.filter(exchange, chain)
            .contextWrite(
                Context.of(ObservationThreadLocalAccessor.KEY, observation)
            );

        try {
            result.block();
        } catch (RuntimeException expectedForErrorScenarios) {
            // downstream errors are expected in some tests; doFinally
            // still runs and logs before the error propagates to block()
        }
    }

    private JsonNode encodeToJson(ILoggingEvent event) throws Exception {
        LogstashEncoder encoder = new LogstashEncoder();
        encoder.start();

        byte[] bytes = encoder.encode(event);
        encoder.stop();

        return new ObjectMapper().readTree(
            new String(bytes, StandardCharsets.UTF_8)
        );
    }
}
