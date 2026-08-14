# Learning Notes — Portfolio RAG MCP Server

These are my running notes while building this project. Goal: understand *why*
each piece exists, not just get it working. Written for someone who already
knows Java/Spring but is new to AI/RAG/vector-database concepts.

Updated after each PRD task is completed.

---

## Where we left off (resume point)

**Tasks 1–5 are done and verified.** Task 6 is next:
1. Finish `.gitignore` (already has secrets/target rules — verify it's complete).
2. `git init` / first commit — commit only what Part 4's Task 6 checklist says.
3. Push to GitHub (`ManiraM-1/portfolio-mcp-server`).
4. Then **Task 7** — the React chat widget (`chat-widget/ChatWidget.tsx`).

---

## Part 1 — Prerequisites (the vocabulary you need before the rest makes sense)

### What is an LLM, briefly
A Large Language Model (Gemini, GPT, Claude, etc.) is a model trained to predict
the next chunk of text given everything before it. You send it text (a
"prompt"), it sends text back. It has no memory between separate API calls —
every request is stateless unless *you* resend the previous conversation as
part of the prompt. It also has no access to your private data (your resume,
your project docs) unless you put that data into the prompt yourself. That
limitation is the entire reason RAG exists (see below).

### API key
A secret string that identifies *you* to a service (here, Google's Gemini
API) so it can bill your account and rate-limit your usage. Treat it like a
password — never commit it to git. This is why the PRD's Task 6 keeps
`application.properties` out of version control.

### Vector / embedding (one-line version, expanded properly below)
A list of numbers, e.g. `[0.021, -0.114, 0.87, ...]`, that represents the
*meaning* of a piece of text. Two pieces of text with similar meaning produce
number-lists that are close together mathematically.

---

## Part 2 — Core concepts of this project

### 1. Embeddings

**The problem:** computers can't compare "meaning" directly. `"backend
frameworks"` and `"server-side technologies"` are different strings but the
same *idea*. Plain text search (`LIKE '%backend%'`) would miss the second one
entirely.

**The fix:** an embedding model reads a piece of text and outputs a fixed-length
list of floating point numbers — a *vector* — positioned in a high-dimensional
space such that semantically similar text ends up geometrically close together.
`"backend frameworks"` and `"server-side technologies"` would land near each
other in that space, even though they share zero words.

In this project: `spring.ai.google.genai.embedding.text.options.model=gemini-embedding-001`
does this. Every document we store, and every question a user asks, gets
converted to a vector using the *same* model (this matters — you can't compare
vectors produced by two different embedding models, they don't share a
coordinate system).

**Dimensions**: how many numbers are in the vector — literally the length of
the array. `gemini-embedding-001` defaults to 3072 numbers. More dimensions =
more nuance captured, at the cost of more storage and slower comparison math
(cosine similarity is a dot product over the whole array — double the length,
double the multiply-add work per comparison).

**Truncating it (Matryoshka Representation Learning)**: `gemini-embedding-001`
is trained with a technique called MRL, which deliberately front-loads the
most important information into the *first* numbers of the vector, so that a
prefix of it — say, just the first 768 numbers — is *also* a valid, usable
embedding on its own, not garbage. This is what
`spring.ai.google.genai.embedding.text.options.dimensions=768` does: it asks
the API to return only that prefix.

**Important nuance**: this doesn't mean you can truncate to *any* arbitrary
number and expect good results. Google trained and benchmarked MRL quality at
a specific, small set of checkpoint sizes — **3072 (full), 1536, 768, and
256** — with published numbers (e.g., 768 dims ≈ only 0.26% quality loss vs.
the full 3072). Picking something in between, like 500 or 900, wouldn't
necessarily break, but there's no benchmark for it and no benefit over just
using the nearest official checkpoint. Stick to the documented sizes.

In this project, though, 768 wasn't even chosen *for* `gemini-embedding-001`
in the first place — see the "who actually decided 768" note in the PGVector
section below. It's a good example of a config value that looks like a
deliberate quality/cost tradeoff but is really just an inherited constraint.

### 2. Vector similarity search

Once text is a list of numbers, "how similar are these two pieces of text?"
becomes "how close are these two points in space?" — an actual, computable
geometry problem.

**Cosine similarity** is the standard way to measure this: it looks at the
*angle* between two vectors (not the distance between their endpoints). Two
vectors pointing in almost the same direction score close to `1.0`
(very similar); perpendicular vectors score `0`; opposite vectors score `-1`.
We use `spring.ai.vectorstore.pgvector.distance-type=COSINE_DISTANCE`, so
PGVector does this angle math directly in the database.

**A concrete lesson from this project**: cosine similarity is *fuzzy*, and
"fuzzy" is the wrong tool for questions that have an exact yes/no answer. We
tried to use `similaritySearch(...).similarityThreshold(0.9)` to answer "has
this exact dataset already been loaded?" and it failed — the specific text we
searched for wasn't a close enough semantic match to any stored document, so
it returned nothing even though the data was there. Lesson: use similarity
search for "find related things"; use a direct database query (`SELECT
COUNT(*)`) for "does this exact thing exist." Right tool for the right kind of
question.

### 3. RAG — Retrieval Augmented Generation

This is the core pattern of the whole project. Recall from Part 1: an LLM
doesn't know anything about Maniram's portfolio — that data was never in its
training set. RAG solves this without retraining the model:

1. **Retrieval** — take the user's question, embed it, run a similarity search
   against your document vectors, pull back the top-K most relevant chunks.
2. **Augmented** — stuff those chunks into the prompt as context, alongside
   the original question.
3. **Generation** — the LLM reads both and writes an answer grounded in the
   provided text, instead of guessing from its training data (which would
   risk *hallucination* — confidently making things up).

In this codebase: `PortfolioTools.searchPortfolio()` is the "retrieval" step.
`ChatController` handing the retrieved chunks + question to Gemini is the
"augmented generation" step.

### 4. MCP and tool/function calling

**MCP (Model Context Protocol)** is a standard way for an AI application to
expose a set of callable "tools" (and other capabilities) to an LLM, so the
model isn't limited to just generating text — it can decide to call your Java
methods to get up-to-date or precise information.

**How the model actually calls your code** — this is "function calling" /
"tool calling", and it's not magic:
1. Spring AI reads your `@Tool`-annotated methods (`searchPortfolio`,
   `getContact`) and generates a JSON schema describing each one: name,
   description, parameter types.
2. That schema is sent to Gemini alongside your question.
3. Gemini decides — based purely on the tool *descriptions* — whether calling
   a tool would help answer the question, and if so, which one and with what
   arguments.
4. Spring AI receives that decision, actually invokes your Java method,
   and sends the *result* back to Gemini.
5. Gemini reads the result and writes the final natural-language answer.

This is why the `description` text in `@Tool(description = "...")` matters so
much — it's the *only* information the model uses to decide when to call your
method. A vague description means a vague (or absent) tool call.

### 5. Spring AI

Spring AI is a Java framework that gives you a consistent API surface
(`ChatModel`, `EmbeddingModel`, `VectorStore`, `ChatClient`) regardless of
which AI vendor sits underneath. Swapping Gemini for OpenAI, in theory, only
means changing config properties and a dependency — your `ChatController`
code stays the same. Two ideas worth understanding:

- **Auto-configuration**: Spring Boot scans the classpath at startup and
  registers beans automatically based on what's present, gated by annotations
  like `@ConditionalOnClass` (only activate if a specific class is on the
  classpath) and `@ConditionalOnProperty` (only activate if a specific
  property is set to a specific value). This is *exactly* the mechanism that
  bit us early on: the embedding auto-configuration required
  `GoogleGenAiTextEmbeddingModel` to exist on the classpath, and it didn't,
  because the dependency was declared in the wrong Maven section (see Part 3).
- **`ChatClient`**: a fluent builder around a raw `ChatModel`, letting you
  attach a system prompt and tools once, then just call
  `.prompt().user(question).call().content()` per request. See "Builder
  pattern" below for the general shape this is built on.

### 6. PGVector

Postgres doesn't natively store or search vectors efficiently. `pgvector` is a
Postgres extension that adds a `vector` column type plus operators for
distance/similarity math, so a normal Postgres database (here, hosted on
Supabase) can double as a vector database — no separate specialized database
needed. Spring AI's `PgVectorStore` is the adapter that speaks `VectorStore`
on one side and raw SQL on the other.

The column is declared with a **fixed dimension size**
(`spring.ai.vectorstore.pgvector.dimensions=768`), and PGVector enforces this
as an *exact* match on insert, not "at most N" — a vector of the wrong length
(too long or too short) is rejected outright, the same way a Java array of
`int[3]` can't hold 4 elements. This is exactly why "expected 768 dimensions,
not 3072" happened.

**Who actually decided 768, and why**: this is a good example of a config
value that looks deliberate but is actually inherited. `dimensions=768` was
already in `application.properties` from this project's original setup —
back when the plan was to use `text-embedding-004`, a model that natively,
always outputs exactly 768 numbers (no MRL truncation involved, that's just
its fixed size). That property gets read *once*, the very first time the app
starts against a fresh database, by `PgVectorStore`'s schema initializer
(`spring.ai.vectorstore.pgvector.initialize-schema=true`), which runs a
`CREATE TABLE IF NOT EXISTS ... vector(768) ...`. After that table exists,
`vector(768)` is a physical fact of the database, like any other column
type — editing the property afterward does **not** reach back and resize an
existing column. It's schema *initialization*, not schema *migration*.

So when we were later forced to switch to `gemini-embedding-001` (because
`text-embedding-004` turned out to be Vertex-AI-only, unreachable via a plain
API key — see Part 3), the `768` we configured on the *embedding* side wasn't
chosen to suit that new model at all. It was reverse-engineered to match a
column that had already been locked in by the old model's requirements. A
"clean slate" choice for `gemini-embedding-001` alone might have been 1536
for better quality — but changing it now would mean dropping/recreating the
table and re-embedding every document, since the old 768-length vectors
can't sit in a differently-sized column. **General lesson: the embedding
dimension is a schema decision, not a free-to-change config knob** — unlike
most Spring properties, it has a one-way door the moment real data exists
behind it.

*(Decision: considered switching to 1536 for the slightly better MRL quality
checkpoint, deliberately decided against it — the quality gain is
imperceptible at 7 documents, and it's not worth the schema churn for no
practical benefit. Keeping 768.)*

### 7. Builder pattern

You'll see `.builder()...build()` constantly in this codebase
(`SearchRequest.builder()`, `ChatClient.builder()`). It's a way to construct
an object that has many optional parameters without needing a constructor
overload for every combination:

```java
SearchRequest.builder()
    .query(query)
    .topK(3)
    .similarityThreshold(0.5)
    .build();
```

Each `.xxx(...)` call returns the same builder object with that field set,
so they chain. `.build()` at the end produces the final, immutable object.
Compare to a constructor like `new SearchRequest(query, 3, 0.5, null, null,
...)` — with a builder you only specify what you actually care about, in any
order, by name.

### 8. `CommandLineRunner`

A Spring Boot interface with one method (`run(String... args)`) that Spring
automatically calls once, right after the application context finishes
starting up, before the app is ready to serve traffic. It's the standard hook
for "do some setup work at startup" — here, `DataLoader` uses it to load
`portfolio-data.json` into the vector store. Any `@Component` implementing
this interface gets picked up and run automatically; you never call it
yourself.

### 9. Java Streams

Used for transforming collections without writing manual loops, e.g. in
`PortfolioTools`:

```java
results.stream()
    .map(Document::getText)
    .collect(Collectors.joining("\n\n---\n\n"));
```

Read as a pipeline: take the list of `Document`s → transform each one into
its text (`.map`) → combine all the strings into one, separated by
`"\n\n---\n\n"` (`.collect(Collectors.joining(...))`). The equivalent
imperative loop would be a `for` loop with a `StringBuilder`; streams express
the same operation as a declarative pipeline instead.

### 10. System prompts (a taste of prompt engineering)

Every call to `chatClient.prompt()` actually sends *two* pieces of text to
Gemini: the **system prompt** (set once, via `.defaultSystem(...)` in
`ChatController`'s constructor) and the **user prompt** (the visitor's actual
question, set per-request via `.user(...)`). The system prompt is instructions
*about how the model should behave*, invisible to the end user — it's not
part of the conversation, it's the model's operating rules for the whole
conversation.

A well-written system prompt does a few specific jobs, all visible in
`ChatController`'s prompt:
- **Forces tool use** ("Always call searchPortfolio first") — without this,
  the model might just answer from its own general knowledge/guesswork
  instead of your actual documents, defeating the entire point of RAG.
- **Constrains length/tone** ("3-5 sentences", "professional") — models
  default to verbose unless told otherwise.
- **Defines the failure path explicitly** — giving the model the *exact*
  sentence to say when it has no answer, rather than leaving it to improvise
  (which is where hallucination creeps in). We tested this directly: a
  question with no matching document ("capital of France") returned exactly
  the fallback sentence, verbatim, because the prompt specified it verbatim.

General lesson: for anything a model absolutely must not do wrong (inventing
facts, going off-topic, ignoring your tools), don't imply it — write it as an
explicit rule in the system prompt.

### 11. CORS (Cross-Origin Resource Sharing)

**The rule browsers enforce**: by default, JavaScript running on `page-a.com`
is *not allowed* to read the response of a `fetch()` call to `api-b.com` — this
is the browser's "same-origin policy," and it exists so a malicious website
can't silently make authenticated requests to your bank/email/etc. using
cookies your browser is already carrying. It's a browser-side protection, not
a server-side one — a `curl` request or a Java `HttpClient` call is completely
unaffected by it, which is why testing CORS requires actually inspecting
response headers rather than just checking "did the request succeed."

**How the server opts back in**: the server adds an `Access-Control-Allow-Origin`
response header naming which origins it trusts. If a browser sees that header
match the page's own origin, it lets the JS read the response; otherwise it
blocks it client-side, even though the HTTP response already arrived.

**Preflight requests**: for anything beyond a "simple" GET, the browser first
sends an automatic `OPTIONS` request (a "preflight") asking "would you allow a
POST with a Content-Type header, from this origin?" *before* sending the real
request. The server has to answer that OPTIONS request correctly too — this
is why we tested with `curl -X OPTIONS` and the `Access-Control-Request-*`
headers rather than just testing the real `POST`.

**The bug this task fixed**: this codebase had *two independent* CORS
mechanisms active at once — a global `CorsFilter` bean (a raw Servlet filter,
runs on every request regardless of controller) and a `@CrossOrigin(origins =
"*")` annotation directly on `ChatController` (a Spring-MVC-level mechanism,
only applies to that controller). Two separate systems both trying to answer
"is this origin allowed" is a recipe for either conflicting headers or one
silently overriding the other's config. Concretely: even after making the
filter's origin list configurable via a property, the hardcoded `"*"` on the
controller annotation would have kept `/api/chat` wide open in production
regardless of what the property said. Fix: pick one mechanism (the filter,
since it's already global) and delete the other. Lesson: when two
independent config surfaces claim to control the same behavior, that's a bug
waiting to happen even before either one is misconfigured.

### 12. Docker & containers

**The problem it solves**: "works on my machine" — your laptop has a specific
JDK version, specific environment variables, specific everything. Hugging
Face's servers have none of that by default. A **container** packages your
app *plus* everything it needs to run (here: a JRE) into one portable unit
that behaves identically wherever it runs.

**A container is not a virtual machine** — it doesn't boot a whole separate
OS. It's an isolated process that shares the host machine's kernel but has
its own filesystem, network, and process view. That's why containers start in
under a second and use a fraction of a VM's resources.

**The `Dockerfile`, line by line** (`Dockerfile` in the project root):
```dockerfile
FROM eclipse-temurin:21-jre-alpine   # start from a pre-built image containing a JRE 21 on tiny Alpine Linux
WORKDIR /app                          # every following instruction runs relative to /app inside the image
COPY target/*.jar app.jar             # copy the jar we already built on the host into the image
EXPOSE 8081                           # documents "this container listens on 8081" (informational — doesn't open it by itself)
ENTRYPOINT ["java", "-jar", "app.jar"] # the command that runs when a container starts from this image
```

**Image vs. container**: the `Dockerfile` describes how to build an
**image** (`docker build`) — a static, reusable template. A **container** is
a running instance of that image (`docker run`) — you can start many
containers from the same image. Same relationship as a Java `class` vs. an
`instance` of it.

**Why `COPY target/*.jar app.jar` instead of building the jar inside
Docker**: this Dockerfile expects `mvn clean package` to have already run on
the host *before* `docker build`. This keeps the image simple, but means the
build step (`mvn package`) has to happen first, every time — worth knowing so
a redeploy doesn't silently ship a stale jar.

### 13. Passing secrets into a container (environment variables + Spring Boot relaxed binding)

Two things had to line up to make `application.properties` work both locally
and inside a container without maintaining two separate config files:

**1. The `${ENV_VAR:default}` placeholder syntax.** Spring reads this at
startup: if an environment variable named `ENV_VAR` exists in the process
environment, use its value; otherwise, use the text after the `:`. This is
why the same property line works in both places:
```properties
spring.datasource.password=${SPRING_DATASOURCE_PASSWORD:M@niram812525}
```
Locally, no such env var is set, so it falls back to the real password
(fine, since this specific file never gets committed to git — see Task 6).
On Hugging Face, the Space settings inject `SPRING_DATASOURCE_PASSWORD` as a
real environment variable, which overrides the fallback — the production
secret never has to live in a file at all.

**2. Spring Boot already treats environment variables as a config source, on
its own — relaxed binding.** Even without the `${...}` syntax, Spring Boot
automatically checks the OS environment for a property using a naming
convention: `spring.datasource.url` ⇄ `SPRING_DATASOURCE_URL` (dots become
underscores, everything upper-cased). It also ranks environment variables
*above* the packaged `application.properties` file in priority. So the
`${...}` rewrite here is really about making the fallback value explicit and
documented in one place — the override-from-env-var behavior would have
worked either way. Worth knowing both: relaxed binding explains *why* setting
`SERVER_PORT` on Hugging Face works even though nothing in this codebase
explicitly wires it up.

**How a container actually receives an env var**: `docker run -e
SPRING_DATASOURCE_PASSWORD=xxx ...` (or, on Hugging Face, values typed into
the Space's Settings → Repository secrets / variables UI) — the container
runtime injects them into the process environment before your `java -jar`
command even starts, so this needs zero application code, just the property
file wiring above.

---

## Part 3 — Real bugs hit during this project (and the engineering lessons)

These aren't hypothetical — every one of these caused an actual failure while
building this app. Recording the *lesson*, not just the fix.

### Maven `<dependencyManagement>` vs `<dependencies>`
`<dependencyManagement>` only **pins a version** for a dependency *if* it's
also declared in `<dependencies>` somewhere — it does not add the dependency
to your classpath by itself. We had the Google GenAI embedding starter sitting
only in `<dependencyManagement>`, so it silently never got included, and
Spring couldn't find the `EmbeddingModel` bean it needed. Lesson: these two
sections answer different questions — "what version, if used" vs. "use it."

### Spring Boot conditional auto-configuration
When a bean you expect "just isn't there," don't guess — run with
`--debug` (or `-Dspring-boot.run.arguments=--debug` under Maven) and read the
**condition evaluation report**. It tells you exactly which auto-configuration
classes matched or didn't, and why (missing class on classpath, property not
set to the expected value, etc.). This turned a multi-hour guessing exercise
into a five-second answer twice in this project.

### AI model lifecycle / deprecation
Hardcoded model name strings (`"gemini-1.5-flash"`, `"text-embedding-004"`)
are not permanent — vendors retire older models over time, and a previously
working config can start returning `404 model not found` months later with no
code change on your side. Two mitigations: (1) when available, use a rolling
alias (`gemini-flash-latest`) instead of a pinned version for
side-projects/hobby use, accepting that behavior can shift slightly over time
in exchange for not breaking; (2) when a model 404s, check the vendor's live
`ListModels` endpoint rather than guessing a replacement name — for Gemini
that's `GET https://generativelanguage.googleapis.com/v1beta/models?key=...`.

### Idempotency
An operation is **idempotent** if running it twice produces the same end
state as running it once. `DataLoader.run()` was *not* idempotent — every
restart called `vectorStore.add()` again with no check, so N restarts meant
`7 × N` rows. This is a general lesson for anything that runs on startup, on
retry, or on redeploy (migrations, message consumers, seed scripts): always
ask "what happens if this runs twice?"

### Logging exceptions correctly
`log.error("msg: {}", e.getMessage())` only logs the exception's message
*text* — the stack trace and any nested "Caused by" exceptions are thrown
away. `log.error("msg", e)` passes the actual `Throwable` to SLF4J, which
prints the full trace. The difference was the reason the original `/api/chat`
500 error was a total mystery until this one-line fix.

---

## Part 4 — Progress log (filled in after each PRD task)

### Task 1 — Fix `/api/chat` 500 error ✅
Root cause: `gemini-1.5-flash` retired by Google. Fixed logging first (see
Part 3), which surfaced the real exception, then switched the chat model to
`gemini-flash-latest`. Verified both tool paths work: `searchPortfolio` (skills
question) and `getContact` (contact question).

### Task 2 — Prevent duplicate document loading ✅
The PRD's suggested fix (similarity-search-based existence check) didn't
actually work in practice — see the cosine similarity lesson in Part 3.
Replaced with a direct `SELECT COUNT(*)` via `JdbcTemplate`. Verified: first
boot after a clean table loads 7 rows; second boot logs "Documents already
loaded, skipping ingestion" and adds zero.

### Task 3 — System prompt ✅
Replaced the vague original system prompt with an explicit rules-based one
(see Part 2 §10). Verified two behaviors directly: an in-scope question
("What did Maniram do at Chargebee?") pulled a factual, on-topic answer via
`searchPortfolio`; an out-of-scope question ("capital of France") returned
the exact fallback sentence specified in the prompt, instead of the model
guessing or going off-topic.

### Task 4 — CORS via environment variable ✅
Made `CorsFilter`'s allowed origins configurable via `cors.allowed-origins`
(comma-separated, defaults to `*`). Also found and removed a second, hardcoded
`@CrossOrigin(origins = "*")` on `ChatController` — see Part 2 §11 for why
having both was a real bug, not just untidy code. Verified with real preflight
(`OPTIONS`) requests: default config reflects any origin back with a single
clean `Access-Control-Allow-Origin` header (no duplication from the two
mechanisms fighting each other); with `cors.allowed-origins` restricted to one
domain, a matching `Origin` gets `200` + proper headers, a mismatched one gets
`403` with no CORS headers at all.

### Task 5 — Hugging Face deployment files ✅
Created `Dockerfile` and `.env.example` (see Part 2 §12). Rewrote the
datasource + Gemini API key properties in `application.properties` to read
from environment variables with the real local values as fallback defaults
(see Part 2 §13). Verified with a real `docker build` + `docker run`:
- Built the jar (`mvnw clean package`), built the image, ran it with
  `-e CORS_ALLOWED_ORIGINS=https://test-override.example.com` to prove env
  vars actually override the baked-in fallback *inside* the container, not
  just locally on the host.
- `GET /api/health` → `{"status":"UP"}`.
- CORS preflight from the override origin → `200` with a matching
  `Access-Control-Allow-Origin`; a mismatched origin → clean `403`. Confirms
  the env-var override wiring works end-to-end inside the container.
- `POST /api/chat` — both tool paths verified inside the container:
  `searchPortfolio` (Chargebee question) and `getContact` (contact question)
  both returned correct, grounded answers.

**Real bug found and fixed along the way — Supabase direct connection is
IPv6-only.** The container couldn't reach Postgres at all
(`SocketException: Network unreachable`). Root cause: Supabase's *direct
connection* host (`db.<ref>.supabase.co`) only has an `AAAA` (IPv6) DNS
record, and Docker's default bridge network only routes IPv4 — so the
connection was never going to work, in any Docker container, not just this
one. This would have failed identically on Hugging Face Spaces, which is
also IPv4-only, so catching it here instead of after deploying was the
whole point of doing this verification step at all. **Fix**: switched
`spring.datasource.url`/`username` to Supabase's **Session Pooler**
endpoint (`aws-0-<region>.pooler.supabase.com`, username
`postgres.<project-ref>`) — Supabase proxies this over IPv4 specifically
for environments like this. Updated in `application.properties`,
`application.properties.template`, and `.env.example` so this is documented
for whoever (future me) sets the Hugging Face env vars later. General
lesson: a hostname resolving fine from your host machine doesn't mean it's
reachable from *inside* a container — different network namespace, and
IPv6-vs-IPv4 routing differences are an easy silent trap. Test connectivity
from inside the container you're actually going to ship, not just from the
host.

**Also hit, unrelated to the app**: the Windows machine's `C:` drive was
almost completely full (a handful of MB free out of 222GB), which is why
Docker Desktop kept failing to even deploy its WSL2 VM in the first place
(`not enough space on disk`, then a corrupted half-deployed distro after a
forced retry). Fixed by clearing ~9GB of safe, regenerable caches (`npm`,
`.gradle`, `.m2`, `pip`, `Yarn`, Temp) and unregistering the broken
`docker-desktop` WSL distro so it could redeploy clean. Not an app bug, but
worth remembering: `docker info` hanging or Docker Desktop's engine refusing
to start on this machine is very likely a disk-space problem first, before
assuming a Docker config issue.

### Task 6 — `.gitignore` + push to GitHub (pending)

### Task 7 — React chat widget (pending)
