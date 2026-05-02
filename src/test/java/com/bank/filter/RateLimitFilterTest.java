package com.bank.filter;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class RateLimitFilterTest {

    private RateLimitFilter filter;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new RateLimitFilter();
        chain = mock(FilterChain.class);
    }

    @Test
    @DisplayName("한도 이하 요청은 필터 체인을 통과")
    void underLimit_passesThrough() throws Exception {
        MockHttpServletRequest request = loginRequest("1.2.3.4");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(any(), any());
        assertThat(response.getStatus()).isNotEqualTo(429);
    }

    @Test
    @DisplayName("동일 IP 6번째 요청은 429 반환")
    void overLimit_returns429WithBody() throws Exception {
        String ip = "10.0.0.1";

        for (int i = 0; i < 5; i++) {
            filter.doFilter(loginRequest(ip), new MockHttpServletResponse(), chain);
        }

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(loginRequest(ip), response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentAsString()).contains("한도를 초과");
        verify(chain, times(5)).doFilter(any(), any());
    }

    @Test
    @DisplayName("X-Forwarded-For 헤더 IP로 버킷 구분")
    void xForwardedFor_usedAsClientIp() throws Exception {
        MockHttpServletRequest request = loginRequest(null);
        request.addHeader("X-Forwarded-For", "203.0.113.5, 10.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    @DisplayName("IP 다르면 각자 독립적인 버킷 사용")
    void differentIps_independentBuckets() throws Exception {
        for (int i = 0; i < 5; i++) {
            filter.doFilter(loginRequest("192.168.1.1"), new MockHttpServletResponse(), chain);
        }

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(loginRequest("192.168.1.2"), response, chain);

        assertThat(response.getStatus()).isNotEqualTo(429);
        verify(chain, times(6)).doFilter(any(), any());
    }

    private MockHttpServletRequest loginRequest(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/login");
        if (remoteAddr != null) {
            request.setRemoteAddr(remoteAddr);
        }
        return request;
    }
}
