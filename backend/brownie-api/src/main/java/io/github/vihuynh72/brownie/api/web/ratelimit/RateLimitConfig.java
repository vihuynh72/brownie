package io.github.vihuynh72.brownie.api.web.ratelimit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.EnumMap;
import java.util.Map;

/**
 * The allowances are requests a minute for one person. They are set where
 * nobody using the web app would meet them (it polls a running job about
 * forty times a minute and a full browser test run makes a few dozen
 * changes) and where one account still cannot take the host: the render
 * allowance in particular sits beside the ceiling on simultaneous renders,
 * not instead of it.
 */
@Configuration
@ConditionalOnProperty(name = "brownie.rate-limit.enabled", havingValue = "true", matchIfMissing = true)
class RateLimitConfig {

    @Bean
    RateLimiter rateLimiter(
            @Value("${brownie.rate-limit.per-minute.model:30}") int model,
            @Value("${brownie.rate-limit.per-minute.render:30}") int render,
            @Value("${brownie.rate-limit.per-minute.upload:120}") int upload,
            @Value("${brownie.rate-limit.per-minute.connector:60}") int connector,
            @Value("${brownie.rate-limit.per-minute.write:300}") int write,
            @Value("${brownie.rate-limit.per-minute.read:1200}") int read,
            @Value("${brownie.rate-limit.per-minute.anonymous:120}") int anonymous) {
        Map<RateLimitClass, Integer> perMinute = new EnumMap<>(RateLimitClass.class);
        perMinute.put(RateLimitClass.MODEL, model);
        perMinute.put(RateLimitClass.RENDER, render);
        perMinute.put(RateLimitClass.UPLOAD, upload);
        perMinute.put(RateLimitClass.CONNECTOR, connector);
        perMinute.put(RateLimitClass.WRITE, write);
        perMinute.put(RateLimitClass.READ, read);
        perMinute.put(RateLimitClass.ANONYMOUS, anonymous);
        return new RateLimiter(perMinute, System::nanoTime);
    }

    @Bean
    RateLimitFilter rateLimitFilter(RateLimiter rateLimiter) {
        return new RateLimitFilter(rateLimiter);
    }

    /**
     * The filter runs inside the security filter chain, where the security
     * configuration puts it. Any filter that is a bean is also put in the
     * servlet container's own chain unless that is switched off, and there
     * it would run a second time, after the security chain had already
     * answered everything it answers by itself.
     */
    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilterIsNotAlsoAServletFilter(RateLimitFilter rateLimitFilter) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(rateLimitFilter);
        registration.setEnabled(false);
        return registration;
    }
}
