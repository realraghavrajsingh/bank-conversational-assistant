# Banking Conversational Assistant

A conversational assistant that lets an already-authenticated bank customer, in natural language:

1. **Check a balance**: "how much have I got in savings?"
2. **Get a debit explained**: "what's that RZP*TECHNOVA charge?", "why did Uber charge me twice?"
3. **Order a cheque book**: a service request is raised and confirmed with a reference number.

The model reasons about the request and chooses among banking tools. Every balance, transaction and confirmation shown comes from the (mocked) core-banking service, and a server-side grounding guard enforces that.

![Architecture](docs/architecture.png)

---

## Quick start

**Prerequisites:** Python 3.11+ and one LLM option: a Gemini API key (free at https://aistudio.google.com/apikey), Vertex AI credentials, an OpenAI key, or a local Ollama.

```bash
git clone https://github.com/realraghavrajsingh/bank-conversational-assistant.git
cd bank-conversational-assistant
make install                 # creates .venv and installs dependencies
cp .env.example .env         # then set GEMINI_API_KEY (or switch provider, see below)

make bank                    # terminal 1: mock core banking on :8001
make assistant               # terminal 2: assistant API + UI on :8000
```

Open http://localhost:8000, pick a demo customer and start chatting.

Without `make`:

```bash
python3 -m venv .venv && . .venv/bin/activate && pip install -r requirements-dev.txt
uvicorn mock_bank.app:app --port 8001
uvicorn assistant.main:app --port 8000
```

**With Docker:** `cp .env.example .env`, set your key, then `docker compose up --build`. Only the assistant is published (port 8000); the mock bank sits on the internal Compose network.

### Choosing a model

| Provider | `.env` |
|---|---|
| Gemini API (default) | `LLM_PROVIDER=gemini`, `GEMINI_API_KEY=…`, `GEMINI_MODEL=gemini-3.8-flash` |
| Vertex AI | `LLM_PROVIDER=gemini`, `VERTEX_PROJECT=…`, `VERTEX_LOCATION=global` (uses ADC) |
| OpenAI | `LLM_PROVIDER=openai`, `OPENAI_API_KEY=…`, `OPENAI_MODEL=gpt-5-mini` |
| Ollama / vLLM / any OpenAI-compatible | `LLM_PROVIDER=openai`, `OPENAI_BASE_URL=http://localhost:11434/v1`, `OPENAI_MODEL=qwen3:8b` |

The model must support tool (function) calling. If no model is configured, the service still starts: `/api/health` reports why, and chat returns a clear 503.

### Demo customers (synthetic)

| Customer | What to try |
|---|---|
| **Aarav Mehta** (CUST-1001): Savings XXXX4521 + Current XXXX7788 | "what's my balance" (two accounts), "and the amazon one yesterday?", "what is RZP*TECHNOVA", "why did uber charge me twice", "what's the ₹500 deduction", "send me a cheque book" → "yes" |
| **Priya Nair** (CUST-1002): Savings XXXX3192 | "send me a cheque book" → already has SR-700245 in Dispatched status |
| **Rohan Das** (CUST-1003): Basic Savings XXXX8812 | "send me a cheque book" → not eligible (BSBDA account) |

Tick **details** in the header to see which tools each reply used.

---

## Tests and evals

```bash
make test                                   # 29 tests, offline, < 1s
python scripts/eval_scenarios.py            # real-model scenarios against a running assistant
```

- **Unit/integration tests** drive the real orchestrator, tools, guards and an in-process mock bank. Only the model is replaced by a scripted stand-in (`assistant/llm/scripted.py`), so tests are deterministic. They cover balance and follow-up flows, blocked hallucinated figures, cheque book confirmation enforcement, idempotent retries, ineligible accounts and existing requests, cross-customer access attempts, invalid tool arguments, model outage and runaway tool loops.
- **Scenario evals** check the actual model's reasoning: varied phrasings, follow-ups, cryptic statement text, look-alike charges, the two-step cheque book flow, out-of-scope requests and a prompt-injection attempt against another customer's account. Each turn asserts which tools were called, which were *not*, and what the reply must or must not contain.

---

## Repository layout

```
assistant/            conversational backend (FastAPI, port 8000)
  main.py             HTTP API: session, chat, health; serves the UI
  agent.py            orchestrator: bounded reason–act loop, fallbacks
  tools.py            banking tools: schemas, validation, scoping, 2-phase cheque book
  grounding.py        deterministic check that reply figures came from the bank
  prompts.py          system prompt
  sessions.py         sessions, pending actions, evidence, rate limiting
  bank_client.py      HTTP client for core banking
  llm/                provider-neutral interface + Gemini, OpenAI-compatible, scripted adapters
mock_bank/            mock core-banking API (FastAPI, port 8001) + synthetic seed data
web/index.html        minimal chat UI (no build step)
tests/                pytest suite
scripts/              real-model scenario evals
docs/                 architecture diagram (SVG + PNG)
```

### API (assistant)

| Method | Path | Notes |
|---|---|---|
| `POST` | `/api/session` | `{customer_id}` → `{token}`. **Simulated** sign-in (see assumptions); disabled with `DEMO_LOGIN_ENABLED=false` |
| `POST` | `/api/chat` | `Authorization: Bearer <token>`, `{message}` → `{reply, outcome, turn, trace[]}` |
| `DELETE` | `/api/session` | sign out |
| `GET` | `/api/health` | liveness + model configuration status |

Interactive docs: http://localhost:8000/docs (assistant), http://localhost:8001/docs (mock bank).

---

## Design notes

### Decomposition

The solution is split along trust boundaries, not features:

- **Chat UI** (untrusted). Renders text only and holds an opaque session token.
- **Assistant service** (bank-controlled). Owns identity binding, orchestration and every safety guarantee. It has four parts:
  - API edge: auth, limits, per-session serialisation.
  - Orchestrator: the reason–act loop.
  - Tool layer: the only path to banking data.
  - Grounding guard.
- **Model provider** (third party). Proposes actions and words; it is never trusted for facts or authority.
- **Core banking** (system of record, mocked). It enforces customer scoping, eligibility and idempotency on its own side as well (defence in depth).

### How a turn works

1. The UI posts the message with the bearer token. The session resolves to exactly one `customer_id`.
2. The orchestrator sends the system prompt, the conversation window and seven tool schemas to the model.
3. The model either answers or requests tool calls. Each call is validated with pydantic and scoped: the customer id is injected from the session, never taken from the model, and account ids are checked against the session's accounts. The call is then executed against core banking.
4. Results go back to the model, and every number and reference in them is recorded as **evidence** for the session.
5. Steps 2–4 loop up to `MAX_AGENT_STEPS`. The final draft goes through the grounding guard. Any currency amount or `SR-`/`TXN-` reference not present in the evidence triggers one silent self-correction. If it still fails, a safe fallback is returned.

Routing is done by the model choosing tools from their descriptions. There is no intent classifier or keyword matching, so "what's left in my current account", "was I charged twice by Uber?" and "RZP*TECHNOVA??" all work. Follow-ups work because the full tool exchange stays in the conversation history.

### Key decisions and why

| Decision | Rationale |
|---|---|
| **Single agent with tools**, not multi-agent or intent-router | Three capabilities with shared context (a debit question after a balance check) fit one reasoning loop. Multiple agents would add hand-off latency and lose context for no gain at this scope. New capabilities are added as tools. |
| **Server-enforced invariants instead of prompt-only rules** | Prompts are advisory. Identity scoping, write confirmation and grounding live in code. That is what lets the system promise that figures are never invented by the model. |
| **Deterministic grounding guard** | It is cheap, explainable and catches the failure that matters most in banking: a confidently wrong number. Arithmetic is done in tools (`total_amount`) so legitimate figures always appear verbatim in the evidence. |
| **Two-phase cheque book order with turn-gap check** | `prepare` returns a summary and a pending id. `submit` is rejected unless the customer has replied since the summary was shown. This stops the model, or text injected via a transaction descriptor, from proposing and executing in one step. The pending id is the idempotency key, so retries never create duplicate orders. |
| **Provider-neutral LLM interface** | Banks often mandate a specific provider or on-prem model. Gemini (API key or Vertex) and any OpenAI-compatible endpoint (including local Ollama) work by configuration. Gemini's raw content is round-tripped so thought signatures survive multi-step tool use. |
| **Python + FastAPI, two services** | Fast to build and test, async I/O for concurrent LLM and bank calls. The mock bank is a separate service so the trust boundary and network failure modes are real rather than simulated in-process. |
| **Scripted-model tests + real-model evals** | Plumbing and guarantees are tested deterministically. Reasoning quality is measured separately, because it is probabilistic and depends on the model. |

### Assumptions

- **Authentication happens upstream.** The customer is already signed in to the bank's app. `POST /api/session` stands in for exchanging the app's IdP token (e.g. OIDC) for a conversation session; it is a demo affordance behind a flag.
- Indian retail bank context: INR, NEFT/UPI/NACH channels, and cheque books of 25 leaves (free) or 50 leaves (₹118 incl. GST), delivered to the registered address in about 7 days.
- "Explain a debit" means explaining it from the bank's own transaction and enrichment data. Raising disputes, blocking cards and fraud handling are out of scope; the assistant points the customer to the app or helpline.
- Only customer-initiated delivery to the registered address is supported. Address changes are a separate, higher-assurance journey.
- The data is entirely synthetic. Dates are generated relative to service start so "yesterday" and "last week" always resolve.

### Security considerations

- **Identity:** the model never chooses whose data to read. The customer id comes from the session, account ids are checked against it, and core banking scopes again and returns 404 for foreign resources (no existence leak).
- **Prompt injection:** tool data is labelled untrusted in the prompt. More importantly, injected text cannot cross the server-side controls: it cannot change identity, skip write confirmation, or get an invented figure past the guard.
- **Data minimisation to the model provider:** only masked account numbers and masked addresses ever leave core banking. Full account numbers and credentials are never sent to the model.
- **Service-to-service auth** between the assistant and core banking uses `X-Service-Key`; in production this would be mTLS or workload identity. Core banking is not exposed publicly.
- **Edge controls:** message length limit, per-session rate limit, session TTL, one in-flight turn per session, `no-store` responses. The UI renders replies as text (no HTML injection).
- **Logging** records tool names, outcomes and latency, not message contents or balances.

### Failure handling

| Failure | Behaviour |
|---|---|
| Model timeout / error | Friendly fallback pointing to the app. The turn is closed cleanly so the next turn works. |
| Core banking down | The tool returns `bank_unavailable`. The model apologises, and the guard prevents any guessed figure. |
| Model invents a figure or reference | One self-correction, then a safe fallback. Logged as `ungrounded_reply`. |
| Bad tool arguments | A structured error goes back to the model, which corrects itself (tested). |
| Runaway tool loop | Capped by `MAX_AGENT_STEPS`. |
| Duplicate submit / client retry | Idempotent at core banking; one open request per account is enforced. |

### What I'd do next for production

- Persist sessions and pending actions in Redis, and run the assistant stateless behind a load balancer.
- Replace the demo login with IdP token exchange, and add step-up authentication for write actions above a risk threshold.
- Add OpenTelemetry tracing per turn and tool, plus dashboards for containment rate, fallback rate, grounding-guard hits, latency and cost per conversation. These are the metrics that tie back to the 390k-call business case.
- Hand off to a human agent with the conversation transcript when the assistant can't help or the customer asks.
- Run the scenario evals in CI against the pinned model version, adding red-team prompts and multilingual or Hinglish phrasing.
- Stream responses (SSE) for perceived latency, and add PII redaction before model calls if free-text descriptors carry personal data.
