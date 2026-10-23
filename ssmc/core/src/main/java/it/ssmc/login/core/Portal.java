package it.ssmc.login.core;

import com.google.gson.Gson;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;

public final class Portal {
    public enum State { FOUND, DENIED, UNAVAILABLE, CONFIG_ERROR }
    public record Reply(State state, Credential credential) {}
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final URI endpoint;
    private final String key;
    private final Gson gson = new Gson();
    public Portal(URI endpoint, String key) {
        if (!"https".equals(endpoint.getScheme())) throw new IllegalArgumentException("Portal URL must use HTTPS");
        if (key == null || key.length() < 32) throw new IllegalArgumentException("API key must have at least 32 characters");
        this.endpoint = endpoint; this.key = key;
    }
    public Reply resolve(String identity) {
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(java.util.Map.of("identity", identity)))).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == 404 || status == 403) return new Reply(State.DENIED, null);
            if (status >= 500) return new Reply(State.UNAVAILABLE, null);
            if (status != 200 || response.body().length() > 16_384) return new Reply(State.CONFIG_ERROR, null);
            Credential credential = gson.fromJson(response.body(), Credential.class);
            if (credential == null || !identity.equals(credential.identity()) || credential.accountId() == null
                    || credential.serverUuid() == null || credential.passwordVerifier() == null) return new Reply(State.CONFIG_ERROR, null);
            java.util.UUID.fromString(credential.serverUuid());
            return new Reply(State.FOUND, credential);
        } catch (IOException e) {
            return new Reply(State.UNAVAILABLE, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); return new Reply(State.CONFIG_ERROR, null);
        } catch (RuntimeException e) {
            return new Reply(State.CONFIG_ERROR, null);
        }
    }
    public String issueLink(String identity, String name, String serverUuid, String premiumUuid, String xuid) throws IOException, InterruptedException {
        var data = new java.util.HashMap<String, String>();
        data.put("identity", identity); data.put("name", name); data.put("serverUuid", serverUuid);
        if (premiumUuid != null) data.put("premiumUuid", premiumUuid);
        if (xuid != null) data.put("xuid", xuid);
        URI link = endpoint.resolve("/api/minecraft/link");
        var request = HttpRequest.newBuilder(link).timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(data))).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200 || response.body().length() > 1024) throw new IOException("Link not available");
        String code = gson.fromJson(response.body(), com.google.gson.JsonObject.class).get("code").getAsString();
        if (!code.matches("[a-f0-9]{24}")) throw new IOException("Invalid link response");
        return code;
    }
}
