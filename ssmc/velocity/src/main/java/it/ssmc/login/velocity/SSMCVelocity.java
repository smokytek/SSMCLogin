package it.ssmc.login.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.*;
import com.velocitypowered.api.event.connection.*;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.player.*;
import com.velocitypowered.api.event.proxy.*;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.*;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.util.GameProfile;
import it.ssmc.login.core.*;
import net.kyori.adventure.text.Component;
import org.geysermc.floodgate.api.FloodgateApi;
import org.slf4j.Logger;
import java.net.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

@Plugin(id = "ssmclogin", name = "SSMCLogin", version = "0.1.0-dev", authors = {"SSMC"},
        dependencies = {@com.velocitypowered.api.plugin.Dependency(id = "floodgate", optional = true)})
public final class SSMCVelocity {
    private static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.from("ssmclogin:auth");
    private static final class Session {
        final long started = System.currentTimeMillis();
        volatile boolean verifiedPremium, authenticated, busy;
        volatile AuthService.Lookup lookup;
        volatile String xuid;
        volatile String premiumUuid;
        volatile long lastLink;
        int failures;
    }
    private final ProxyServer server;
    private final Logger logger;
    private final Path directory;
    private final Map<InetSocketAddress, Session> sessions = new ConcurrentHashMap<>();
    private final AttemptLimit attempts = new AttemptLimit();
    private final ExecutorService workers = new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128), new ThreadPoolExecutor.AbortPolicy());
    private volatile boolean ready;
    private AuthService auth;
    private Portal portal;
    private String authServer, lobbyServer, offlineHost, premiumHost, proxyKey;
    @Inject public SSMCVelocity(ProxyServer server, Logger logger, @DataDirectory Path directory) {
        this.server = server; this.logger = logger; this.directory = directory;
    }
    @Subscribe public void init(ProxyInitializeEvent event) {
        try {
            Files.createDirectories(directory);
            Path file = directory.resolve("ssmclogin.properties");
            if (!Files.exists(file)) Files.writeString(file, "portal-url=https://ssmc.it/api/minecraft/resolve\nauth-server=auth\nlobby-server=lobby\noffline-host=offline.ssmc.it\npremium-host=premium.ssmc.it\n");
            Properties settings = new Properties();
            try (var input = Files.newInputStream(file)) { settings.load(input); }
            proxyKey = System.getenv("SSMCLOGIN_PROXY_KEY");
            if (proxyKey == null || proxyKey.length() < 32) throw new IllegalArgumentException("Missing SSMCLOGIN_PROXY_KEY");
            authServer = settings.getProperty("auth-server", "auth");
            lobbyServer = settings.getProperty("lobby-server", "lobby");
            offlineHost = settings.getProperty("offline-host", "").toLowerCase(Locale.ROOT);
            premiumHost = settings.getProperty("premium-host", "").toLowerCase(Locale.ROOT);
            if (server.getServer(authServer).isEmpty() || server.getServer(lobbyServer).isEmpty()) throw new IllegalArgumentException("Auth/lobby server not registered");
            portal = new Portal(URI.create(settings.getProperty("portal-url")), System.getenv("SSMCLOGIN_API_KEY"));
            auth = new AuthService(portal,
                    new LocalCache(directory.resolve("credentials.json")));
            server.getChannelRegistrar().register(CHANNEL);
            var meta = server.getCommandManager().metaBuilder("login").aliases("l").plugin(this).build();
            server.getCommandManager().register(meta, new LoginCommand());
            server.getCommandManager().register(server.getCommandManager().metaBuilder("ssmclink").plugin(this).build(), new LinkCommand());
            server.getScheduler().buildTask(this, () -> {
                long deadline = System.currentTimeMillis() - 90_000;
                for (Player player : server.getAllPlayers()) {
                    Session s = sessions.get(player.getRemoteAddress());
                    if (s == null || (!s.authenticated && s.started < deadline)) player.disconnect(Component.text("Tempo per il login scaduto"));
                }
                sessions.entrySet().removeIf(e -> e.getValue().started < System.currentTimeMillis() - 180_000
                        && server.getAllPlayers().stream().noneMatch(p -> p.getRemoteAddress().equals(e.getKey())));
            }).repeat(Duration.ofSeconds(1)).schedule();
            ready = true;
        } catch (Exception e) { logger.error("SSMCLogin unavailable: all new connections will be denied", e); }
    }
    private EventTask async(Runnable task) {
        return EventTask.withContinuation(continuation -> {
            try { workers.submit(() -> { try { task.run(); } finally { continuation.resume(); } }); }
            catch (RejectedExecutionException e) { continuation.resumeWithException(e); }
        });
    }
    @Subscribe(order = PostOrder.LAST) public EventTask prelogin(PreLoginEvent e) {
        if (!ready) { e.setResult(PreLoginEvent.PreLoginComponentResult.denied(Component.text("Autenticazione non disponibile"))); return null; }
        if (!e.getResult().isAllowed()) return null;
        return async(() -> {
            Session s = new Session();
            // Bedrock resolution happens after Floodgate has authenticated the login.
            s.lookup = auth.resolve(AuthService.javaIdentity(e.getUsername()));
            sessions.put(e.getConnection().getRemoteAddress(), s);
            Credential c = s.lookup.credential();
            boolean offline = e.getConnection().getVirtualHost().map(h -> h.getHostString().equalsIgnoreCase(offlineHost)).orElse(false);
            boolean premium = e.getConnection().getVirtualHost().map(h -> h.getHostString().equalsIgnoreCase(premiumHost)).orElse(false);
            if (premium || (!offline && c != null && c.autoLogin() && c.premiumUuid() != null))
                e.setResult(PreLoginEvent.PreLoginComponentResult.forceOnlineMode());
            else if (!e.getResult().isForceOfflineMode()) e.setResult(PreLoginEvent.PreLoginComponentResult.forceOfflineMode());
        });
    }
    @Subscribe(order = PostOrder.LAST) public void profile(GameProfileRequestEvent e) {
        Session s = sessions.get(e.getConnection().getRemoteAddress());
        if (s == null) return;
        if (e.isOnlineMode()) s.premiumUuid = e.getGameProfile().getId().toString();
        if (s.lookup == null || s.lookup.credential() == null) return;
        Credential c = s.lookup.credential();
        s.verifiedPremium = e.isOnlineMode() && c.proves(e.getGameProfile().getId().toString(), null);
        // Do not rewrite Floodgate's identity here. Its API is authoritative at LoginEvent.
        if (e.getGameProfile().getName().equalsIgnoreCase(c.name())) {
            e.setGameProfile(new GameProfile(UUID.fromString(c.serverUuid()), e.getGameProfile().getName(), e.getGameProfile().getProperties()));
        }
    }
    @Subscribe(order = PostOrder.LAST) public EventTask login(LoginEvent e) {
        if (!e.getResult().isAllowed()) return null;
        Player p = e.getPlayer();
        return async(() -> {
            Session s = sessions.get(p.getRemoteAddress());
            if (!ready || s == null) { e.setResult(ResultedEvent.ComponentResult.denied(Component.text("Autenticazione non disponibile"))); return; }
            if (server.getPluginManager().isLoaded("floodgate")) {
                var bedrock = FloodgateApi.getInstance().getPlayer(p.getUniqueId());
                if (bedrock != null) { s.xuid = bedrock.getXuid(); s.lookup = auth.resolve(AuthService.bedrockIdentity(s.xuid)); }
            }
            Credential c = s.lookup == null ? null : s.lookup.credential();
            if (c == null && (s.premiumUuid != null || s.xuid != null)) return; // Restricted enrollment lobby only.
            if (c == null || !c.serverUuid().equalsIgnoreCase(p.getUniqueId().toString())) {
                e.setResult(ResultedEvent.ComponentResult.denied(Component.text(c == null ? "Collega il tuo account su ssmc.it" : "UUID non allineato: contatta lo staff"))); return;
            }
            s.authenticated = s.verifiedPremium || c.proves(null, s.xuid);
        });
    }
    @Subscribe public void choose(PlayerChooseInitialServerEvent e) {
        Session s = sessions.get(e.getPlayer().getRemoteAddress());
        e.setInitialServer(server.getServer(s != null && s.authenticated ? lobbyServer : authServer).orElse(null));
    }
    @Subscribe public void connect(ServerPreConnectEvent e) {
        Session s = sessions.get(e.getPlayer().getRemoteAddress());
        if (s == null || (!s.authenticated && !e.getOriginalServer().getServerInfo().getName().equals(authServer)))
            e.setResult(ServerPreConnectEvent.ServerResult.denied());
    }
    @Subscribe public void connected(ServerConnectedEvent e) {
        Session s = sessions.get(e.getPlayer().getRemoteAddress());
        if (s != null && s.authenticated) server.getScheduler().buildTask(this, () -> {
            if (sessions.get(e.getPlayer().getRemoteAddress()) == s && s.authenticated)
                e.getPlayer().getCurrentServer().filter(c -> c.getServer().equals(e.getServer()))
                    .ifPresent(c -> c.sendPluginMessage(CHANNEL, ProxyProof.sign(e.getPlayer().getUniqueId(), proxyKey)));
        }).delay(Duration.ofSeconds(1)).schedule();
        else e.getPlayer().sendMessage(Component.text("Accedi con /login <password di gioco> — ssmc.it"));
    }
    @Subscribe public void command(CommandExecuteEvent e) {
        if (!(e.getCommandSource() instanceof Player p)) return;
        Session s = sessions.get(p.getRemoteAddress());
        if (s != null && s.authenticated) return;
        String cmd = e.getCommand().split(" ", 2)[0].toLowerCase(Locale.ROOT);
        if (!Set.of("login", "l", "ssmclink").contains(cmd)) e.setResult(CommandExecuteEvent.CommandResult.denied());
    }
    @Subscribe public void chat(PlayerChatEvent e) {
        Session s = sessions.get(e.getPlayer().getRemoteAddress());
        if (s == null || !s.authenticated) e.setResult(PlayerChatEvent.ChatResult.denied());
    }
    @Subscribe public void message(PluginMessageEvent e) {
        if (e.getIdentifier().equals(CHANNEL)) e.setResult(PluginMessageEvent.ForwardResult.handled());
    }
    @Subscribe public void disconnect(DisconnectEvent e) { sessions.remove(e.getPlayer().getRemoteAddress()); }
    @Subscribe public void shutdown(ProxyShutdownEvent e) {
        ready = false; workers.shutdownNow();
        for (Player p : server.getAllPlayers()) p.disconnect(Component.text("Autenticazione in riavvio"));
    }
    private final class LinkCommand implements SimpleCommand {
        @Override public void execute(Invocation invocation) {
            if (!(invocation.source() instanceof Player p)) return;
            Session s = sessions.get(p.getRemoteAddress());
            if (s == null || (s.premiumUuid == null && s.xuid == null)) { p.sendMessage(Component.text("Collegati dall’indirizzo premium della rete o tramite Floodgate")); return; }
            synchronized (s) {
                if (System.currentTimeMillis() - s.lastLink < 15_000) return;
                s.lastLink = System.currentTimeMillis();
            }
            try { workers.submit(() -> {
                try {
                    String identity = s.xuid == null ? AuthService.javaIdentity(p.getUsername()) : AuthService.bedrockIdentity(s.xuid);
                    String code = portal.issueLink(identity, p.getUsername(), p.getUniqueId().toString(), s.xuid == null ? s.premiumUuid : null, s.xuid);
                    if (sessions.get(p.getRemoteAddress()) == s && p.isActive()) p.sendMessage(Component.text("Codice personale: " + code + " — inseriscilo nelle impostazioni di ssmc.it entro 5 minuti. Poi riconnettiti."));
                } catch (Exception e) { p.sendMessage(Component.text("Collegamento non disponibile: riprova quando il portale è online")); }
            }); } catch (RejectedExecutionException e) { p.sendMessage(Component.text("Servizio occupato: riprova")); }
        }
        @Override public List<String> suggest(Invocation invocation) { return List.of(); }
    }
    private final class LoginCommand implements SimpleCommand {
        @Override public void execute(Invocation invocation) {
            if (!(invocation.source() instanceof Player p)) return;
            Session s = sessions.get(p.getRemoteAddress());
            if (s == null || s.authenticated) return;
            synchronized (s) { if (s.busy) return; s.busy = true; }
            String[] args = invocation.arguments();
            if (args.length != 1 || args[0].length() > 128) { s.busy = false; p.sendMessage(Component.text("Usa /login <password>")); return; }
            if (!attempts.allow(p.getRemoteAddress().getAddress().getHostAddress())) { s.busy = false; p.disconnect(Component.text("Troppi tentativi: riprova tra un minuto")); return; }
            char[] password = args[0].toCharArray();
            try { workers.submit(() -> {
                try {
                    var lookup = auth.resolve(s.xuid == null ? AuthService.javaIdentity(p.getUsername()) : AuthService.bedrockIdentity(s.xuid));
                    boolean ok = lookup.credential() != null && lookup.credential().serverUuid().equalsIgnoreCase(p.getUniqueId().toString()) && auth.password(lookup, password);
                    if (sessions.get(p.getRemoteAddress()) != s || !p.isActive()) return;
                    if (ok) {
                        s.lookup = lookup; s.authenticated = true;
                        p.getCurrentServer().ifPresent(connection -> connection.sendPluginMessage(CHANNEL, ProxyProof.sign(p.getUniqueId(), proxyKey)));
                        p.sendMessage(Component.text("Accesso effettuato" + (lookup.local() ? " (modalità locale)" : "")));
                        p.createConnectionRequest(server.getServer(lobbyServer).orElseThrow()).fireAndForget();
                    } else if (++s.failures >= 5) p.disconnect(Component.text("Troppi tentativi di accesso"));
                    else p.sendMessage(Component.text(lookup.error() == null ? "Credenziali non valide" : lookup.error()));
                } finally { Arrays.fill(password, '\0'); s.busy = false; }
            }); } catch (RejectedExecutionException ex) {
                Arrays.fill(password, '\0'); s.busy = false; p.disconnect(Component.text("Servizio occupato: riprova"));
            }
        }
        @Override public List<String> suggest(Invocation invocation) { return List.of(); }
    }
}
