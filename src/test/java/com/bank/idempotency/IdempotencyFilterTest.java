package com.bank.idempotency;

import com.bank.exception.IdempotencyHashMismatchException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

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
    @DisplayName("대상 경로의 하위 URI도 필터를 탐 (startsWith)")
    void nestedTargetPath_filtered() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/transactions/42");
        request.addHeader("Idempotency-Key", "key-1");
        request.setContent("{}".getBytes());
        MockHttpServletResponse response = new MockHttpServletResponse();

        given(idempotencyStore.getOrCreate(eq("key-1"), anyString()))
                .willReturn(new GetOrCreateResult.Fresh());
        respondWith(200, "{\"ok\":true}");

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(any(), any());
    }

    @Test
    @DisplayName("Fresh — filterChain 실행 후 2xx 응답을 저장")
    void fresh_2xx_savesResponse() throws ServletException, IOException {
        MockHttpServletRequest request = postTransfer();
        request.addHeader("Idempotency-Key", "key-1");
        request.setContent("{\"amount\":1000}".getBytes());
        MockHttpServletResponse response = new MockHttpServletResponse();

        given(idempotencyStore.getOrCreate(eq("key-1"), anyString()))
                .willReturn(new GetOrCreateResult.Fresh());
        respondWith(200, "{\"success\":true}");

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(any(), any());
        verify(idempotencyStore).saveResponse(eq("key-1"), eq(200), eq("{\"success\":true}"));
    }

    @Test
    @DisplayName("Fresh — 4xx 응답도 멱등 재생 대상으로 저장")
    void fresh_4xx_savesResponse() throws ServletException, IOException {
        MockHttpServletRequest request = postTransfer();
        request.addHeader("Idempotency-Key", "key-1");
        request.setContent("{\"amount\":999999999}".getBytes());
        MockHttpServletResponse response = new MockHttpServletResponse();

        given(idempotencyStore.getOrCreate(eq("key-1"), anyString()))
                .willReturn(new GetOrCreateResult.Fresh());
        respondWith(422, "{\"error\":\"한도 초과\"}");

        filter.doFilter(request, response, filterChain);

        verify(idempotencyStore).saveResponse(eq("key-1"), eq(422), anyString());
    }

    @Test
    @DisplayName("Fresh — 5xx 응답은 저장하지 않음")
    void fresh_5xx_doesNotSaveResponse() throws ServletException, IOException {
        MockHttpServletRequest request = postTransfer();
        request.addHeader("Idempotency-Key", "key-1");
        request.setContent("{}".getBytes());
        MockHttpServletResponse response = new MockHttpServletResponse();

        given(idempotencyStore.getOrCreate(eq("key-1"), anyString()))
                .willReturn(new GetOrCreateResult.Fresh());
        respondWith(500, "{\"error\":\"내부 오류\"}");

        filter.doFilter(request, response, filterChain);

        verify(idempotencyStore, never()).saveResponse(anyString(), anyInt(), anyString());
    }

    @Test
    @DisplayName("Replay — 캐시된 응답 반환, filterChain 미실행")
    void replay_returnsCachedResponse() throws ServletException, IOException {
        MockHttpServletRequest request = postTransfer();
        request.addHeader("Idempotency-Key", "key-1");
        request.setContent("{}".getBytes());
        MockHttpServletResponse response = new MockHttpServletResponse();

        given(idempotencyStore.getOrCreate(eq("key-1"), anyString()))
                .willReturn(new GetOrCreateResult.Replay(200, "{\"success\":true}"));

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("X-Idempotency-Replayed")).isEqualTo("true");
        assertThat(response.getContentAsString()).isEqualTo("{\"success\":true}");
        verifyNoInteractions(filterChain);
    }

    @Test
    @DisplayName("InProgress — 409 반환, filterChain 미실행")
    void inProgress_returns409() throws ServletException, IOException {
        MockHttpServletRequest request = postTransfer();
        request.addHeader("Idempotency-Key", "key-1");
        request.setContent("{}".getBytes());
        MockHttpServletResponse response = new MockHttpServletResponse();

        given(idempotencyStore.getOrCreate(eq("key-1"), anyString()))
                .willReturn(new GetOrCreateResult.InProgress());

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(409);
        verifyNoInteractions(filterChain);
    }

    @Test
    @DisplayName("해시 불일치 — IdempotencyHashMismatchException 전파")
    void hashMismatch_propagatesException() throws ServletException, IOException {
        MockHttpServletRequest request = postTransfer();
        request.addHeader("Idempotency-Key", "key-1");
        request.setContent("{\"amount\":1000}".getBytes());
        MockHttpServletResponse response = new MockHttpServletResponse();

        given(idempotencyStore.getOrCreate(eq("key-1"), anyString()))
                .willThrow(new IdempotencyHashMismatchException());

        assertThatThrownBy(() -> filter.doFilter(request, response, filterChain))
                .isInstanceOf(IdempotencyHashMismatchException.class);
        verifyNoInteractions(filterChain);
    }

    private void respondWith(int status, String body) throws IOException, ServletException {
        doAnswer(invocation -> {
            HttpServletResponse wrapped = invocation.getArgument(1);
            wrapped.setStatus(status);
            wrapped.getWriter().write(body);
            return null;
        }).when(filterChain).doFilter(any(), any());
    }

    private MockHttpServletRequest postTransfer() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/transfers");
        request.setContent(new byte[0]);
        return request;
    }
}
