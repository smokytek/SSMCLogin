package it.ssmc.login.core;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Atomic, persistent cache; only portal-accepted accounts may enter it. */
public final class LocalCache {
    private final Path path;
    private final Map<String, Credential> entries;
    private final Gson gson = new Gson();
    public LocalCache(Path path) throws IOException {
        this.path = path;
        if (Files.exists(path)) {
            Map<String, Credential> parsed = gson.fromJson(Files.readString(path), new TypeToken<Map<String, Credential>>() {}.getType());
            if (parsed == null) throw new IOException("Invalid credential cache");
            entries = new HashMap<>(parsed);
        } else entries = new HashMap<>();
    }
    public synchronized Credential get(String identity) { return entries.get(identity); }
    public synchronized void put(Credential c) throws IOException {
        Credential previous = entries.put(c.identity(), c);
        try { save(); } catch (IOException e) {
            if (previous == null) entries.remove(c.identity()); else entries.put(c.identity(), previous);
            throw e;
        }
    }
    public synchronized void remove(String identity) throws IOException {
        entries.remove(identity); save();
    }
    private void save() throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        Path temp = Files.createTempFile(path.toAbsolutePath().getParent(), "ssmc-cache-", ".tmp");
        try {
            Files.writeString(temp, gson.toJson(entries));
            try { Files.setPosixFilePermissions(temp, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); }
            catch (UnsupportedOperationException ignored) { /* Windows: protect the plugin folder with an ACL. */ }
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temp); }
    }
}
