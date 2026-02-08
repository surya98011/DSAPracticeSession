# TweeterChaatBot (Java)

Fetches the 50 most recent tweets from unique authors for a topic, summarizes them with OpenAI, and proposes a new post.

## Prereqs
- Java 17+
- X (Twitter) API Bearer Token
- OpenAI API Key

## Configure
Set env vars:
- `X_BEARER_TOKEN` (required)
- `OPENAI_API_KEY` (required)
- `OPENAI_MODEL` (optional, default `gpt-4o-mini`)
- `OPENAI_API_BASE_URL` (optional, default `https://api.openai.com/v1`)
- `OPENAI_MODERATION_MODEL` (optional, default `omni-moderation-latest`)
- `X_API_BASE_URL` (optional, default `https://api.x.com/2`)
- `PORT` (optional, default `8080`)
- `CACHE_TTL_SECONDS` (optional, default `600`)

## Run (Web UI)
```bash
mvn -q -DskipTests package
java -jar target/dsapracticesession-1.0.0.jar
```
Open `http://localhost:8080` in your browser.

## Run (CLI)
```bash
mvn -q -DskipTests package
java -jar target/dsapracticesession-1.0.0.jar --cli "your topic here"
```

## What’s New
- Streaming progress updates via SSE (`/api/generate-sse?topic=...`).
- In-memory cache for repeated topics.
- OpenAI moderation checks for the generated post.

## Notes
- The app filters out retweets and deduplicates by author to ensure different people.
- If the topic is narrow, it may return fewer than 50 unique authors.
- The web UI has a built-in preview mode for a quick visual demo.
