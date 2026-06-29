package com.finsafe.gateway.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;

@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private final StringRedisTemplate stringRedisTemplate;
    private static final int MAX_REQUESTS_PER_MINUTE = 100;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String clientIp = request.getRemoteAddr();
        String key = "rate_limit:" + clientIp;

        Long requests = stringRedisTemplate.opsForValue().increment(key);
        
        if (requests != null) {
            // Updated to Duration.ofMinutes(1) to resolve the deprecation warning
            if (requests == 1L) {
                stringRedisTemplate.expire(key, Duration.ofMinutes(1));
            } else {
                Long ttl = stringRedisTemplate.getExpire(key);
                if (ttl != null && ttl == -1L) {
                    stringRedisTemplate.expire(key, Duration.ofMinutes(1));
                }
            }

            if (requests > MAX_REQUESTS_PER_MINUTE) {
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setContentType("text/plain");
                response.getWriter().write("Too Many Requests - Rate Limit Exceeded");
                return false;
            }
        }

        return true;
    }
}