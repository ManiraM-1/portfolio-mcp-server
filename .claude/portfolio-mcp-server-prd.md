# PRD — Portfolio RAG MCP Server
**Project:** portfolio-mcp-server  
**Owner:** Maniram Tatipamula  
**Stack:** Java 21, Spring Boot 3.3.5, Spring AI 1.1.6, PGVector (Supabase), Google Gemini  
**Goal:** A RAG-based MCP server that answers questions about Maniram's portfolio, deployed free on Hugging Face Spaces, embedded as a chat widget on his portfolio website.

---

## 1. What we are building and why

### The problem
A portfolio website is static. A recruiter reads it once and leaves. There is no way for them to ask follow-up questions, explore specific areas, or get a personalised answer.

### The solution
An AI chat assistant embedded on the portfolio website. The visitor types a question — "What backend technologies does Maniram use?" or "Tell me about his Chargebee work" — and gets a precise answer sourced from Maniram's actual documents, not hallucinated by the LLM.

### Why RAG + MCP together
- **RAG** handles unstructured questions — finds relevant chunks from documents using vector similarity search, feeds them to Gemini as context
- **MCP tools** handle structured queries — contact info, project list — where vector search is overkill
- Combined: Gemini decides which tool to call based on the question. For nuanced questions it calls `searchPortfolio()` which triggers the RAG pipeline. For simple structured queries it calls `getContact()` directly.

### Why this demonstrates real AI engineering skill
- It is the same pattern Maniram built at Chargebee — documentation as callable tools for AI agents
- PGVector + embeddings + semantic search is the core of most production AI products today
- Java/Spring AI for MCP is rare — the entire field is Python-heavy, this is a genuine differentiator

---

## 2. Architecture

```
Portfolio Website (React — maniram-portfolio repo)
        │
        │ Page loads → silent GET /api/health (wakes Hugging Face server)
        │ User types question → POST /api/chat { "question": "..." }
        │
        ▼
Spring Boot MCP Server (Hugging Face Spaces — Docker)
        │
        ├── ChatController
        │     └── receives POST /api/chat
        │     └── passes question to Gemini with tools registered
        │
        ├── Gemini Flash (MCP Client)
        │     └── reads tool descriptions
        │     └── decides which tool to call
        │
        ├── PortfolioTools (MCP Tools)
        │     ├── searchPortfolio(query) ← RAG pipeline
        │     │     ├── embed query → 768-dim vector (Gemini text-embedding-004)
        │     │     ├── similarity search PGVector → top 3 chunks
        │     │     └── return chunks as context to Gemini
        │     └── getContact() ← direct vector lookup
        │
        └── Supabase PGVector
              └── vector_store table (7 documents, 768 dimensions, cosine distance)

DataLoader (runs on every startup)
        └── reads portfolio-data.json from resources/
        └── converts to Document objects
        └── embeds + stores in PGVector via vectorStore.add()
```

---

## 3. Current state (what Claude Code built)

### ✅ Done and working
- `pom.xml` — Spring Boot 3.3.5, Spring AI 1.1.6, PGVector, Google GenAI embedding + chat, PostgreSQL driver
- `portfolio-data.json` — knowledge base in `src/main/resources/` with experience, projects, skills, contact
- `DataLoader.java` — loads JSON, converts to Documents, embeds and stores in Supabase on startup. Confirmed: "Successfully loaded 7 documents"
- `PortfolioTools.java` — two `@Tool` methods: `searchPortfolio(query)` and `getContact()`
- `ChatController.java` — `POST /api/chat` and `GET /api/health` endpoints
- `CorsConfig.java` — allows cross-origin requests from portfolio frontend
- Server starts successfully on port 8081
- PGVector table created in Supabase with 7 rows confirmed

### ❌ Not working yet
- `POST /api/chat` returns 500 Internal Server Error
- Root cause unknown — server logs needed. Likely one of:
  - Gemini chat model not auto-configured (missing property or wrong model name)
  - `ChatClient` builder failing because `GoogleGenAiChatModel` bean not found
  - Tool registration failing at runtime

### 📁 Project structure
```
src/main/java/com/maniram/portfolio_mcp_server/
├── PortfolioMcpServerApplication.java
├── config/
│   ├── DataLoader.java
│   └── CorsConfig.java
├── controller/
│   └── ChatController.java
└── tools/
    └── PortfolioTools.java

src/main/resources/
├── application.properties
└── portfolio-data.json
```

---

## 4. Task list for Claude Code

### Task 1 — Fix the 500 error on POST /api/chat (PRIORITY 1)

**Symptoms:** Server starts, DataLoader works, health endpoint works, but `/api/chat` returns 500.

**What to investigate (in order):**
1. Check server logs for the exact exception after a POST /api/chat call
2. Verify `GoogleGenAiChatModel` bean is being created — add a startup log in ChatController constructor
3. Check `application.properties` has the correct chat model property:
   ```properties
   spring.ai.google.genai.chat.options.model=gemini-1.5-flash
   spring.ai.google.genai.api-key=<key>
   ```
4. If `ChatClient` builder fails, try autowiring `ChatModel` (the interface) instead of `GoogleGenAiChatModel` (the implementation) — Spring AI recommends the interface
5. Verify `@Tool` annotation import is `org.springframework.ai.tool.annotation.Tool` not any other package
6. Add proper error logging in `ChatController.chat()` — currently the catch block only logs `e.getMessage()`. Change to `log.error("Error", e)` to see the full stack trace

**Expected fix:** The `/api/chat` endpoint returns:
```json
{"answer": "Maniram has built three main projects: Gatify..."}
```

---

### Task 2 — Prevent duplicate document loading on restarts

**Problem:** Every time the server restarts, `DataLoader` calls `vectorStore.add()` again, creating duplicate rows in Supabase. After 10 restarts there are 70 rows instead of 7.

**Fix:** Before loading, check if documents already exist:
```java
// In DataLoader.run(), add this check first:
List<Document> existing = vectorStore.similaritySearch(
    SearchRequest.builder()
        .query("Maniram Tatipamula portfolio")
        .topK(1)
        .similarityThreshold(0.9)
        .build()
);
if (!existing.isEmpty()) {
    log.info("Documents already loaded, skipping ingestion.");
    return;
}
```

---

### Task 3 — Improve the system prompt and answer quality

**In `ChatController.java`, update the system prompt to:**
```
You are an AI assistant for Maniram Tatipamula's portfolio website.
Your job is to answer questions from recruiters and visitors about Maniram's
background, experience, projects, and skills.

Rules:
- Always call searchPortfolio first before answering any question about his work
- Be concise — 3-5 sentences maximum unless asked for detail
- Be factual — only state what the documents say, never invent details
- Be professional — this is a portfolio, not a casual conversation
- If someone asks how to contact Maniram, call getContact()
- If you cannot find relevant information, say: "I don't have specific information
  about that. You can reach Maniram directly at maniram24crt@gmail.com"
```

---

### Task 4 — Add CORS environment variable support

**Problem:** `CorsConfig.java` currently hardcodes `allowedOriginPattern("*")` which is fine for development but too permissive for production.

**Fix:** Read allowed origins from a property:
```java
@Value("${cors.allowed-origins:*}")
private String allowedOrigins;
```

In `application.properties`:
```properties
cors.allowed-origins=*
```

On Hugging Face, set it to the actual portfolio domain once deployed.

---

### Task 5 — Create Hugging Face deployment files

**File 1: `Dockerfile`** in project root:
```dockerfile
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**File 2: `.env.example`** in project root (template, never commit real values):
```
SPRING_AI_GOOGLE_GENAI_API_KEY=your_gemini_api_key
SPRING_AI_GOOGLE_GENAI_EMBEDDING_API_KEY=your_gemini_api_key
SPRING_DATASOURCE_URL=jdbc:postgresql://db.xxx.supabase.co:5432/postgres
SPRING_DATASOURCE_USERNAME=postgres
SPRING_DATASOURCE_PASSWORD=your_supabase_password
```

**File 3: update `application.properties`** to read from environment variables with fallback:
```properties
spring.ai.google.genai.api-key=${SPRING_AI_GOOGLE_GENAI_API_KEY:local-key}
spring.ai.google.genai.embedding.api-key=${SPRING_AI_GOOGLE_GENAI_EMBEDDING_API_KEY:local-key}
spring.datasource.url=${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/postgres}
spring.datasource.username=${SPRING_DATASOURCE_USERNAME:postgres}
spring.datasource.password=${SPRING_DATASOURCE_PASSWORD:password}
```

This way: local dev reads from `application.properties` directly, Hugging Face reads from environment variables set in the Space settings.

---

### Task 6 — Push to GitHub with correct .gitignore

**`.gitignore` must include:**
```
# Secrets
src/main/resources/application.properties

# Build
target/
*.jar

# IDE
.idea/
*.iml
.vscode/

# OS
.DS_Store
Thumbs.db
```

**Commit only:**
- All Java source files
- `portfolio-data.json`
- `application.properties.template` (with placeholder values, no real keys)
- `Dockerfile`
- `.env.example`
- `pom.xml`
- `NOTES.md`

---

### Task 7 — Build the React chat widget (portfolio-mcp-server repo)

Create `chat-widget/ChatWidget.tsx` — a self-contained React component to be copied into the portfolio website.

**Behaviour:**
- On mount: silent `GET /api/health` to wake the Hugging Face server
- Floating button bottom-right corner: amber chat bubble icon
- Click opens a modal/drawer with a chat interface
- Input field + send button
- Messages displayed in a thread (user right, assistant left)
- Loading state while waiting for response (animated dots)
- Error state if server fails ("Server is waking up, try again in 30 seconds")
- Remembers conversation in React state (cleared on page refresh — no persistence needed)

**API call:**
```typescript
const response = await fetch(`${MCP_SERVER_URL}/api/chat`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ question: userMessage })
});
const data = await response.json();
// data.answer contains the response
```

**Config at top of file:**
```typescript
const MCP_SERVER_URL = import.meta.env.VITE_MCP_SERVER_URL || 'http://localhost:8081';
```

**Styling:** matches portfolio theme — dark background `#070707`, amber accents `rgb(245,158,11)`, rounded corners, same font stack.

---

## 5. Knowledge base documents (what's in portfolio-data.json)

7 documents stored in PGVector:

| # | Source | Content |
|---|---|---|
| 1 | experience | Chargebee internship — role, bullets |
| 2 | experience | GSSoC — role, bullets |
| 3 | projects | Gatify — description, metric, tech, bullets |
| 4 | projects | Fitness Tracking App — description, metric, tech, bullets |
| 5 | projects | Crypto Analysis — description, metric, tech, bullets |
| 6 | skills | All four skill groups |
| 7 | contact | Name, email, LinkedIn, GitHub, location, education, CGPA |

---

## 6. Environment variables (Hugging Face Spaces settings)

| Variable | Value |
|---|---|
| `SPRING_AI_GOOGLE_GENAI_API_KEY` | Your Gemini API key |
| `SPRING_AI_GOOGLE_GENAI_EMBEDDING_API_KEY` | Same Gemini API key |
| `SPRING_DATASOURCE_URL` | Supabase JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | `postgres` |
| `SPRING_DATASOURCE_PASSWORD` | Your Supabase password |
| `SERVER_PORT` | `8081` |

---

## 7. Deployment flow (after all tasks done)

```
1. mvn clean package -DskipTests
   → produces target/portfolio-mcp-server-0.0.1-SNAPSHOT.jar

2. Push to GitHub (ManiraM-1/portfolio-mcp-server)

3. Create Hugging Face Space:
   - Owner: ManiraM-1
   - Space name: portfolio-mcp-server
   - SDK: Docker
   - Visibility: Public

4. Connect GitHub repo to Hugging Face Space
   → auto-deploys on every push to main

5. Set environment variables in Space Settings

6. Add to portfolio website:
   - Copy ChatWidget.tsx to src/components/
   - Add VITE_MCP_SERVER_URL=https://maniram1-portfolio-mcp-server.hf.space to .env
   - Render <ChatWidget /> in Index.tsx

7. Portfolio page load flow:
   → ChatWidget mounts → pings /api/health silently
   → Hugging Face wakes up (30 sec cold start)
   → User opens chat → server already awake → instant response
```

---

## 8. What Maniram is learning by building this

- **Embeddings** — converting text to vectors representing meaning
- **Vector similarity search** — finding semantically related documents
- **RAG pipeline** — retrieval augmented generation, the core pattern of AI products
- **MCP protocol** — how AI agents call external tools
- **Spring AI** — Java-native AI framework wrapping LLMs, embeddings, vector stores
- **PGVector** — using Postgres as a vector database
- **Builder pattern** — constructing complex objects cleanly
- **CommandLineRunner** — running startup tasks in Spring Boot
- **Java Streams** — transforming collections without loops
- **Docker** — containerising a Java app for deployment
