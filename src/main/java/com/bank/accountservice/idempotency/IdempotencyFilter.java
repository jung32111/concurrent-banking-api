package com.bank.accountservice.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;

@RequiredArgsConstructor
public class IdempotencyFilter extends OncePerRequestFilter {

    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    private static final String REPLAYED_HEADER = "X-Idempotency-Replayed";
    private static final String IN_PROGRESS = "IN_PROGRESS";

    private static final Set<String> TARGET_PATHS = Set.of(
            "/transfers",
            "/transactions"
    );

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final IdempotencyStore idempotencyStore;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod())
                || !TARGET_PATHS.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String key = request.getHeader(IDEMPOTENCY_KEY_HEADER);
        if (!StringUtils.hasText(key)) {
            response.sendError(HttpStatus.BAD_REQUEST.value(), "멱등성 키 헤더가 필요합니다");
            return;
        }
        ContentCachingRequestWrapper wrappedRequest = new ContentCachingRequestWrapper(request);
        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);
        wrappedRequest.getInputStream().readAllBytes();

        // 캐시 조회 — getResponse() 한 번으로 멱등성 판단
        Optional<String> stored = idempotencyStore.getResponse(key);
        if (stored.isPresent()) {
            String value = stored.get();

            if (IN_PROGRESS.equals(value)) {
                response.sendError(HttpStatus.CONFLICT.value(), "동일한 요청이 처리 중입니다. 잠시 후 재시도하세요.");
                return;
            }

            // 이전에 완료된 응답 재사용
            StoredResponse storedResponse = OBJECT_MAPPER.readValue(value, StoredResponse.class);
            response.setStatus(storedResponse.httpStatus());
            response.setHeader(REPLAYED_HEADER, "true");
            response.getWriter().write(storedResponse.responseBody());
            return;
        }

        boolean saved = false;
        try {
            filterChain.doFilter(wrappedRequest, wrappedResponse);

            if (wrappedResponse.getStatus() >= 200 && wrappedResponse.getStatus() < 300) {
                String responseBody = new String(
                        wrappedResponse.getContentAsByteArray(),
                        StandardCharsets.UTF_8
                );
                String json = OBJECT_MAPPER.writeValueAsString(
                        new StoredResponse(wrappedResponse.getStatus(), responseBody)
                );
                idempotencyStore.save(key, json);
                saved = true;
            }
        } finally {
            // 예외 발생 또는 비2xx 응답 시 IN_PROGRESS 키를 즉시 해제해
            // 클라이언트가 30초 TTL 만료를 기다리지 않고 즉시 재시도할 수 있도록 한다.
            if (!saved) {
                idempotencyStore.delete(key);
            }
            wrappedResponse.copyBodyToResponse();
        }
    }
}
