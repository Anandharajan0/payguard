package com.anandharajan.payguard.paypal;

public final class PayPalLookupException extends RuntimeException {

    private final String errorCode;

    public PayPalLookupException(String errorCode) {
        this(errorCode, null);
    }

    public PayPalLookupException(String errorCode, Throwable cause) {
        super(errorCode, cause);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
