package com.greenharborlabs.l402.core.macaroon;

import java.util.Arrays;

public interface RootKeyStore {

    /**
     * Result of generating a new root key, containing both the root key
     * and the tokenId that identifies it atomically.
     */
    record GenerationResult(byte[] rootKey, byte[] tokenId) {
        public GenerationResult {
            rootKey = Arrays.copyOf(rootKey, rootKey.length);
            tokenId = Arrays.copyOf(tokenId, tokenId.length);
        }

        @Override
        public byte[] rootKey() {
            return Arrays.copyOf(rootKey, rootKey.length);
        }

        @Override
        public byte[] tokenId() {
            return Arrays.copyOf(tokenId, tokenId.length);
        }
    }

    GenerationResult generateRootKey();
    byte[] getRootKey(byte[] keyId);
    void revokeRootKey(byte[] keyId);
}
