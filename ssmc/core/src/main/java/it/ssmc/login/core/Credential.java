package it.ssmc.login.core;

import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Shared with the portal. The verifier never contains a plaintext password. */
public record Credential(String accountId, String name, String identity, String serverUuid,
                         String premiumUuid, String xuid, String passwordVerifier, long revision,
                         boolean autoLogin) {
    public boolean verify(char[] password) {
        PBEKeySpec spec = null;
        try {
            String[] parts = passwordVerifier.split("\\$");
            if (parts.length != 4 || !parts[0].equals("pbkdf2-sha256")) return false;
            int iterations = Integer.parseInt(parts[1]);
            if (iterations < 600_000 || iterations > 1_200_000) return false;
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            if (salt.length != 16 || expected.length != 32) return false;
            spec = new PBEKeySpec(password, salt, iterations, 256);
            byte[] actual = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return MessageDigest.isEqual(actual, expected);
        } catch (Exception e) {
            return false;
        } finally {
            if (spec != null) spec.clearPassword();
        }
    }
    public boolean proves(String sessionPremiumUuid, String floodgateXuid) {
        if (!autoLogin) return false;
        return (premiumUuid != null && sessionPremiumUuid != null
                && normalize(premiumUuid).equals(normalize(sessionPremiumUuid)))
            || (xuid != null && xuid.equals(floodgateXuid));
    }
    private static String normalize(String uuid) { return uuid.replace("-", "").toLowerCase(java.util.Locale.ROOT); }
}
