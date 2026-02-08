package com.tweeterbot;

public class App {
  public static void main(String[] args) {
    boolean cli = args.length > 0 && "--cli".equalsIgnoreCase(args[0]);

    if (cli) {
      if (args.length < 2) {
        System.err.println("Usage: java -jar tweeter-bot.jar --cli <topic>");
        System.exit(1);
      }
      String topic = joinArgs(args, 1);
      CliRunner.run(topic);
      return;
    }

    WebServer.start();
  }

  private static String joinArgs(String[] args, int start) {
    StringBuilder sb = new StringBuilder();
    for (int i = start; i < args.length; i++) {
      if (i > start) sb.append(' ');
      sb.append(args[i]);
    }
    return sb.toString().trim();
  }
}
