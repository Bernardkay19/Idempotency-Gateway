package com.finsafe.gateway.service;

import com.finsafe.gateway.model.IdempotencyRecord;
import com.finsafe.gateway.model.PaymentRequest;
import com.finsafe.gateway.model.PaymentResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

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

        // setIfAbsent acts like putIfAbsent / SETNX in Redis
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, newRecord, 24, TimeUnit.HOURS);

        if (Boolean.TRUE.equals(acquired)) {
            // We are the first thread to handle this key!
            try {
                ResponseEntity<PaymentResponse> response = logic.get();
                
                // Update the record to COMPLETED
                newRecord.setStatus(IdempotencyRecord.Status.COMPLETED);
                newRecord.setResponseBody(response.getBody());
                newRecord.setResponseStatusCode(response.getStatusCode().value());
                
                redisTemplate.opsForValue().set(key, newRecord, 24, TimeUnit.HOURS);
                
                return response;
            } catch (Exception e) {
                // If it fails, remove the key so it can be cleanly retried
                redisTemplate.delete(key);
                throw e;
            }
        }

        // Key already exists. We need to handle the state.
        return handleExistingRecord(key, request);
    }

    private ResponseEntity<PaymentResponse> handleExistingRecord(String key, PaymentRequest request) {
        int maxRetries = 50; // 5 seconds max wait (50 * 100ms)
        
        while (maxRetries > 0) {
            Object rawRecord = redisTemplate.opsForValue().get(key);
            if (rawRecord == null) {
                // Key disappeared (maybe it failed and was deleted). Let's throw an error to retry.
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Idempotency state lost. Please retry.");
            }

            // Convert raw linked hash map from Jackson to IdempotencyRecord
            IdempotencyRecord record = objectMapper.convertValue(rawRecord, IdempotencyRecord.class);

            if (!record.getRequest().equals(request)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key already used for a different request body.");
            }

            if (record.getStatus() == IdempotencyRecord.Status.COMPLETED) {
                return ResponseEntity.status(record.getResponseStatusCode())
                        .header("X-Cache-Hit", "true")
                        .body(record.getResponseBody());
            }

            // Still IN_PROGRESS, wait and poll
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
