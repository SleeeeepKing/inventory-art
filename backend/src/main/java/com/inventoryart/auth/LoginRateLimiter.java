package com.inventoryart.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.inventoryart.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class LoginRateLimiter {
    private final Cache<String, AtomicInteger> attempts = Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(1)).maximumSize(20_000).build();
    public void check(String key) {
        int count = attempts.asMap().computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet();
        if (count > 5) throw new BusinessException("LOGIN_RATE_LIMITED", "Too many login attempts", HttpStatus.TOO_MANY_REQUESTS);
    }
    public void success(String key) { attempts.invalidate(key); }
}

