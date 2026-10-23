package it.ssmc.login.core;
import org.junit.jupiter.api.Test;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CredentialTest {
    private Credential credential(String verifier) {
        return new Credential("site-user", "Player", "java:player", UUID.randomUUID().toString(),
            "00000000-0000-4000-8000-000000000001", "123456", verifier, 1, true);
    }
    @Test void acceptsCorrectPasswordAndRejectsWrongPassword() throws Exception {
        byte[] salt = new byte[16]; Arrays.fill(salt, (byte) 7);
        byte[] hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec("CorrectHorse123".toCharArray(), salt, 600_000, 256)).getEncoded();
        String verifier = "pbkdf2-sha256$600000$" + Base64.getEncoder().encodeToString(salt) + "$" + Base64.getEncoder().encodeToString(hash);
        assertTrue(credential(verifier).verify("CorrectHorse123".toCharArray()));
        assertFalse(credential(verifier).verify("wrong".toCharArray()));
    }
    @Test void malformedAndUnboundedVerifiersAreRejected() {
        for (String bad : List.of("", "plain", "pbkdf2-sha256$999999999$AAAA$AAAA", "pbkdf2-sha256$600000$bad$bad"))
            assertFalse(credential(bad).verify("anything".toCharArray()));
    }
    @Test void autoLoginRequiresCryptographicIdentity() {
        Credential c = credential("unused");
        assertFalse(c.proves(null, null));
        assertFalse(c.proves(UUID.randomUUID().toString(), "99999"));
        assertTrue(c.proves("00000000000040008000000000000001", null));
        assertTrue(c.proves(null, "123456"));
        assertFalse(new Credential("id", "name", "java:name", "id", c.premiumUuid(), c.xuid(), "", 1, false).proves(c.premiumUuid(), c.xuid()));
    }
}
