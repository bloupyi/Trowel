package com.stackmc.trowel;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * A small web server that hands out exported schematics: each file gets a secret link that
 * works for {@value #LIFETIME_MINUTES} minutes.
 */
public final class SchematicDownloads {

    public static final int LIFETIME_MINUTES = 10;

    private record Offer(String file, byte[] bytes, long expires) {
    }

    private final JavaPlugin plugin;
    private final Map<String, Offer> offers = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private HttpServer server;
    private String base;

    public SchematicDownloads(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void start(TrowelSettings settings) {
        stop();
        int port = settings.downloadPort();
        if (port <= 0) {
            return;
        }
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Schematic downloads off: port " + port + " unavailable", e);
            return;
        }
        server.createContext("/", this::serve);
        server.start();
        String url = settings.downloadUrl().strip();
        String ip = Bukkit.getIp();
        base = !url.isEmpty() ? url.replaceAll("/+$", "")
                : "http://" + (ip.isBlank() ? "localhost" : ip) + ":" + port;
        plugin.getLogger().info("Schematic downloads on " + base);
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        offers.clear();
    }

    public boolean running() {
        return server != null;
    }

    /** @return the link to this file */
    public String offer(String file, byte[] bytes) {
        long now = System.currentTimeMillis();
        offers.values().removeIf(offer -> offer.expires() < now);
        byte[] token = new byte[16];
        random.nextBytes(token);
        String key = HexFormat.of().formatHex(token);
        offers.put(key, new Offer(file, bytes, now + LIFETIME_MINUTES * 60_000L));
        return base + "/" + key + "/" + file;
    }

    private void serve(HttpExchange exchange) throws IOException {
        try (exchange) {
            String[] parts = exchange.getRequestURI().getPath().split("/");
            Offer offer = parts.length > 1 ? offers.get(parts[1]) : null;
            if (offer == null || offer.expires() < System.currentTimeMillis()
                    || !exchange.getRequestMethod().equals("GET")) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + offer.file() + "\"");
            exchange.sendResponseHeaders(200, offer.bytes().length);
            exchange.getResponseBody().write(offer.bytes());
        }
    }
}
