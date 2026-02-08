package com.tweeterbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;

public class OpenAIModerationClient {
  private final String apiKey;
  private final String baseUrl;
  private final String model;
  private final HttpClient http;
  private final ObjectMapper mapper;

  public OpenAIModerationClient(String apiKey, String baseUrl, String model) {
    this.apiKey = apiKey;
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.model = model;
    this.http = HttpClient.newHttpClient();
    this.mapper = new ObjectMapper();
  }

  public ModerationResult moderate(String text) throws IOException, InterruptedException {
    ObjectNode root = mapper.createObjectNode();
    root.put("model", model);
    root.put("input", text);

    HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/moderations"))
        .header("Authorization", "Bearer " + apiKey)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(root)))
        .build();

    HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() != 200) {
      throw new IOException("OpenAI Moderation API error: HTTP " + resp.statusCode() + " -> " + resp.body());
    }

    JsonNode rootNode = mapper.readTree(resp.body());
    JsonNode results = rootNode.path("results");
    if (!results.isArray() || results.isEmpty()) {
      return new ModerationResult(false, Map.of(), Map.of());
    }

    JsonNode r = results.get(0);
    boolean flagged = r.path("flagged").asBoolean(false);
    Map<String, Boolean> categories = toBooleanMap(r.path("categories"));
    Map<String, Double> scores = toDoubleMap(r.path("category_scores"));
    return new ModerationResult(flagged, categories, scores);
  }

  private Map<String, Boolean> toBooleanMap(JsonNode node) {
    Map<String, Boolean> out = new LinkedHashMap<>();
    if (node == null || !node.isObject()) return out;
    node.fieldNames().forEachRemaining(name -> out.put(name, node.get(name).asBoolean(false)));
    return out;
  }

  private Map<String, Double> toDoubleMap(JsonNode node) {
    Map<String, Double> out = new LinkedHashMap<>();
    if (node == null || !node.isObject()) return out;
    node.fieldNames().forEachRemaining(name -> out.put(name, node.get(name).asDouble(0.0)));
    return out;
  }

  public record ModerationResult(boolean flagged, Map<String, Boolean> categories, Map<String, Double> scores) {}
}
