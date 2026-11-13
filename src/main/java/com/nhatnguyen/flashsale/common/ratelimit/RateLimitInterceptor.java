package com.nhatnguyen.flashsale.common.ratelimit;

import com.nhatnguyen.flashsale.common.exception.RateLimitExceededException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimiterService rateLimiterService;

    public RateLimitInterceptor(RateLimiterService rateLimiterService) {
        this.rateLimiterService = rateLimiterService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // Apply rate limiting specifically on order placement (POST)
        if ("POST".equalsIgnoreCase(request.getMethod()) && request.getRequestURI().contains("/api/v1/orders")) {
            String clientIp = extractClientIp(request);
            if (!rateLimiterService.tryAcquire(clientIp)) {
                throw new RateLimitExceededException("Rate limit exceeded for IP: " + clientIp + ". Please retry shortly.");
            }
        }
        return true;
    }

    private String extractClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
