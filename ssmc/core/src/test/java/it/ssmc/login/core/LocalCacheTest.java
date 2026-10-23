package it.ssmc.login.core;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
class LocalCacheTest {
    @TempDir Path directory;
    @Test void cacheSurvivesRestartAndPersistsRevocation() throws Exception {
        Path path = directory.resolve("credentials.json");
        Credential c = new Credential("id", "Name", "java:name", "uuid", null, null, "hash", 1, false);
        new LocalCache(path).put(c);
        assertEquals(c, new LocalCache(path).get("java:name"));
        new LocalCache(path).remove("java:name");
        assertNull(new LocalCache(path).get("java:name"));
    }
    @Test void signedBackendProofCannotBeForgedOrUsedForAnotherPlayer() {
        var player = java.util.UUID.randomUUID();
        String key = "12345678901234567890123456789012";
        byte[] proof = ProxyProof.sign(player, key);
        assertNotNull(ProxyProof.validate(proof, player, key));
        assertNull(ProxyProof.validate(proof, java.util.UUID.randomUUID(), key));
        assertNull(ProxyProof.validate(proof, player, "00000000000000000000000000000000"));
        proof[0] ^= 1;
        assertNull(ProxyProof.validate(proof, player, key));
    }
}
