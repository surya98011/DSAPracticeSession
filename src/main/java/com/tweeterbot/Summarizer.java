package com.tweeterbot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class Summarizer {
  private static final Set<String> STOPWORDS = new HashSet<>(Arrays.asList(
      "a","an","the","and","or","but","if","then","than","so","to","of","for","in","on",
      "at","by","with","about","as","is","are","was","were","be","been","being","it","its",
      "this","that","these","those","i","you","he","she","they","we","me","my","your","our",
      "their","them","from","into","out","up","down","over","under","again","more","most","very",
      "can","could","should","would","will","just","not","no","yes","do","does","did","doing",
      "rt","via","amp","t","s"
  ));

  public SummaryResult summarize(String topic, List<Tweet> tweets) {
    List<String> cleaned = tweets.stream()
        .map(t -> cleanText(t.text()))
        .filter(s -> !s.isBlank())
        .toList();

    Map<String, Integer> freq = keywordFrequency(cleaned);
    List<String> topKeywords = topKeywords(freq, 8);

    List<Tweet> representative = topRepresentativeTweets(tweets, freq, 4);
    String summary = buildSummary(topic, topKeywords, representative);
    String post = buildPost(topic, topKeywords, representative);

    return new SummaryResult(summary, post, topKeywords, representative);
  }

  private Map<String, Integer> keywordFrequency(List<String> texts) {
    Map<String, Integer> freq = new HashMap<>();
    for (String text : texts) {
      for (String token : text.split("\\s+")) {
        String w = token.toLowerCase(Locale.ROOT);
        if (w.length() < 3 || STOPWORDS.contains(w)) continue;
        freq.put(w, freq.getOrDefault(w, 0) + 1);
      }
    }
    return freq;
  }

  private List<String> topKeywords(Map<String, Integer> freq, int n) {
    return freq.entrySet().stream()
        .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
        .limit(n)
        .map(Map.Entry::getKey)
        .collect(Collectors.toList());
  }

  private List<Tweet> topRepresentativeTweets(List<Tweet> tweets, Map<String, Integer> freq, int n) {
    List<ScoredTweet> scored = new ArrayList<>();
    for (Tweet t : tweets) {
      String cleaned = cleanText(t.text());
      int score = 0;
      for (String token : cleaned.split("\\s+")) {
        String w = token.toLowerCase(Locale.ROOT);
        score += freq.getOrDefault(w, 0);
      }
      scored.add(new ScoredTweet(t, score));
    }

    return scored.stream()
        .sorted(Comparator.comparingInt(ScoredTweet::score).reversed())
        .limit(n)
        .map(ScoredTweet::tweet)
        .collect(Collectors.toList());
  }

  private String buildSummary(String topic, List<String> keywords, List<Tweet> reps) {
    String themes = keywords.isEmpty() ? "" : String.join(", ", keywords);

    StringBuilder sb = new StringBuilder();
    sb.append("Summary for \"").append(topic).append("\": ");
    if (!themes.isBlank()) {
      sb.append("Key themes include ").append(themes).append(". ");
    }

    if (!reps.isEmpty()) {
      sb.append("Representative points: ");
      for (int i = 0; i < reps.size(); i++) {
        Tweet t = reps.get(i);
        String snippet = trimTo(t.text().replaceAll("\\s+", " "), 120);
        sb.append("\"").append(snippet).append("\"");
        if (i < reps.size() - 1) sb.append("; ");
      }
      sb.append('.');
    }

    return sb.toString();
  }

  private String buildPost(String topic, List<String> keywords, List<Tweet> reps) {
    String lead = "Quick roundup on " + topic + ": ";

    List<String> parts = new ArrayList<>();
    if (!keywords.isEmpty()) {
      parts.add(String.join(", ", keywords.subList(0, Math.min(4, keywords.size()))));
    } else if (!reps.isEmpty()) {
      parts.add(trimTo(reps.get(0).text(), 80));
    } else {
      parts.add("recent discussion and opinions");
    }

    String hashtags = buildHashtags(keywords);
    String tail = " What do you think?" + (hashtags.isBlank() ? "" : " " + hashtags);

    String post = lead + String.join(" ", parts) + "." + tail;
    return trimTo(post, 280);
  }

  private String buildHashtags(List<String> keywords) {
    List<String> tags = new ArrayList<>();
    for (String k : keywords) {
      if (tags.size() >= 2) break;
      String tag = k.replaceAll("[^a-zA-Z0-9]", "");
      if (tag.length() >= 3) {
        tags.add("#" + tag);
      }
    }
    return String.join(" ", tags);
  }

  private String cleanText(String text) {
    String s = text;
    s = s.replaceAll("https?://\\S+", " ");
    s = s.replaceAll("@\\w+", " ");
    s = s.replaceAll("#", "");
    s = s.replaceAll("[^a-zA-Z0-9\\s ]", " ");
    s = s.replaceAll("\\s+", " ").trim();
    return s;
  }

  private String trimTo(String s, int max) {
    if (s.length() <= max) return s;
    if (max <= 3) return s.substring(0, Math.max(0, max));
    return s.substring(0, Math.max(0, max - 3)).trim() + "...";
  }

  public record SummaryResult(
      String summary,
      String suggestedPost,
      List<String> keywords,
      List<Tweet> representativeTweets
  ) {}

  private record ScoredTweet(Tweet tweet, int score) {}
}
