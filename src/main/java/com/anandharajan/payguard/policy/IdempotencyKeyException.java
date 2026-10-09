package com.anandharajan.payguard.policy;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class IdempotencyKeyException extends RuntimeException {
    public IdempotencyKeyException(String message) {
        super(message);
    }
}
