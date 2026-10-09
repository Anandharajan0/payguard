package com.anandharajan.payguard.policy;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.FORBIDDEN)
public class SelfApprovalException extends RuntimeException {
    public SelfApprovalException(String message) {
        super(message);
    }
}
