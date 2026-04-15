package com.bank.accountservice.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Slf4j
public class TraceIdFilter extends OncePerRequestFilter {

    private static final String TRACE_ID_MDC_KEY = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String traceId = request.getHeader("X-Trace-Id") != null
                ? request.getHeader("X-Trace-Id")
                : UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        MDC.put(TRACE_ID_MDC_KEY, traceId);
        response.setHeader("X-Trace-Id", traceId);

        String method = request.getMethod();
        String uri = request.getRequestURI();
        long startTime = System.currentTimeMillis();

        log.info("[TraceId: {}] {} {} 시작", traceId, method, uri);

        try {
            filterChain.doFilter(request, response);
        } finally {
            long elapsed = System.currentTimeMillis() - startTime;
            log.info("[TraceId: {}] 응답 완료 - {} - {}ms", traceId, response.getStatus(), elapsed);
            MDC.clear();
        }
    }
}
