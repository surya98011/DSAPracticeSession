const statusEl = document.getElementById("status");
const summaryEl = document.getElementById("summary");
const keywordsEl = document.getElementById("keywords");
const bulletsEl = document.getElementById("bullets");
const postEl = document.getElementById("post");
const tweetsEl = document.getElementById("tweets");
const tweetCountEl = document.getElementById("tweet-count");
const copyBtn = document.getElementById("copy");
const copyStatus = document.getElementById("copy-status");
const runBtn = document.getElementById("run");
const previewBtn = document.getElementById("preview");
const topicInput = document.getElementById("topic");
const statCountEl = document.getElementById("stat-count");
const statModelEl = document.getElementById("stat-model");
const moderationEl = document.getElementById("moderation");

let activeStream = null;

const mockData = {
  topic: "java 21 features",
  generated_at: new Date().toISOString(),
  model: "gpt-4o-mini",
  cache: false,
  moderation: {
    flagged: false,
    categories: {},
    scores: {}
  },
  summary: {
    summary: "People are excited about Java 21 performance, virtual threads, and more predictable concurrency. Many highlight real-world throughput gains, simpler async code, and smoother scaling for services.",
    suggested_post: "Quick roundup on Java 21: virtual threads are landing real-world throughput gains, simpler async code, and smoother scaling for services. What has your team shipped with it? #Java #Concurrency",
    keywords: ["virtual", "threads", "performance", "concurrency", "throughput"],
    bullets: [
      "Virtual threads are reducing boilerplate in high-concurrency services.",
      "Teams report better throughput with less tuning.",
      "Adoption is growing across web backends and data tooling."
    ]
  },
  tweets: Array.from({ length: 12 }).map((_, i) => ({
    authorName: "Developer " + (i + 1),
    authorUsername: "dev" + (i + 1),
    text: "Testing virtual threads in Java 21 is making our services more responsive. Benchmarks show steady gains.",
    createdAt: new Date(Date.now() - i * 3600 * 1000).toISOString()
  }))
};

function setStatus(text, tone = "info") {
  statusEl.textContent = text;
  statusEl.style.color = tone === "error" ? "#fca5a5" : "#38bdf8";
}

function render(data) {
  const summary = data.summary || {};
  summaryEl.textContent = summary.summary || "";
  postEl.value = summary.suggested_post || summary.suggestedPost || "";

  keywordsEl.innerHTML = "";
  (summary.keywords || []).forEach((k) => {
    const span = document.createElement("span");
    span.textContent = k;
    keywordsEl.appendChild(span);
  });

  bulletsEl.innerHTML = "";
  (summary.bullets || []).forEach((b) => {
    const li = document.createElement("li");
    li.textContent = b;
    bulletsEl.appendChild(li);
  });

  tweetsEl.innerHTML = "";
  (data.tweets || []).forEach((t) => {
    const div = document.createElement("div");
    div.className = "tweet";
    const h3 = document.createElement("h3");
    h3.textContent = `${t.authorName} (@${t.authorUsername})`;
    const p = document.createElement("p");
    p.textContent = t.text;
    div.appendChild(h3);
    div.appendChild(p);
    tweetsEl.appendChild(div);
  });

  tweetCountEl.textContent = `${(data.tweets || []).length} tweets loaded`;
  statCountEl.textContent = (data.tweets || []).length || "0";
  statModelEl.textContent = data.model || "OpenAI";

  if (data.cache) {
    setStatus("Loaded from cache.");
  }

  const moderation = data.moderation || {};
  moderationEl.textContent = moderation.flagged
    ? "Moderation: flagged (suggested post withheld)"
    : "Moderation: clear";
}

async function generate() {
  const topic = topicInput.value.trim();
  if (!topic) {
    setStatus("Please enter a topic.", "error");
    return;
  }

  runBtn.disabled = true;
  setStatus("Connecting to live stream...");

  if (activeStream) {
    activeStream.close();
  }

  const url = `/api/generate-sse?topic=${encodeURIComponent(topic)}`;
  const es = new EventSource(url);
  activeStream = es;

  es.addEventListener("status", (evt) => {
    setStatus(evt.data || "Working...");
  });

  es.addEventListener("result", (evt) => {
    const data = JSON.parse(evt.data);
    render(data);
    setStatus("Done.");
    es.close();
    activeStream = null;
    runBtn.disabled = false;
  });

  es.addEventListener("error", (evt) => {
    const msg = evt.data || "Stream error";
    setStatus(msg, "error");
    es.close();
    activeStream = null;
    runBtn.disabled = false;
  });
}

copyBtn.addEventListener("click", () => {
  postEl.select();
  document.execCommand("copy");
  copyStatus.textContent = "Copied!";
  setTimeout(() => (copyStatus.textContent = ""), 1500);
});

runBtn.addEventListener("click", generate);
previewBtn.addEventListener("click", () => {
  render(mockData);
  setStatus("Preview loaded.");
});

render(mockData);
