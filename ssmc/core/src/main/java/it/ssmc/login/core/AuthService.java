package it.ssmc.login.core;

import java.io.IOException;
import java.util.Arrays;

public final class AuthService {
    public record Lookup(Credential credential, boolean local, String error) {}
    private final java.util.function.Function<String, Portal.Reply> resolver;
    private final LocalCache cache;
    public AuthService(Portal portal, LocalCache cache) { this(portal::resolve, cache); }
    AuthService(java.util.function.Function<String, Portal.Reply> resolver, LocalCache cache) { this.resolver = resolver; this.cache = cache; }
    public Lookup resolve(String identity) {
        Portal.Reply reply = resolver.apply(identity);
        try {
            return switch (reply.state()) {
                case FOUND -> { cache.put(reply.credential()); yield new Lookup(reply.credential(), false, null); }
                case DENIED -> { cache.remove(identity); yield new Lookup(null, false, "Account non abilitato: visita ssmc.it"); }
                case UNAVAILABLE -> {
                    Credential c = cache.get(identity);
                    yield new Lookup(c, true, c == null ? "Portale offline: account non sincronizzato" : null);
                }
                case CONFIG_ERROR -> new Lookup(null, false, "Servizio autenticazione non configurato correttamente");
            };
        } catch (IOException e) { return new Lookup(null, false, "Archivio locale non disponibile"); }
    }
    public boolean password(Lookup lookup, char[] password) {
        try { return lookup.credential() != null && lookup.credential().verify(password); }
        finally { Arrays.fill(password, '\0'); }
    }
    public static String javaIdentity(String name) { return "java:" + name.toLowerCase(java.util.Locale.ROOT); }
    public static String bedrockIdentity(String xuid) { return "bedrock:" + xuid; }
}
