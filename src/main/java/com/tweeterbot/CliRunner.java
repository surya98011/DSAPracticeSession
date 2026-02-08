package com.tweeterbot;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class CliRunner {
  public static void run(String topic) {
    String xToken = System.getenv("X_BEARER_TOKEN");
    if (xToken == null || xToken.isBlank()) {
      System.err.println("Missing X_BEARER_TOKEN environment variable.");
      System.exit(1);
    }

    String openAiKey = System.getenv("OPENAI_API_KEY");
    if (openAiKey == null || openAiKey.isBlank()) {
      System.err.println("Missing OPENAI_API_KEY environment variable.");
      System.exit(1);
    }

    String xBase = System.getenv().getOrDefault("X_API_BASE_URL", "https://api.x.com/2");
    String oaBase = System.getenv().getOrDefault("OPENAI_API_BASE_URL", "https://api.openai.com/v1");
    String model = System.getenv().getOrDefault("OPENAI_MODEL", "gpt-4o-mini");
    String moderationModel = System.getenv().getOrDefault("OPENAI_MODERATION_MODEL", "omni-moderation-latest");

    try {
      XClient xClient = new XClient(xToken, xBase);
      List<Tweet> tweets = xClient.fetchRecentUniqueAuthors(topic, 50);

      System.out.println("Fetched " + tweets.size() + " tweets from unique authors.");
      System.out.println();

      DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
          .withZone(ZoneId.systemDefault());

      int i = 1;
      for (Tweet t : tweets) {
        String when = t.createdAt() == null ? "" : dtf.format(t.createdAt());
        System.out.println(i + ". " + t.authorName() + " (@" + t.authorUsername() + ")" + (when.isBlank() ? "" : " - " + when));
        System.out.println("   " + t.text().replaceAll("\\s+", " ").trim());
        System.out.println();
        i++;
      }

      OpenAIClient ai = new OpenAIClient(openAiKey, oaBase, model);
      OpenAIClient.SummaryPayload summary = ai.summarize(topic, tweets);

      OpenAIModerationClient moderation = new OpenAIModerationClient(openAiKey, oaBase, moderationModel);
      OpenAIModerationClient.ModerationResult mod = moderation.moderate(summary.suggestedPost);
      if (mod.flagged()) {
        summary.suggestedPost = "Suggested post withheld due to safety policies.";
      }

      System.out.println("Summary:");
      System.out.println(summary.summary);
      System.out.println();

      System.out.println("Suggested Post:");
      System.out.println(summary.suggestedPost);
    } catch (Exception e) {
      System.err.println("Error: " + e.getMessage());
      System.exit(1);
    }
  }
}
