package com.finsafe.gateway.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.http.ResponseEntity;

import java.util.concurrent.CompletableFuture;

@Data
@AllArgsConstructor
public class IdempotencyRecord {
    private PaymentRequest request;
    private CompletableFuture<ResponseEntity<PaymentResponse>> responseFuture;
}
