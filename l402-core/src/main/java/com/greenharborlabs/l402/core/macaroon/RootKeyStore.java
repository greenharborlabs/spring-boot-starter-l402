package com.greenharborlabs.l402.core.macaroon;

public interface RootKeyStore {
    byte[] generateRootKey();
    byte[] getRootKey(byte[] keyId);
    void revokeRootKey(byte[] keyId);
}
