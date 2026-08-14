# Portfolio MCP Server

A RAG-based MCP server that answers questions about Maniram Tatipamula's
portfolio — background, experience, projects, and skills — grounded in his
actual documents via PGVector similarity search, using Spring AI and Google
Gemini.

- `POST /api/chat` — `{ "question": "..." }` → `{ "answer": "..." }`
- `GET /api/health` — liveness check

Embedded as a chat widget on [the portfolio website](https://github.com/ManiraM-1/maniram-portfolio).

## Deployment

Deployed to [Render](https://render.com) as a Docker web service (free tier).
`render.yaml` describes the service; environment variables (Gemini API key,
Supabase connection details) are set as secrets in the Render dashboard, not
committed to this repo. See `.env.example` for the required variables.
