package com.bank.accountservice.config;

import com.bank.accountservice.filter.RateLimitFilter;
import com.bank.accountservice.filter.TraceIdFilter;
import com.bank.accountservice.idempotency.IdempotencyFilter;
import com.bank.accountservice.idempotency.IdempotencyStore;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FilterConfig {

    /**
     * TraceIdFilter: 모든 요청에 고유 TraceId 부여 및 MDC 설정.
     * Spring Security FilterChain(order: -100)보다 반드시 먼저 실행되도록 order -200 지정.
     */
    @Bean
    public TraceIdFilter traceIdFilter() {
        return new TraceIdFilter();
    }

    @Bean
    public FilterRegistrationBean<TraceIdFilter> traceIdFilterRegistration(TraceIdFilter traceIdFilter) {
        FilterRegistrationBean<TraceIdFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(traceIdFilter);
        registration.addUrlPatterns("/*");
        registration.setOrder(-200);
        return registration;
    }

    @Bean
    public IdempotencyFilter idempotencyFilter(IdempotencyStore idempotencyStore) {
        return new IdempotencyFilter(idempotencyStore);
    }

    @Bean
    public FilterRegistrationBean<IdempotencyFilter> idempotencyFilterRegistration(IdempotencyFilter idempotencyFilter) {
        FilterRegistrationBean<IdempotencyFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(idempotencyFilter);
        registration.addUrlPatterns(
                "/transfers",
                "/transfers/*",
                "/transactions",
                "/transactions/*"
        );
        registration.setOrder(2);
        return registration;
    }

    @Bean
    public RateLimitFilter rateLimitFilter() {
        return new RateLimitFilter();
    }

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitFilter rateLimitFilter) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(rateLimitFilter);
        registration.addUrlPatterns("/auth/login");
        registration.setOrder(1);
        return registration;
    }
}
