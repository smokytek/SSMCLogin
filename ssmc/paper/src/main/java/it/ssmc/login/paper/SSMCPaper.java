package it.ssmc.login.paper;

import it.ssmc.login.core.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.block.*;
import org.bukkit.command.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.geysermc.floodgate.api.FloodgateApi;

public final class SSMCPaper extends JavaPlugin implements Listener, CommandExecutor, PluginMessageListener {
    private static final class Session {
        volatile boolean authenticated;
        volatile boolean busy;
        int failures;
        long lastLink;
        final long started = System.currentTimeMillis();
    }
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final AttemptLimit attempts = new AttemptLimit();
    private final Map<String, Long> usedProofs = new HashMap<>();
    private final ExecutorService workers = new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128), new ThreadPoolExecutor.AbortPolicy());
    private AuthService auth;
    private Portal portal;
    private boolean proxy;
    private String proxyKey;
    @Override public void onEnable() {
        saveDefaultConfig();
        try {
            String mode = getConfig().getString("mode", "standalone");
            if (!Set.of("standalone", "proxy-guard").contains(mode)) throw new IllegalArgumentException("Invalid mode");
            proxy = mode.equals("proxy-guard");
            if (proxy) {
                proxyKey = System.getenv("SSMCLOGIN_PROXY_KEY");
                if (proxyKey == null || proxyKey.length() < 32) throw new IllegalArgumentException("SSMCLOGIN_PROXY_KEY missing");
                getServer().getMessenger().registerIncomingPluginChannel(this, "ssmclogin:auth", this);
            } else {
                if (getServer().getOnlineMode()) throw new IllegalArgumentException("Standalone mixed access requires online-mode=false");
                portal = new Portal(URI.create(getConfig().getString("portal-url")), System.getenv("SSMCLOGIN_API_KEY"));
                auth = new AuthService(portal,
                        new LocalCache(getDataFolder().toPath().resolve("credentials.json")));
            }
        } catch (Exception e) {
            // Disabling an authentication plugin would leave an offline server open.
            getLogger().severe("SSMCLogin configuration invalid; stopping server: " + e.getMessage());
            getServer().shutdown(); return;
        }
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("login")).setExecutor(this);
        Objects.requireNonNull(getCommand("ssmclink")).setExecutor(this);
        getServer().getScheduler().runTaskTimer(this, () -> {
            long deadline = System.currentTimeMillis() - Math.max(15, getConfig().getInt("login-timeout-seconds", 90)) * 1000L;
            for (Player p : getServer().getOnlinePlayers()) {
                Session s = sessions.get(p.getUniqueId());
                if (s == null || (!s.authenticated && s.started < deadline)) p.kickPlayer("Tempo per il login scaduto");
            }
            usedProofs.values().removeIf(t -> t < System.currentTimeMillis() - 30_000);
        }, 20, 20);
        // Reloading the plugin must never inherit an unauthenticated player as logged in.
        for (Player p : getServer().getOnlinePlayers()) p.kickPlayer("SSMCLogin avviato: riconnettiti");
    }
    @Override public void onDisable() {
        workers.shutdownNow();
        for (Player p : getServer().getOnlinePlayers()) p.kickPlayer("Autenticazione non disponibile");
        sessions.clear();
    }
    private boolean locked(Player p) { Session s = sessions.get(p.getUniqueId()); return s == null || !s.authenticated; }
    @EventHandler public void join(PlayerJoinEvent e) {
        Player p = e.getPlayer(); Session s = new Session(); sessions.put(p.getUniqueId(), s);
        p.sendMessage("Accedi con /login <password di gioco>. Gestisci il tuo account su https://ssmc.it");
        if (!proxy) {
            String xuid = xuid(p);
            if (xuid != null) check(p, s, AuthService.bedrockIdentity(xuid), null, xuid);
        }
    }
    private String xuid(Player p) {
        if (!getServer().getPluginManager().isPluginEnabled("floodgate")) return null;
        var player = FloodgateApi.getInstance().getPlayer(p.getUniqueId());
        return player == null ? null : player.getXuid();
    }
    @EventHandler public void quit(PlayerQuitEvent e) { sessions.remove(e.getPlayer().getUniqueId()); }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) return true;
        Session s = sessions.get(p.getUniqueId());
        if (command.getName().equalsIgnoreCase("ssmclink")) {
            String xuid = xuid(p);
            if (proxy || s == null || xuid == null) { p.sendMessage("Collegamento disponibile con Floodgate; per Java premium usa Velocity"); return true; }
            if (System.currentTimeMillis() - s.lastLink < 15_000) return true;
            s.lastLink = System.currentTimeMillis();
            try { workers.submit(() -> {
                try {
                    String code = portal.issueLink(AuthService.bedrockIdentity(xuid), p.getName(), p.getUniqueId().toString(), null, xuid);
                    getServer().getScheduler().runTask(this, () -> {
                        if (sessions.get(p.getUniqueId()) == s) p.sendMessage("Codice personale: " + code + " — inseriscilo su ssmc.it entro 5 minuti, poi riconnettiti");
                    });
                } catch (Exception e) { getServer().getScheduler().runTask(this, () -> p.sendMessage("Portale non disponibile")); }
            }); } catch (RejectedExecutionException e) { p.sendMessage("Servizio occupato: riprova"); }
            return true;
        }
        if (s == null || s.authenticated || s.busy) return true;
        if (proxy) { p.sendMessage("Login disponibile tramite il proxy della rete"); return true; }
        if (args.length != 1 || args[0].length() > 128) { p.sendMessage("Usa /login <password>"); return true; }
        if (p.getAddress() == null || !attempts.allow(p.getAddress().getAddress().getHostAddress())) { p.kickPlayer("Troppi tentativi: riprova tra un minuto"); return true; }
        String xuid = xuid(p);
        check(p, s, xuid == null ? AuthService.javaIdentity(p.getName()) : AuthService.bedrockIdentity(xuid), args[0].toCharArray(), xuid);
        return true;
    }
    private void check(Player p, Session s, String identity, char[] password, String xuid) {
        s.busy = true;
        try { workers.submit(() -> {
            var lookup = auth.resolve(identity);
            Credential c = lookup.credential();
            boolean result = c != null && c.serverUuid().equalsIgnoreCase(p.getUniqueId().toString())
                    && ((password == null && c.proves(null, xuid)) || (password != null && auth.password(lookup, password)));
            if (password != null) Arrays.fill(password, '\0');
            getServer().getScheduler().runTask(this, () -> {
                if (sessions.get(p.getUniqueId()) != s || !p.isOnline()) return;
                s.busy = false;
                if (result) { s.authenticated = true; p.sendMessage("Accesso effettuato" + (lookup.local() ? " (modalità locale)" : "")); }
                else if (password != null) {
                    if (++s.failures >= 5) p.kickPlayer("Troppi tentativi di accesso");
                    else p.sendMessage(lookup.error() == null ? "Accesso non riuscito: verifica password e collegamento UUID" : lookup.error());
                }
            });
        }); } catch (RejectedExecutionException e) {
            s.busy = false; if (password != null) Arrays.fill(password, '\0'); p.kickPlayer("Servizio occupato: riprova");
        }
    }
    @Override public void onPluginMessageReceived(String channel, Player p, byte[] message) {
        if (!proxy) return;
        String nonce = ProxyProof.validate(message, p.getUniqueId(), proxyKey);
        Session s = sessions.get(p.getUniqueId());
        if (nonce != null && s != null && usedProofs.putIfAbsent(nonce, System.currentTimeMillis()) == null) s.authenticated = true;
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void move(PlayerMoveEvent e) { if (locked(e.getPlayer()) && e.hasChangedPosition()) e.setTo(e.getFrom()); }
    @EventHandler(priority = EventPriority.HIGHEST) public void command(PlayerCommandPreprocessEvent e) {
        if (!locked(e.getPlayer())) return;
        String cmd = e.getMessage().split(" ", 2)[0].toLowerCase(Locale.ROOT);
        if (!Set.of("/login", "/l", "/ssmclogin:login", "/ssmclogin:l", "/ssmclink", "/ssmclogin:ssmclink").contains(cmd)) e.setCancelled(true);
    }
    @EventHandler public void chat(AsyncPlayerChatEvent e) { if (locked(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void interact(PlayerInteractEvent e) { if (locked(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void entity(PlayerInteractEntityEvent e) { if (locked(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void damage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && locked(p)) e.setCancelled(true);
        if (e instanceof EntityDamageByEntityEvent d) {
            if (d.getDamager() instanceof Player p && locked(p)) e.setCancelled(true);
            if (d.getDamager() instanceof org.bukkit.entity.Projectile shot && shot.getShooter() instanceof Player p && locked(p)) e.setCancelled(true);
        }
    }
    @EventHandler public void inventory(InventoryClickEvent e) { if (e.getWhoClicked() instanceof Player p && locked(p)) e.setCancelled(true); }
    @EventHandler public void drag(InventoryDragEvent e) { if (e.getWhoClicked() instanceof Player p && locked(p)) e.setCancelled(true); }
    @EventHandler public void drop(PlayerDropItemEvent e) { if (locked(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void pickup(EntityPickupItemEvent e) { if (e.getEntity() instanceof Player p && locked(p)) e.setCancelled(true); }
    @EventHandler public void block(BlockBreakEvent e) { if (locked(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void place(BlockPlaceEvent e) { if (locked(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void swap(PlayerSwapHandItemsEvent e) { if (locked(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void food(FoodLevelChangeEvent e) { if (e.getEntity() instanceof Player p && locked(p)) e.setCancelled(true); }
    @EventHandler public void teleport(PlayerTeleportEvent e) { if (locked(e.getPlayer())) e.setCancelled(true); }
}
