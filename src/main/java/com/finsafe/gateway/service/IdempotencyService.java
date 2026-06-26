package com.finsafe.gateway.service;

import com.finsafe.gateway.model.IdempotencyRecord;
import com.finsafe.gateway.model.PaymentRequest;
import com.finsafe.gateway.model.PaymentResponse;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Service
public class IdempotencyService {

    // The Developer's Choice: Caffeine Cache with TTL
    // This cache will automatically expire entries after 24 hours to prevent memory leaks
    private final Cache<String, IdempotencyRecord> cache = Caffeine.newBuilder()
            .expireAfterWrite(24, TimeUnit.HOURS)
            .maximumSize(100_000)
            .build();

    public ResponseEntity<PaymentResponse> processIdempotentRequest(
            String idempotencyKey,
            PaymentRequest request,
            Supplier<ResponseEntity<PaymentResponse>> logic) {

        CompletableFuture<ResponseEntity<PaymentResponse>> newFuture = new CompletableFuture<>();
        IdempotencyRecord newRecord = new IdempotencyRecord(request, newFuture);

        // putIfAbsent is thread-safe and atomic
        IdempotencyRecord existingRecord = cache.asMap().putIfAbsent(idempotencyKey, newRecord);

        if (existingRecord != null) {
            // Check if the request body is the same (Fraud/Error Check)
            if (!existingRecord.getRequest().equals(request)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key already used for a different request body.");
            }

            // Same key, same body: wait for the original process to finish, or get the finished result
            try {
                ResponseEntity<PaymentResponse> cachedResponse = existingRecord.getResponseFuture().get(); // This will block if still in-flight
                
                // Return cached response with a custom header
                return ResponseEntity.status(cachedResponse.getStatusCode())
                        .headers(headers -> {
                            headers.addAll(cachedResponse.getHeaders());
                            headers.set("X-Cache-Hit", "true");
                        })
                        .body(cachedResponse.getBody());
            } catch (Exception e) {
                // If the original request failed, we shouldn't cache the failure indefinitely,
                // but for this scope, returning 500 is fine.
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Error processing idempotent request.");
            }
        }

        // We are the first thread to handle this key!
        try {
            ResponseEntity<PaymentResponse> response = logic.get();
            newRecord.getResponseFuture().complete(response);
            return response;
        } catch (Exception e) {
            newRecord.getResponseFuture().completeExceptionally(e);
            cache.invalidate(idempotencyKey); // Remove so it can be retried cleanly
            throw e;
        }
    }
}
