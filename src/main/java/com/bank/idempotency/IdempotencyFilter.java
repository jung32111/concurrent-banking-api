package com.bank.idempotency;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RequiredArgsConstructor
public class IdempotencyFilter extends OncePerRequestFilter {

    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    private static final String REPLAYED_HEADER = "X-Idempotency-Replayed";

    private static final List<String> TARGET_PREFIXES = List.of(
            "/transfers",
            "/transactions"
    );

    private final IdempotencyStore idempotencyStore;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String uri = request.getRequestURI();
        return TARGET_PREFIXES.stream().noneMatch(uri::startsWith);
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

        CachedBodyRequestWrapper wrappedRequest = new CachedBodyRequestWrapper(request);
        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);

        String requestHash = IdempotencyHashUtil.hash(wrappedRequest.getCachedBody());

        GetOrCreateResult result = idempotencyStore.getOrCreate(key, requestHash);

        switch (result) {
            case GetOrCreateResult.InProgress ignored -> {
                response.sendError(HttpStatus.CONFLICT.value(),
                        "동일한 요청이 처리 중입니다. 잠시 후 재시도하세요.");
                return;
            }
            case GetOrCreateResult.Replay replay -> {
                response.setStatus(replay.httpStatus());
                response.setHeader(REPLAYED_HEADER, "true");
                response.getWriter().write(replay.responseBody());
                return;
            }
            case GetOrCreateResult.Fresh ignored -> {
                // fall through to processing
            }
        }

        filterChain.doFilter(wrappedRequest, wrappedResponse);

        // 5xx를 제외한 모든 응답을 재생 대상으로 저장한다.
        // 4xx(한도 초과·잔액 부족 등)도 같은 요청에는 같은 결과를 반환하는 게 멱등성 계약.
        // 5xx는 서버 일시 장애일 수 있어 저장하지 않음 → Fresh 레코드는 response_body NULL로 남고
        // 같은 키로 즉시 재시도 시 InProgress(409). 클라이언트는 새 Idempotency-Key로 재시도.
        if (wrappedResponse.getStatus() < 500) {
            String responseBody = new String(
                    wrappedResponse.getContentAsByteArray(),
                    StandardCharsets.UTF_8
            );
            idempotencyStore.saveResponse(key, wrappedResponse.getStatus(), responseBody);
        }

        wrappedResponse.copyBodyToResponse();
    }
}
