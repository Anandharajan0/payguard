package com.anandharajan.payguard.policy;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.FORBIDDEN)
public class ApprovalScopeException extends RuntimeException {
    public ApprovalScopeException(String message) {
        super(message);
    }
}
