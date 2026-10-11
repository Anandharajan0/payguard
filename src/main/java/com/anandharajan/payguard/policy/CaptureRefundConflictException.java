package com.anandharajan.payguard.policy;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public final class CaptureRefundConflictException extends RuntimeException {

    public CaptureRefundConflictException(String message) {
        super(message);
    }

    public CaptureRefundConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
