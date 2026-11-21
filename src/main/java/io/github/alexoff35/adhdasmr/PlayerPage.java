package io.github.alexoff35.adhdasmr;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;

final class PlayerPage implements AutoCloseable {
    private static final Gson JSON = new Gson();
    private final HttpServer server;
    private final String path = "/" + UUID.randomUUID() + "/";
    private final ArrayDeque<Command> commands = new ArrayDeque<>();
    private volatile byte[] state;
    private long sequence;

    PlayerPage() throws IOException {
        byte[] html;
        try (var resource = PlayerPage.class.getResourceAsStream("/assets/adhd_asmr/player.html")) {
            html = resource.readAllBytes();
        }
        update(null, false);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 8);
        server.createContext(path, exchange -> serve(exchange, html));
        server.start();
    }

    String url() { return "http://127.0.0.1:" + server.getAddress().getPort() + path; }

    void command(String action, int value) {
        commands.addLast(new Command(++sequence, action, value));
        if (commands.size() > 64) commands.removeFirst();
    }

    void update(String id, boolean visible) {
        state = JSON.toJson(new State(id == null ? "" : id, visible, List.copyOf(commands)))
                .getBytes(StandardCharsets.UTF_8);
    }

    private void serve(HttpExchange exchange, byte[] html) throws IOException {
        try (exchange) {
            String requestPath = exchange.getRequestURI().getPath();
            if (!"GET".equals(exchange.getRequestMethod())
                    || !("127.0.0.1:" + server.getAddress().getPort()).equals(exchange.getRequestHeaders().getFirst("Host"))) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            byte[] body;
            String type;
            if (path.equals(requestPath)) {
                body = html;
                type = "text/html; charset=utf-8";
            } else if ((path + "state").equals(requestPath)) {
                body = state;
                type = "application/json; charset=utf-8";
            } else {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            var headers = exchange.getResponseHeaders();
            headers.set("Content-Type", type);
            headers.set("Cache-Control", "no-store");
            headers.set("Referrer-Policy", "strict-origin-when-cross-origin");
            headers.set("X-Content-Type-Options", "nosniff");
            headers.set("Content-Security-Policy", "default-src 'none'; connect-src 'self'; "
                    + "script-src 'unsafe-inline' https://www.youtube.com https://s.ytimg.com; "
                    + "style-src 'unsafe-inline'; frame-src https://www.youtube.com; frame-ancestors 'none'; base-uri 'none'");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        }
    }

    @Override public void close() { server.stop(0); }

    private record State(String id, boolean visible, List<Command> commands) {}
    private record Command(long sequence, String action, int value) {}
}
