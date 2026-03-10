package com.greenharborlabs.l402.lightning.lnbits;

/**
 * Thrown when an LNbits API call fails.
 */
public class LnbitsException extends RuntimeException {

    public LnbitsException(String message) {
        super(message);
    }

    public LnbitsException(String message, Throwable cause) {
        super(message, cause);
    }
}
