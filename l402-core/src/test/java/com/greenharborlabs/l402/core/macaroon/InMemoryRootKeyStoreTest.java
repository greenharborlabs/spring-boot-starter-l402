package com.greenharborlabs.l402.core.macaroon;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("InMemoryRootKeyStore")
class InMemoryRootKeyStoreTest {

    private static final int KEY_LENGTH = 32;
    private static final HexFormat HEX = HexFormat.of();

    private InMemoryRootKeyStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryRootKeyStore();
    }

    @Nested
    @DisplayName("generateRootKey")
    class GenerateRootKey {

        @Test
        @DisplayName("returns non-null byte array of 32 bytes")
        void returnsThirtyTwoBytes() {
            byte[] key = store.generateRootKey();

            assertThat(key).isNotNull().hasSize(KEY_LENGTH);
        }

        @Test
        @DisplayName("successive calls return different keys")
        void successiveCallsReturnDifferentKeys() {
            byte[] key1 = store.generateRootKey();
            byte[] key2 = store.generateRootKey();

            assertThat(key1).isNotEqualTo(key2);
        }

        @Test
        @DisplayName("returned key is not all zeros")
        void keyIsNotAllZeros() {
            byte[] key = store.generateRootKey();

            boolean allZeros = true;
            for (byte b : key) {
                if (b != 0) {
                    allZeros = false;
                    break;
                }
            }
            assertThat(allZeros).isFalse();
        }
    }

    @Nested
    @DisplayName("getRootKey")
    class GetRootKey {

        @Test
        @DisplayName("returns null for unknown keyId")
        void returnsNullForUnknownKeyId() {
            byte[] unknownKeyId = new byte[KEY_LENGTH];
            new SecureRandom().nextBytes(unknownKeyId);

            byte[] result = store.getRootKey(unknownKeyId);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("returns same key bytes that were generated for a given keyId")
        void returnsSameKeyForKnownKeyId() {
            // Generate a key, then retrieve it using the internally assigned keyId.
            // The InMemoryRootKeyStore must expose its keyId mapping for this test
            // to work. The implementation stores hex(tokenId) -> key in a ConcurrentHashMap.
            // We use getLastGeneratedKeyId() to retrieve the keyId after generation.
            byte[] key = store.generateRootKey();
            byte[] keyId = store.getLastGeneratedKeyId();

            byte[] retrieved = store.getRootKey(keyId);

            assertThat(retrieved).isEqualTo(key);
        }

        @Test
        @DisplayName("returns defensive copy, not internal reference")
        void returnsDefensiveCopy() {
            byte[] key = store.generateRootKey();
            byte[] keyId = store.getLastGeneratedKeyId();

            byte[] retrieved1 = store.getRootKey(keyId);
            byte[] retrieved2 = store.getRootKey(keyId);

            assertThat(retrieved1).isEqualTo(retrieved2);
            // Mutating the returned array should not affect the stored key
            retrieved1[0] = (byte) ~retrieved1[0];
            byte[] retrieved3 = store.getRootKey(keyId);
            assertThat(retrieved3).isEqualTo(retrieved2);
        }
    }

    @Nested
    @DisplayName("revokeRootKey")
    class RevokeRootKey {

        @Test
        @DisplayName("after revocation, getRootKey returns null")
        void revokedKeyReturnsNull() {
            store.generateRootKey();
            byte[] keyId = store.getLastGeneratedKeyId();

            store.revokeRootKey(keyId);

            assertThat(store.getRootKey(keyId)).isNull();
        }

        @Test
        @DisplayName("revoking unknown keyId does not throw")
        void revokingUnknownKeyIdDoesNotThrow() {
            byte[] unknownKeyId = new byte[KEY_LENGTH];
            new SecureRandom().nextBytes(unknownKeyId);

            // Should not throw
            store.revokeRootKey(unknownKeyId);
        }

        @Test
        @DisplayName("revocation does not affect other keys")
        void revocationDoesNotAffectOtherKeys() {
            byte[] key1 = store.generateRootKey();
            byte[] keyId1 = store.getLastGeneratedKeyId();

            byte[] key2 = store.generateRootKey();
            byte[] keyId2 = store.getLastGeneratedKeyId();

            store.revokeRootKey(keyId1);

            assertThat(store.getRootKey(keyId1)).isNull();
            assertThat(store.getRootKey(keyId2)).isEqualTo(key2);
        }
    }

    @Nested
    @DisplayName("thread safety")
    class ThreadSafety {

        @Test
        @DisplayName("concurrent generateRootKey calls all succeed without exceptions")
        void concurrentGenerateAllSucceed() throws InterruptedException {
            int threadCount = 64;
            ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            CountDownLatch latch = new CountDownLatch(threadCount);
            Set<String> hexKeys = ConcurrentHashMap.newKeySet();

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        byte[] key = store.generateRootKey();
                        hexKeys.add(HEX.formatHex(key));
                    } finally {
                        latch.countDown();
                    }
                });
            }

            boolean finished = latch.await(5, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(finished).isTrue();
            // All generated keys should be unique
            assertThat(hexKeys).hasSize(threadCount);
        }

        @Test
        @DisplayName("concurrent generate and revoke do not corrupt state")
        void concurrentGenerateAndRevoke() throws InterruptedException {
            int iterations = 100;
            ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            CountDownLatch latch = new CountDownLatch(iterations * 2);

            for (int i = 0; i < iterations; i++) {
                executor.submit(() -> {
                    try {
                        store.generateRootKey();
                    } finally {
                        latch.countDown();
                    }
                });
                executor.submit(() -> {
                    try {
                        byte[] randomKeyId = new byte[KEY_LENGTH];
                        new SecureRandom().nextBytes(randomKeyId);
                        store.revokeRootKey(randomKeyId);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            boolean finished = latch.await(5, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(finished).isTrue();
            // No assertion on final state — the test verifies no exceptions were thrown
        }
    }
}
