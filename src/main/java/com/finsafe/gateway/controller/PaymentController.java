package com.finsafe.gateway.controller;

import com.finsafe.gateway.model.PaymentRequest;
import com.finsafe.gateway.model.PaymentResponse;
import com.finsafe.gateway.service.IdempotencyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class PaymentController {

    private final IdempotencyService idempotencyService;

    @PostMapping("/process-payment")
    public ResponseEntity<PaymentResponse> processPayment(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PaymentRequest request) {

        return idempotencyService.processIdempotentRequest(idempotencyKey, request, () -> {
            try {
                // Simulate processing delay
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Processing interrupted", e);
            }

            PaymentResponse response = PaymentResponse.builder()
                    .status("Charged " + request.getAmount() + " " + request.getCurrency())
                    .build();

            return ResponseEntity.ok(response);
        });
    }
}
