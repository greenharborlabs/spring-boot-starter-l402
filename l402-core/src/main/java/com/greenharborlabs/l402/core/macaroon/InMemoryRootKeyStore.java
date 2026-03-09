package com.greenharborlabs.l402.core.macaroon;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of {@link RootKeyStore} backed by a {@link ConcurrentHashMap}.
 * Keys are lost when the JVM exits. Suitable for testing and short-lived processes.
 */
public final class InMemoryRootKeyStore implements RootKeyStore {

    private static final int KEY_LENGTH = 32;
    private static final HexFormat HEX = HexFormat.of();

    private final SecureRandom secureRandom = new SecureRandom();
    private final ConcurrentHashMap<String, byte[]> keys = new ConcurrentHashMap<>();
    private volatile byte[] lastGeneratedKeyId;

    @Override
    public byte[] generateRootKey() {
        byte[] rootKey = new byte[KEY_LENGTH];
        secureRandom.nextBytes(rootKey);

        byte[] tokenId = new byte[KEY_LENGTH];
        secureRandom.nextBytes(tokenId);

        String hexKeyId = HEX.formatHex(tokenId);
        keys.put(hexKeyId, rootKey);
        lastGeneratedKeyId = Arrays.copyOf(tokenId, tokenId.length);

        return Arrays.copyOf(rootKey, rootKey.length);
    }

    @Override
    public byte[] getRootKey(byte[] keyId) {
        String hexKeyId = HEX.formatHex(keyId);
        byte[] stored = keys.get(hexKeyId);
        return stored == null ? null : Arrays.copyOf(stored, stored.length);
    }

    @Override
    public void revokeRootKey(byte[] keyId) {
        String hexKeyId = HEX.formatHex(keyId);
        keys.remove(hexKeyId);
    }

    /**
     * Returns a defensive copy of the tokenId from the last {@link #generateRootKey()} call.
     * Used by tests to retrieve the keyId needed for {@link #getRootKey(byte[])}.
     */
    public byte[] getLastGeneratedKeyId() {
        byte[] id = lastGeneratedKeyId;
        return id == null ? null : Arrays.copyOf(id, id.length);
    }
}
