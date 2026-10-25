package dev.kairos.mocktarget.behavior.application;

/** Verification port keeps transport code independent of adapters. */
@FunctionalInterface
public interface VerifyRequest {
    boolean verify(String method, String path, String key, byte[] body, String timestamp, String signature);
}
