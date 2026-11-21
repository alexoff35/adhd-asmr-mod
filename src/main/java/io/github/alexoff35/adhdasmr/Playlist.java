package io.github.alexoff35.adhdasmr;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

final class Playlist {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern ID = Pattern.compile("[a-zA-Z0-9_-]{11}");
    private static final URI CATALOG = URI.create(
            "https://raw.githubusercontent.com/alexoff35/adhd-asmr-mod/main/videos.json");
    private static final int LIMIT = 512;
    private final Path config = FabricLoader.getInstance().getConfigDir().resolve("adhd-asmr.json");
    private final List<String> bundled;
    private Settings settings;
    private List<String> order = new ArrayList<>();
    private String current;

    Playlist() {
        try (var fallback = new InputStreamReader(
                Playlist.class.getResourceAsStream("/assets/adhd_asmr/videos.json"), StandardCharsets.UTF_8)) {
            bundled = normalize(JSON.fromJson(fallback, String[].class));
        } catch (IOException e) {
            throw new IllegalStateException("Could not read the bundled playlist", e);
        }
        Path inputConfig = Files.exists(config) ? config : config.resolveSibling("asmr-corner.json");
        try (var reader = Files.newBufferedReader(inputConfig)) {
            settings = JSON.fromJson(reader, Settings.class);
        } catch (IOException | RuntimeException e) {
            if (Files.exists(config)) AdhdAsmr.LOG.warn("Could not read ADHD ASMR configuration", e);
        }
        if (settings == null) settings = new Settings();
        settings.remote = normalize(settings.remote);
        settings.local = normalize(settings.local); settings.order = normalize(settings.order);
        rebuild();
    }

    int scale() { return Math.clamp(settings.scale, 15, 55); }
    int corner() { return Math.floorMod(settings.corner, 4); }
    void setScale(int value) { settings.scale = value; }
    void nextCorner() { settings.corner = (corner() + 1) % 4; save(); }
    void saveSettings() { save(); }
    void resetSettings() { settings.scale = 24; settings.corner = 1; settings.enabled = true; save(); }

    boolean enabled() { return settings.enabled; }
    void setEnabled(boolean enabled) {
        settings.enabled = enabled;
        save();
    }

    List<String> videos() { return List.copyOf(order); }
    String selected() { return current; }
    void select(String id) {
        if (order.contains(id)) current = id;
    }

    void step(int delta) {
        if (!order.isEmpty()) current = order.get(Math.floorMod(order.indexOf(current) + delta, order.size()));
    }

    void shuffle() {
        Collections.shuffle(order); persistOrder(); save();
    }

    boolean add(String input) {
        String id = videoId(input);
        if (id == null || order.contains(id) || settings.local.size() >= LIMIT) return false;
        settings.local.add(id);
        order.add(id); persistOrder();
        save();
        return true;
    }

    void remove(String id) {
        if (!order.contains(id)) return;
        if (!settings.hasRemote) { settings.remote = new ArrayList<>(bundled); settings.hasRemote = true; }
        settings.remote.remove(id);
        settings.local.remove(id);
        order.remove(id);
        persistOrder();
        rebuild();
        save();
    }

    void clear() {
        settings.hasRemote = true;
        settings.remote.clear();
        settings.local.clear();
        settings.order.clear();
        rebuild();
        save();
    }

    private void persistOrder() { settings.order = new ArrayList<>(order); }

    void replaceRemote(List<String> ids) {
        settings.remote = ids; settings.order.clear();
        settings.hasRemote = true;
        rebuild();
        save();
    }

    CompletableFuture<List<String>> download() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                var request = HttpRequest.newBuilder(CATALOG)
                        .timeout(Duration.ofSeconds(18))
                        .GET()
                        .build();
                try (var http = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(8))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build()) {
                    var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
                    try (InputStream body = response.body()) {
                        if (response.statusCode() != 200) {
                            throw new IllegalStateException("GitHub HTTP " + response.statusCode());
                        }
                        byte[] data = body.readNBytes(131073);
                        if (data.length > 131072) throw new IllegalStateException("Could not load GitHub queue");
                        var parsed = JSON.fromJson(new String(data, StandardCharsets.UTF_8), String[].class);
                        if (parsed == null || parsed.length > LIMIT) throw new IllegalStateException("Could not load GitHub queue");
                        List<String> ids = normalize(parsed);
                        if (parsed.length > 0 && ids.isEmpty()) throw new IllegalStateException("Could not load GitHub queue");
                        return ids;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Could not load GitHub queue");
            } catch (IOException | IllegalArgumentException | com.google.gson.JsonParseException e) {
                throw new IllegalStateException("Could not load GitHub queue");
            }
        });
    }

    static String url(String id) { return "https://www.youtube.com/watch?v=" + id; }

    static String videoId(String raw) {
        if (raw == null) return null;
        String value = raw.strip();
        if (ID.matcher(value).matches()) return value;
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) return null;
            String host = uri.getHost();
            if (host == null) return null;
            host = host.toLowerCase(java.util.Locale.ROOT);
            String path = uri.getPath();
            String candidate = "";
            if (host.equals("youtu.be")) {
                candidate = path.substring(1).split("/", 2)[0];
            } else if (host.equals("youtube.com") || host.equals("www.youtube.com") || host.equals("m.youtube.com")
                    || host.equals("www.youtube-nocookie.com")) {
                if (path.equals("/watch") && uri.getRawQuery() != null) {
                    for (String field : uri.getRawQuery().split("&")) {
                        if (field.startsWith("v=")) {
                            candidate = field.substring(2);
                            break;
                        }
                    }
                } else if (path.matches("/(shorts|embed|live)/[^/]+/?")) {
                    candidate = path.split("/")[2];
                }
            }
            return ID.matcher(candidate).matches() ? candidate : null;
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            return null;
        }
    }

    private static List<String> normalize(String[] values) {
        var ids = new LinkedHashSet<String>();
        if (values != null) for (String value : values) {
            String id = videoId(value);
            if (id != null) ids.add(id);
            if (ids.size() >= LIMIT) break;
        }
        return new ArrayList<>(ids);
    }

    private static List<String> normalize(List<String> values) {
        return normalize(values == null ? null : values.toArray(String[]::new));
    }

    private void rebuild() {
        var ids = new LinkedHashSet<>(settings.hasRemote ? settings.remote : bundled);
        ids.addAll(settings.local);
        var sorted = new LinkedHashSet<String>();
        for (String id : settings.order) if (ids.contains(id)) sorted.add(id);
        sorted.addAll(ids);
        order = new ArrayList<>(sorted);
        if (!ids.contains(current)) current = order.isEmpty() ? null : order.getFirst();
    }

    private void save() {
        try {
            Files.createDirectories(config.getParent());
            Path temp = config.resolveSibling("adhd-asmr.json.tmp");
            Files.writeString(temp, JSON.toJson(settings));
            try {
                Files.move(temp, config, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, config, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            AdhdAsmr.LOG.warn("Could not save ADHD ASMR configuration", e);
        }
    }

    private static final class Settings {
        boolean enabled = true;
        int scale = 24;
        int corner = 1;
        List<String> order = new ArrayList<>();
        boolean hasRemote;
        List<String> remote = new ArrayList<>();
        List<String> local = new ArrayList<>();
    }
}
