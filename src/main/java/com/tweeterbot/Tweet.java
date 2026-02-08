package com.tweeterbot;

import java.time.Instant;

public record Tweet(
    String id,
    String authorId,
    String authorName,
    String authorUsername,
    String text,
    Instant createdAt
) {}
