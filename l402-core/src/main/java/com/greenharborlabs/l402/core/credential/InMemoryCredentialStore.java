package com.greenharborlabs.l402.core.credential;

import com.greenharborlabs.l402.core.protocol.L402Credential;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryCredentialStore implements CredentialStore {

    private final ConcurrentHashMap<String, CachedCredential> entries = new ConcurrentHashMap<>();

    @Override
    public void store(String tokenId, L402Credential credential, long ttlSeconds) {
        Instant expiresAt = Instant.now().plusSeconds(ttlSeconds);
        entries.put(tokenId, new CachedCredential(credential, expiresAt));
    }

    @Override
    public L402Credential get(String tokenId) {
        CachedCredential cached = entries.get(tokenId);
        if (cached == null) {
            return null;
        }
        if (cached.isExpired()) {
            entries.remove(tokenId);
            return null;
        }
        return cached.credential();
    }

    @Override
    public void revoke(String tokenId) {
        entries.remove(tokenId);
    }

    @Override
    public long activeCount() {
        long count = 0;
        var iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getValue().isExpired()) {
                iterator.remove();
            } else {
                count++;
            }
        }
        return count;
    }
}
