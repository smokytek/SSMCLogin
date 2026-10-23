package it.ssmc.login.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class ProxyProof {
    private ProxyProof() {}
    public static byte[] sign(UUID player, String key) {
        String payload = player + ":" + Instant.now().getEpochSecond() + ":" + UUID.randomUUID();
        return (payload + ":" + signature(payload, key)).getBytes(StandardCharsets.UTF_8);
    }
    public static String validate(byte[] bytes, UUID player, String key) {
        if (key == null || key.length() < 32 || bytes.length > 512) return null;
        String value = new String(bytes, StandardCharsets.UTF_8);
        String[] parts = value.split(":");
        if (parts.length != 4 || !parts[0].equals(player.toString())) return null;
        try {
            long time = Long.parseLong(parts[1]);
            if (Math.abs(Instant.now().getEpochSecond() - time) > 10) return null;
            String payload = parts[0] + ":" + parts[1] + ":" + parts[2];
            if (!MessageDigest.isEqual(signature(payload, key).getBytes(StandardCharsets.US_ASCII), parts[3].getBytes(StandardCharsets.US_ASCII))) return null;
            return parts[2];
        } catch (RuntimeException e) { return null; }
    }
    private static String signature(String payload, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
