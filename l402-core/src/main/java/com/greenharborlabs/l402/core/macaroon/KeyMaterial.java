package com.greenharborlabs.l402.core.macaroon;

import java.util.Arrays;

/**
 * Static utilities for zeroizing sensitive key material in raw byte arrays.
 * Use for intermediate HMAC chain values and other contexts where wrapping
 * in {@code SensitiveBytes} is impractical.
 */
public final class KeyMaterial {

    private KeyMaterial() {
        // utility class
    }

    /**
     * Fills the given array with zeros. Null-safe — a null argument is a no-op.
     *
     * @param data the array to zeroize, may be null
     */
    public static void zeroize(byte[] data) {
        if (data != null) {
            Arrays.fill(data, (byte) 0);
        }
    }

    /**
     * Zeroizes multiple arrays. Null entries are silently skipped.
     *
     * @param arrays the arrays to zeroize, individual entries may be null
     */
    public static void zeroize(byte[]... arrays) {
        if (arrays == null) {
            return;
        }
        for (byte[] data : arrays) {
            zeroize(data);
        }
    }
}
