package it.ssmc.login.core;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
class AuthServiceTest {
    @TempDir Path directory;
    private final Credential c = new Credential("user", "Name", "java:name", "uuid", null, null, "verifier", 1, false);
    @Test void outageAllowsOnlyPreviouslySynchronizedAccounts() throws Exception {
        LocalCache cache = new LocalCache(directory.resolve("cache.json"));
        AuthService service = new AuthService(key -> new Portal.Reply(Portal.State.UNAVAILABLE, null), cache);
        assertNull(service.resolve(c.identity()).credential());
        cache.put(c);
        var result = service.resolve(c.identity());
        assertEquals(c, result.credential()); assertTrue(result.local());
    }
    @Test void rejectedAccountsAreEvictedAndCannotUseOutageFallback() throws Exception {
        Path path = directory.resolve("cache.json");
        LocalCache cache = new LocalCache(path); cache.put(c);
        AuthService denied = new AuthService(key -> new Portal.Reply(Portal.State.DENIED, null), cache);
        assertNull(denied.resolve(c.identity()).credential());
        assertNull(new LocalCache(path).get(c.identity()));
        assertNull(new AuthService(key -> new Portal.Reply(Portal.State.UNAVAILABLE, null), cache).resolve(c.identity()).credential());
    }
    @Test void invalidApiKeyOrMalformedResponseCannotTriggerFallback() throws Exception {
        LocalCache cache = new LocalCache(directory.resolve("cache.json")); cache.put(c);
        assertNull(new AuthService(key -> new Portal.Reply(Portal.State.CONFIG_ERROR, null), cache).resolve(c.identity()).credential());
    }
    @Test void acceptedPasswordRotationReplacesPersistentVerifier() throws Exception {
        Path path = directory.resolve("cache.json");
        LocalCache cache = new LocalCache(path); cache.put(c);
        Credential next = new Credential("user", "Name", "java:name", "uuid", null, null, "new-verifier", 2, false);
        assertEquals(next, new AuthService(key -> new Portal.Reply(Portal.State.FOUND, next), cache).resolve(c.identity()).credential());
        assertEquals(next, new LocalCache(path).get(c.identity()));
    }
}
