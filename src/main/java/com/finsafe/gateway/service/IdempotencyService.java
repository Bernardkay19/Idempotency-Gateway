package com.finsafe.gateway.service;

import com.finsafe.gateway.model.IdempotencyRecord;
import com.finsafe.gateway.model.PaymentRequest;
import com.finsafe.gateway.model.PaymentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final RedisTemplate<String, Object> redisTemplate;

    private static final String IDEMPOTENCY_PREFIX = "idempotency:";

    public ResponseEntity<PaymentResponse> processIdempotentRequest(
            String idempotencyKey,
            PaymentRequest request,
            Supplier<ResponseEntity<PaymentResponse>> logic) {

        String key = IDEMPOTENCY_PREFIX + idempotencyKey;

        IdempotencyRecord newRecord = IdempotencyRecord.builder()
                .status(IdempotencyRecord.Status.IN_PROGRESS)
                .request(request)
                .build();

        // Fixed deprecation warning by using Duration.ofMinutes()
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, newRecord, Duration.ofMinutes(5));

        if (Boolean.TRUE.equals(acquired)) {
            try {
                ResponseEntity<PaymentResponse> response = logic.get();

                IdempotencyRecord completedRecord = IdempotencyRecord.builder()
                        .status(IdempotencyRecord.Status.COMPLETED)
                        .request(request)
                        .responseBody(response.getBody())
                        .responseStatusCode(response.getStatusCode().value())
                        .build();

                // Fixed deprecation warning by using Duration.ofHours()
                redisTemplate.opsForValue().set(key, completedRecord, Duration.ofHours(24));
                return response;
            } catch (Exception e) {
                redisTemplate.delete(key);
                throw e;
            }
        } else {
            return handleExistingRecord(key, request);
        }
    }

    private ResponseEntity<PaymentResponse> handleExistingRecord(String key, PaymentRequest request) {
        int maxRetries = 30;

        while (maxRetries > 0) {
            Object rawRecord = redisTemplate.opsForValue().get(key);
            if (rawRecord == null) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Idempotency state lost. Please retry.");
            }

            IdempotencyRecord record = (IdempotencyRecord) rawRecord;

            if (!record.getRequest().equals(request)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key already used for a different request body.");
            }

            if (record.getStatus() == IdempotencyRecord.Status.COMPLETED) {
                return ResponseEntity.status(record.getResponseStatusCode())
                        .header("X-Cache-Hit", "true")
                        .body(record.getResponseBody());
            }

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Polling interrupted");
            }
            maxRetries--;
        }

        throw new ResponseStatusException(HttpStatus.REQUEST_TIMEOUT, "Timeout waiting for concurrent request to complete.");
    }
}