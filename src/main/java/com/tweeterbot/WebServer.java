package com.tweeterbot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class WebServer {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Map<String, CacheEntry> CACHE = new ConcurrentHashMap<>();

  public static void start() {
    int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));

    try {
      HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
      server.createContext("/", new StaticHandler("/public/index.html", "text/html; charset=utf-8"));
      server.createContext("/app.css", new StaticHandler("/public/app.css", "text/css; charset=utf-8"));
      server.createContext("/app.js", new StaticHandler("/public/app.js", "application/javascript; charset=utf-8"));
      server.createContext("/api/generate", new GenerateHandler(false));
      server.createContext("/api/generate-sse", new GenerateHandler(true));
      server.setExecutor(null);
      server.start();

      System.out.println("TweeterChaatBot web server running on http://localhost:" + port);
    } catch (IOException e) {
      System.err.println("Failed to start server: " + e.getMessage());
      System.exit(1);
    }
  }

  private static class StaticHandler implements HttpHandler {
    private final String resourcePath;
    private final String contentType;

    StaticHandler(String resourcePath, String contentType) {
      this.resourcePath = resourcePath;
      this.contentType = contentType;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
        send(exchange, 405, "Method Not Allowed");
        return;
      }

      try (InputStream in = WebServer.class.getResourceAsStream(resourcePath)) {
        if (in == null) {
          send(exchange, 404, "Not Found");
          return;
        }

        byte[] body = readAllBytes(in);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType);
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
          os.write(body);
        }
      }
    }
  }

  private static class GenerateHandler implements HttpHandler {
    private final boolean sse;

    GenerateHandler(boolean sse) {
      this.sse = sse;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (sse && !"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
        send(exchange, 405, "Method Not Allowed");
        return;
      }
      if (!sse && !"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
        send(exchange, 405, "Method Not Allowed");
        return;
      }

      String topic = sse ? getQueryParam(exchange, "topic") : readTopicFromBody(exchange);
      if (topic == null || topic.isBlank()) {
        sendJson(exchange, 400, Map.of("error", "Topic is required"));
        return;
      }

      if (sse) {
        handleSse(exchange, topic);
        return;
      }

      String xToken = System.getenv("X_BEARER_TOKEN");
      String openAiKey = System.getenv("OPENAI_API_KEY");
      if (xToken == null || xToken.isBlank()) {
        sendJson(exchange, 400, Map.of("error", "Missing X_BEARER_TOKEN"));
        return;
      }
      if (openAiKey == null || openAiKey.isBlank()) {
        sendJson(exchange, 400, Map.of("error", "Missing OPENAI_API_KEY"));
        return;
      }

      Map<String, Object> out = generateNonStreaming(topic);
      int status = out.containsKey("error") ? 500 : 200;
      sendJson(exchange, status, out);
    }

    private void handleSse(HttpExchange exchange, String topic) throws IOException {
      Headers headers = exchange.getResponseHeaders();
      headers.set("Content-Type", "text/event-stream; charset=utf-8");
      headers.set("Cache-Control", "no-cache");
      headers.set("Connection", "keep-alive");
      exchange.sendResponseHeaders(200, 0);

      try (OutputStream os = exchange.getResponseBody()) {
        String xToken = System.getenv("X_BEARER_TOKEN");
        String openAiKey = System.getenv("OPENAI_API_KEY");
        if (xToken == null || xToken.isBlank()) {
          sendEvent(os, "error", "Missing X_BEARER_TOKEN");
          return;
        }
        if (openAiKey == null || openAiKey.isBlank()) {
          sendEvent(os, "error", "Missing OPENAI_API_KEY");
          return;
        }

        String normalized = normalizeTopic(topic);
        CacheEntry cached = CACHE.get(normalized);
        if (cached != null && !cached.isExpired()) {
          sendEvent(os, "status", "Loaded from cache.");
          Map<String, Object> out = new LinkedHashMap<>(cached.payload);
          out.put("cache", true);
          sendEvent(os, "result", MAPPER.writeValueAsString(out));
          return;
        }

        sendEvent(os, "status", "Fetching recent tweets...");

        String xBase = System.getenv().getOrDefault("X_API_BASE_URL", "https://api.x.com/2");
        String oaBase = System.getenv().getOrDefault("OPENAI_API_BASE_URL", "https://api.openai.com/v1");
        String model = System.getenv().getOrDefault("OPENAI_MODEL", "gpt-4o-mini");
        String moderationModel = System.getenv().getOrDefault("OPENAI_MODERATION_MODEL", "omni-moderation-latest");

        XClient xClient = new XClient(System.getenv("X_BEARER_TOKEN"), xBase);
        List<Tweet> tweets = xClient.fetchRecentUniqueAuthors(topic, 50);

        sendEvent(os, "status", "Summarizing with OpenAI...");
        OpenAIClient ai = new OpenAIClient(System.getenv("OPENAI_API_KEY"), oaBase, model);
        OpenAIClient.SummaryPayload summary = ai.summarize(topic, tweets);

        sendEvent(os, "status", "Running moderation...");
        OpenAIModerationClient moderation = new OpenAIModerationClient(System.getenv("OPENAI_API_KEY"), oaBase, moderationModel);
        OpenAIModerationClient.ModerationResult mod = moderation.moderate(summary.suggestedPost);
        if (mod.flagged()) {
          summary.suggestedPost = "Suggested post withheld due to safety policies.";
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("topic", topic);
        out.put("generated_at", Instant.now().toString());
        out.put("model", model);
        out.put("tweets", tweets);
        out.put("summary", summary);
        out.put("moderation", mod);
        out.put("cache", false);

        CACHE.put(normalized, new CacheEntry(out, ttlSeconds()));
        sendEvent(os, "result", MAPPER.writeValueAsString(out));
      } catch (Exception e) {
        sendEvent(exchange.getResponseBody(), "error", e.getMessage());
      }
    }
  }

  private static Map<String, Object> generateNonStreaming(String topic) {
    String normalized = normalizeTopic(topic);
    CacheEntry cached = CACHE.get(normalized);
    if (cached != null && !cached.isExpired()) {
      Map<String, Object> out = new LinkedHashMap<>(cached.payload);
      out.put("cache", true);
      return out;
    }

    String xBase = System.getenv().getOrDefault("X_API_BASE_URL", "https://api.x.com/2");
    String oaBase = System.getenv().getOrDefault("OPENAI_API_BASE_URL", "https://api.openai.com/v1");
    String model = System.getenv().getOrDefault("OPENAI_MODEL", "gpt-4o-mini");
    String moderationModel = System.getenv().getOrDefault("OPENAI_MODERATION_MODEL", "omni-moderation-latest");

    try {
      XClient xClient = new XClient(System.getenv("X_BEARER_TOKEN"), xBase);
      List<Tweet> tweets = xClient.fetchRecentUniqueAuthors(topic, 50);

      OpenAIClient ai = new OpenAIClient(System.getenv("OPENAI_API_KEY"), oaBase, model);
      OpenAIClient.SummaryPayload summary = ai.summarize(topic, tweets);

      OpenAIModerationClient moderation = new OpenAIModerationClient(System.getenv("OPENAI_API_KEY"), oaBase, moderationModel);
      OpenAIModerationClient.ModerationResult mod = moderation.moderate(summary.suggestedPost);

      if (mod.flagged()) {
        summary.suggestedPost = "Suggested post withheld due to safety policies.";
      }

      Map<String, Object> out = new LinkedHashMap<>();
      out.put("topic", topic);
      out.put("generated_at", Instant.now().toString());
      out.put("model", model);
      out.put("tweets", tweets);
      out.put("summary", summary);
      out.put("moderation", mod);
      out.put("cache", false);

      CACHE.put(normalized, new CacheEntry(out, ttlSeconds()));
      return out;
    } catch (Exception e) {
      return Map.of("error", e.getMessage());
    }
  }

  private static int ttlSeconds() {
    String raw = System.getenv().getOrDefault("CACHE_TTL_SECONDS", "600");
    try {
      return Integer.parseInt(raw);
    } catch (NumberFormatException e) {
      return 600;
    }
  }

  private static String normalizeTopic(String topic) {
    return topic.trim().toLowerCase();
  }

  private static String readTopicFromBody(HttpExchange exchange) throws IOException {
    String body = new String(readAllBytes(exchange.getRequestBody()), StandardCharsets.UTF_8);
    Map<String, Object> payload = MAPPER.readValue(body, new TypeReference<>() {});
    return String.valueOf(payload.getOrDefault("topic", "")).trim();
  }

  private static String getQueryParam(HttpExchange exchange, String key) {
    String query = exchange.getRequestURI().getQuery();
    if (query == null || query.isBlank()) return "";

    String[] pairs = query.split("&");
    for (String pair : pairs) {
      String[] kv = pair.split("=", 2);
      if (kv.length == 2 && kv[0].equals(key)) {
        return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
      }
    }
    return "";
  }

  private static void sendEvent(OutputStream os, String event, String data) throws IOException {
    String payload = "event: " + event + "\n" + "data: " + data.replace("\n", " ") + "\n\n";
    os.write(payload.getBytes(StandardCharsets.UTF_8));
    os.flush();
  }

  private static byte[] readAllBytes(InputStream in) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    byte[] data = new byte[4096];
    int n;
    while ((n = in.read(data, 0, data.length)) != -1) {
      buffer.write(data, 0, n);
    }
    return buffer.toByteArray();
  }

  private static void send(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(bytes);
    }
  }

  private static void sendJson(HttpExchange exchange, int status, Object payload) throws IOException {
    byte[] bytes = MAPPER.writeValueAsBytes(payload);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(bytes);
    }
  }

  private static class CacheEntry {
    final Map<String, Object> payload;
    final long expiresAtEpochSeconds;

    CacheEntry(Map<String, Object> payload, int ttlSeconds) {
      this.payload = payload;
      this.expiresAtEpochSeconds = Instant.now().getEpochSecond() + Math.max(1, ttlSeconds);
    }

    boolean isExpired() {
      return Instant.now().getEpochSecond() > expiresAtEpochSeconds;
    }
  }
}
