package com.tweeterbot;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

public class OpenAIClient {
  private final String apiKey;
  private final String baseUrl;
  private final String model;
  private final HttpClient http;
  private final ObjectMapper mapper;

  public OpenAIClient(String apiKey, String baseUrl, String model) {
    this.apiKey = apiKey;
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.model = model;
    this.http = HttpClient.newHttpClient();
    this.mapper = new ObjectMapper();
  }

  public SummaryPayload summarize(String topic, List<Tweet> tweets) throws IOException, InterruptedException {
    ObjectNode root = mapper.createObjectNode();
    root.put("model", model);
    var input = root.putArray("input");
    ObjectNode sys = input.addObject();
    sys.put("role", "system");
    sys.put("content", "You are a social media assistant. Create a concise summary and an original new post based strictly on the provided tweets. Do not invent facts, do not quote verbatim, and keep the suggested post within 280 characters. Return JSON only.");
    ObjectNode user = input.addObject();
    user.put("role", "user");
    user.put("content", buildPrompt(topic, tweets));

    ObjectNode text = root.putObject("text");
    ObjectNode format = text.putObject("format");
    format.put("type", "json_schema");
    format.put("name", "tweet_summary");
    format.put("description", "Summary, keywords, bullets, and a suggested post based on recent tweets.");
    format.put("strict", true);

    ObjectNode schema = format.putObject("schema");
    schema.put("type", "object");
    ObjectNode props = schema.putObject("properties");
    props.putObject("summary").put("type", "string");
    props.putObject("suggested_post").put("type", "string");
    props.putObject("keywords").put("type", "array").putObject("items").put("type", "string");
    props.putObject("bullets").put("type", "array").putObject("items").put("type", "string");
    schema.putArray("required")
        .add("summary")
        .add("suggested_post")
        .add("keywords")
        .add("bullets");
    schema.put("additionalProperties", false);

    root.put("temperature", 0.4);

    HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/responses"))
        .header("Authorization", "Bearer " + apiKey)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(root)))
        .build();

    HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() != 200) {
      throw new IOException("OpenAI API error: HTTP " + resp.statusCode() + " -> " + resp.body());
    }

    String outputText = extractOutputText(resp.body());
    if (outputText == null || outputText.isBlank()) {
      throw new IOException("OpenAI API response missing output_text.");
    }

    SummaryPayload payload = mapper.readValue(outputText, SummaryPayload.class);
    payload.suggestedPost = trimTo(payload.suggestedPost, 280);
    return payload;
  }

  private String extractOutputText(String json) throws IOException {
    JsonNode root = mapper.readTree(json);
    JsonNode output = root.path("output");
    if (!output.isArray()) return null;

    for (JsonNode item : output) {
      if (!"message".equals(item.path("type").asText())) continue;
      JsonNode content = item.path("content");
      if (!content.isArray()) continue;
      for (JsonNode c : content) {
        if ("output_text".equals(c.path("type").asText())) {
          return c.path("text").asText();
        }
      }
    }
    return null;
  }

  private String buildPrompt(String topic, List<Tweet> tweets) {
    StringBuilder sb = new StringBuilder();
    sb.append("Topic: ").append(topic).append("\n");
    sb.append("Tweets:\n");
    int i = 1;
    for (Tweet t : tweets) {
      sb.append(i).append(") ");
      sb.append(t.authorName()).append(" (@").append(t.authorUsername()).append(") - ");
      sb.append(t.text().replaceAll("\\s+", " ").trim());
      sb.append("\n");
      i++;
    }
    sb.append("\nReturn JSON only.");
    return sb.toString();
  }

  private String trimTo(String s, int max) {
    if (s == null) return "";
    if (s.length() <= max) return s;
    if (max <= 3) return s.substring(0, Math.max(0, max));
    return s.substring(0, Math.max(0, max - 3)).trim() + "...";
  }

  public static class SummaryPayload {
    public String summary;

    @JsonProperty("suggested_post")
    public String suggestedPost;

    public List<String> keywords;
    public List<String> bullets;

    public SummaryPayload() {
      this.keywords = new ArrayList<>();
      this.bullets = new ArrayList<>();
    }
  }
}
