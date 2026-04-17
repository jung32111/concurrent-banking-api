package com.bank.idempotency;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IdempotencyFilterTest {

    @Mock
    private IdempotencyStore idempotencyStore;

    @Mock
    private FilterChain filterChain;

    private IdempotencyFilter filter;

    @BeforeEach
    void setUp() {
        filter = new IdempotencyFilter(idempotencyStore);
    }

    @Test
    @DisplayName("Idempotency-Key 헤더 없으면 400 반환")
    void missingKeyHeader_returns400() throws ServletException, IOException {
        MockHttpServletRequest request = postTransfer();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(400);
        verifyNoInteractions(filterChain);
    }

    @Test
    @DisplayName("GET 요청은 필터를 건너뜀")
    void getRequest_skipsFilter() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/transfers");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(any(), any());
        verifyNoInteractions(idempotencyStore);
    }

    @Test
    @DisplayName("대상 경로가 아닌 POST는 필터를 건너뜀")
    void nonTargetPath_skipsFilter() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/accounts");
        request.addHeader("Idempotency-Key", "key-1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(any(), any());
        verifyNoInteractions(idempotencyStore);
    }

    @Test
    @DisplayName("최초 요청 — filterChain 실행 후 응답 저장")
    void firstRequest_executesAndStoresResponse() throws ServletException, IOException {
        MockHttpServletRequest request = postTransfer();
        request.addHeader("Idempotency-Key", "key-1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        given(idempotencyStore.getResponse("key-1")).willReturn(Optional.empty());

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(any(), any());
    }

    @Test
    @DisplayName("중복 요청 — 캐시된 응답 반환, filterChain 미실행")
    void duplicateRequest_returnsCachedResponse() throws ServletException, IOException {
        MockHttpServletRequest request = postTransfer();
        request.addHeader("Idempotency-Key", "key-1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        String storedJson = "{\"httpStatus\":200,\"responseBody\":\"{\\\"success\\\":true}\"}";
        given(idempotencyStore.getResponse("key-1")).willReturn(Optional.of(storedJson));

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("X-Idempotency-Replayed")).isEqualTo("true");
        assertThat(response.getContentAsString()).isEqualTo("{\"success\":true}");
        verifyNoInteractions(filterChain);
    }

    @Test
    @DisplayName("IN_PROGRESS 상태 — 409 반환")
    void inProgressRequest_returns409() throws ServletException, IOException {
        MockHttpServletRequest request = postTransfer();
        request.addHeader("Idempotency-Key", "key-1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        given(idempotencyStore.getResponse("key-1")).willReturn(Optional.of("IN_PROGRESS"));

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(409);
        verifyNoInteractions(filterChain);
    }

    @Test
    @DisplayName("처리 실패 시 IN_PROGRESS 키 삭제")
    void failedRequest_deletesKey() throws ServletException, IOException {
        MockHttpServletRequest request = postTransfer();
        request.addHeader("Idempotency-Key", "key-1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        given(idempotencyStore.getResponse("key-1")).willReturn(Optional.empty());
        doThrow(new RuntimeException("서비스 에러"))
                .when(filterChain).doFilter(any(), any());

        try {
            filter.doFilter(request, response, filterChain);
        } catch (RuntimeException ignored) {
        }

        verify(idempotencyStore).delete("key-1");
    }

    private MockHttpServletRequest postTransfer() {
        return new MockHttpServletRequest("POST", "/transfers");
    }
}
