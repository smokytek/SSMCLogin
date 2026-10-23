# SSMCLogin — development preview

Fork of [OpeNLogin](https://github.com/nickuc-com/OpeNLogin), retaining its MIT license and original sources. New modules live in `ssmc/`. The upstream build remains separate so existing Java 8 code is not silently upgraded.

**Not a production release.** Compilation and automated tests do not establish runtime compatibility with all Minecraft versions. No production database has been modified.

## Implemented

- Shared portal client with HTTPS and a dedicated service key.
- Separate game password, configured in the website settings and stored as PBKDF2-HMAC-SHA256 (600,000 rounds, random 16-byte salt).
- Persistent local credential cache, atomically replaced; previously synchronized accounts can authenticate when the portal is unreachable or returns HTTP 5xx.
- HTTP 401, 429, malformed responses and configuration failures deny authentication. HTTP 403/404 evict the identity from the local cache. A rejected account cannot use the cached password to bypass that rejection.
- Paper standalone password authentication and authenticated Floodgate XUID checks.
- Velocity session verification for linked Java premium accounts, and Floodgate XUID checks. Unauthenticated players are restricted to an authentication backend.
- Game-generated, expiring, single-use link codes redeemable in the portal by the signed-in owner. Public Mojang/Xbox nickname lookups are not accepted as proof of ownership.
- Paper proxy guard: all backend players start locked and require a short-lived, HMAC-signed authorization message from Velocity. Invalid/missing configuration stops standalone Paper rather than opening an offline server.
- Per-session and per-IP password-attempt limits and bounded password verification worker queues.

## Not complete / required before production

1. **nLogin 2.0.24 Premium MariaDB migration:** awaiting table schema and UUID/hash settings. No production import or password-hash conversion exists yet. Never assume the portal UUID, premium UUID and currently used server UUID are the same. Import and reserve all existing identities BEFORE enabling new account provisioning on the website.
2. **Paper standalone Java premium handshake:** not implemented. Mixed-mode Paper cannot infer a verified premium session from a nickname or an offline UUID. Java users currently use the game password in standalone mode. Automatic Java premium authentication is implemented only in the Velocity adapter.
3. Runtime tests on Paper 1.21.x and 26.3 and the corresponding Velocity/Geyser/Floodgate versions. The Java output targets Java 21; build tools use Java 25. Paper 26.x needs Java 25.
4. Minecraft 26.2+ client/session behavior when a verified premium UUID is rewritten to a legacy server UUID requires explicit integration testing. Do not claim legacy UUID preservation works across those versions solely because the code compiles.
5. Authentication-isolation tests with the installed backend plugins, including join-time rewards, inventories, commands, vehicle movement and other plugin side effects. No real client or world test has been run.

## Build and test

From the repository root:

```text
./gradlew -p ssmc build
```

Outputs: `ssmc/paper/build/libs/SSMCLogin-Paper-0.1.0-dev.jar` and `ssmc/velocity/build/libs/SSMCLogin-Velocity-0.1.0-dev.jar`.

Do not install upstream OpeNLogin/nLogin alongside SSMCLogin in the same authentication role; plan a staging migration first.

## Website

Use the companion changes in `smokytek/ssmc-website` and its `docs/ssmclogin.md`. Apply `migrations/ssmclogin.sql` only to a backed-up staging database initially. Keep `SSMCLOGIN_ENABLED` unset until the legacy identity mapping is ready.

Environment variables:

```text
SSMCLOGIN_ENABLED=true
SSMCLOGIN_API_KEY=<random secret of at least 32 characters>
```

Use the same API key in the standalone Paper server or Velocity process. Keep it out of source control and never expose `/api/minecraft/resolve` to browsers with that key: it returns game password verifiers to trusted game servers.

## Paper standalone preview

`server.properties`: `online-mode=false`. Install Floodgate for authenticated Bedrock identity checks. Set `mode: standalone` in `plugins/SSMCLogin/config.yml` and provide `SSMCLOGIN_API_KEY` in the server process environment. A player's actual Paper UUID must equal the approved `game_bindings.server_uuid`; mismatches fail closed.

Commands: `/login <game-password>`, `/ssmclink` for Floodgate users. Java premium automatic login is not available in this standalone preview.

## Velocity preview

- Configure Velocity mixed access with `online-mode=false`, modern forwarding, forwarding secret and backend firewall rules. Backend ports must be reachable only from the proxy.
- Register a dedicated `auth` backend and a `lobby` backend. Install the Paper guard on **every** backend, including `auth`, with `mode: proxy-guard`.
- Provide the same random `SSMCLOGIN_PROXY_KEY` (at least 32 characters) to Velocity and all Paper guards. Only Velocity needs `SSMCLOGIN_API_KEY`.
- Configure `plugins/ssmclogin/ssmclogin.properties` after first startup. `auth-server` and `lobby-server` must refer to registered backend names. Use an isolated authentication world without gameplay or join-time rewards.
- Normal entry automatically requests Mojang session verification only for premium identities linked with a verified session.
- `premium-host=premium.ssmc.it` always requests a premium handshake, allowing first-time premium linking. `/ssmclink` creates a private code; redeem it in the portal settings, then reconnect.
- `offline-host=offline.ssmc.it` requests offline authentication so the player can enter with the game password even when Mojang is unavailable. Both hostnames must point to the same proxy. These DNS entries have not been created.
- Install Geyser/Floodgate on the proxy; the player XUID must come from Floodgate's authenticated API. Linked Bedrock accounts auto-login without a Java premium handshake.
- Unknown but session-verified Java/Bedrock players may reach only the locked auth backend to link the account; they cannot enter gameplay until provisioned on the portal.

When Mojang rejects or cannot verify a forced online session, the proxy cannot safely convert the same in-progress handshake to offline mode. Reconnect using the offline hostname and use the game password.

## Offline behavior and storage

The last portal-accepted credential wins. Password changes, bans and account unlinking made during an outage become visible at the next successful portal lookup. Offline access lasts for the outage; no expiry has been introduced beyond the user's requested policy. Protect the credential cache with OS permissions and encrypted backups. On Windows set an ACL on the plugin directory; on POSIX files are created with mode 0600.

Restarting the authentication service disconnects active players. This preview intentionally does not restore authenticated sessions from disk.

## Migration evidence to collect

In phpMyAdmin/Adminer run `SHOW TABLES;`, then `SHOW CREATE TABLE` for the nLogin account table. Supply the schema and sanitized UUID/hash-related configuration only, not production rows, passwords, database credentials or secret keys. An importer must first produce a dry-run conflict report, then preserve existing UUIDs and make no writes to the source MariaDB database. Password compatibility cannot be promised before seeing the algorithm configuration.
