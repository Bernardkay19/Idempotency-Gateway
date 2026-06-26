package com.finsafe.gateway.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.http.ResponseEntity;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IdempotencyRecord {
    public enum Status {
        IN_PROGRESS,
        COMPLETED
    }

    private Status status;
    private PaymentRequest request;
    // We cannot store ResponseEntity natively in Redis easily due to its generic nature and lack of no-arg constructors in some inner types,
    // so we store the raw Response payload and Status Code.
    private PaymentResponse responseBody;
    private int responseStatusCode;
}
