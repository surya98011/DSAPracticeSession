package com.tweeterbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class XClient {
  private final String bearerToken;
  private final String baseUrl;
  private final HttpClient http;
  private final ObjectMapper mapper;

  public XClient(String bearerToken, String baseUrl) {
    this.bearerToken = bearerToken;
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.http = HttpClient.newHttpClient();
    this.mapper = new ObjectMapper();
  }

  public List<Tweet> fetchRecentUniqueAuthors(String topic, int targetCount) throws IOException, InterruptedException {
    if (targetCount <= 0) {
      return List.of();
    }

    String nextToken = null;
    Map<String, Tweet> byAuthor = new LinkedHashMap<>();
    int safetyPages = 5; // avoid endless loops if topic is too narrow

    while (byAuthor.size() < targetCount && safetyPages-- > 0) {
      String url = buildSearchUrl(topic, nextToken);
      HttpRequest req = HttpRequest.newBuilder(URI.create(url))
          .header("Authorization", "Bearer " + bearerToken)
          .header("User-Agent", "TweeterChaatBot/1.0")
          .GET()
          .build();

      HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() != 200) {
        throw new IOException("X API error: HTTP " + resp.statusCode() + " -> " + resp.body());
      }

      JsonNode root = mapper.readTree(resp.body());
      Map<String, User> users = parseUsers(root.path("includes").path("users"));
      List<Tweet> tweets = parseTweets(root.path("data"), users);

      for (Tweet t : tweets) {
        byAuthor.putIfAbsent(t.authorId(), t);
        if (byAuthor.size() >= targetCount) {
          break;
        }
      }

      JsonNode meta = root.path("meta");
      nextToken = meta.has("next_token") ? meta.get("next_token").asText() : null;
      if (nextToken == null || nextToken.isBlank()) {
        break;
      }
    }

    return new ArrayList<>(byAuthor.values());
  }

  private String buildSearchUrl(String topic, String nextToken) {
    String query = topic + " -is:retweet";

    Map<String, String> params = new HashMap<>();
    params.put("query", query);
    params.put("max_results", "100");
    params.put("tweet.fields", "created_at,author_id,lang");
    params.put("expansions", "author_id");
    params.put("user.fields", "username,name");
    if (nextToken != null && !nextToken.isBlank()) {
      params.put("next_token", nextToken);
    }

    StringBuilder sb = new StringBuilder();
    sb.append(baseUrl).append("/tweets/search/recent?");
    boolean first = true;
    for (Map.Entry<String, String> e : params.entrySet()) {
      if (!first) sb.append('&');
      first = false;
      sb.append(encode(e.getKey())).append('=').append(encode(e.getValue()));
    }
    return sb.toString();
  }

  private String encode(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }

  private List<Tweet> parseTweets(JsonNode data, Map<String, User> users) {
    List<Tweet> out = new ArrayList<>();
    if (!data.isArray()) {
      return out;
    }

    for (JsonNode node : data) {
      String id = node.path("id").asText("");
      String text = node.path("text").asText("");
      String authorId = node.path("author_id").asText("");
      String createdAtRaw = node.path("created_at").asText("");
      Instant createdAt = createdAtRaw.isBlank() ? null : Instant.parse(createdAtRaw);

      User u = users.get(authorId);
      String name = u != null ? u.name : "Unknown";
      String username = u != null ? u.username : "unknown";

      out.add(new Tweet(id, authorId, name, username, text, createdAt));
    }
    return out;
  }

  private Map<String, User> parseUsers(JsonNode usersNode) {
    Map<String, User> map = new HashMap<>();
    if (!usersNode.isArray()) {
      return map;
    }

    for (JsonNode u : usersNode) {
      String id = u.path("id").asText("");
      String name = u.path("name").asText("");
      String username = u.path("username").asText("");
      if (!id.isBlank()) {
        map.put(id, new User(id, name, username));
      }
    }
    return map;
  }

  private static class User {
    final String id;
    final String name;
    final String username;

    User(String id, String name, String username) {
      this.id = id;
      this.name = name;
      this.username = username;
    }
  }
}
