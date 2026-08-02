package com.modularbank.transfers.shared.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter
    extends OncePerRequestFilter {

    public static final String HEADER_NAME =
        "X-Correlation-Id";

    private static final Logger LOGGER =
        LoggerFactory.getLogger(
            CorrelationIdFilter.class
        );

    private static final Pattern SAFE_VALUE =
        Pattern.compile(
            "[A-Za-z0-9._-]{1,100}"
        );

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {

        String correlationId =
            resolveCorrelationId(
                request.getHeader(HEADER_NAME)
            );

        long startedAt = System.nanoTime();

        MDC.put("correlationId", correlationId);

        response.setHeader(
            HEADER_NAME,
            correlationId
        );

        try {
            filterChain.doFilter(
                request,
                response
            );

        } finally {
            long durationMs =
                TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - startedAt
                );

            LOGGER.info(
                "HTTP request completed method={} path={} status={} durationMs={}",
                request.getMethod(),
                request.getRequestURI(),
                response.getStatus(),
                durationMs
            );

            MDC.remove("correlationId");
        }
    }

    private String resolveCorrelationId(
        String requestedValue
    ) {
        if (
            requestedValue != null
                && SAFE_VALUE
                    .matcher(requestedValue)
                    .matches()
        ) {
            return requestedValue;
        }

        return UUID.randomUUID().toString();
    }
}
