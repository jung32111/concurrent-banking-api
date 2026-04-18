package com.bank.idempotency;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class IdempotencyHashUtil {

    private IdempotencyHashUtil() {}

    public static String hash(byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(body);
            StringBuilder sb = new StringBuilder(hashBytes.length * 2);
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 알고리즘 사용 불가", e);
        }
    }
}
