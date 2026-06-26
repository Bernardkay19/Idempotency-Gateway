package com.finsafe.gateway.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private final StringRedisTemplate stringRedisTemplate;
    
    // Allow 100 requests per minute per IP
    private static final int MAX_REQUESTS_PER_MINUTE = 100;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String clientIp = request.getRemoteAddr();
        String key = "rate_limit:" + clientIp;

        Long requests = stringRedisTemplate.opsForValue().increment(key);
        
        // If it's the first request in the window, set the expiry
        if (requests != null && requests == 1L) {
            stringRedisTemplate.expire(key, 1, TimeUnit.MINUTES);
        }

        if (requests != null && requests > MAX_REQUESTS_PER_MINUTE) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.getWriter().write("Too Many Requests - Rate Limit Exceeded");
            return false;
        }

        return true;
    }
}
