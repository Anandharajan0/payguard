package com.anandharajan.payguard.refund;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public final class OperationNotFoundException extends RuntimeException {

    public OperationNotFoundException(String operationId) {
        super("Refund operation was not found: " + operationId);
    }
}
