# MCP Gateway — `llm-mcp-gateway`

A single secure front door for the org's MCP servers. Upstream callers (`llm-mcp-client`, an
IDE agent, any MCP client) connect to **one** Streamable HTTP endpoint (`:8088/mcp`); the
gateway internally fans out to every backend MCP server, aggregates their tools into one
catalog, and centralises everything those backends would otherwise each have to reimplement:
authentication, rate limiting, circuit breaking, audit logging and output capping.

```
                                   ┌──────────────────────┐
                                   │   llm-mcp-gateway     │
[llm-mcp-client] ──(JWT, /mcp)──▶ │  (this service, :8088) │
[IDE / other agent] ──────────▶   │                        │
                                   │  BackendRegistry       │──ping/listTools──▶ ticket-service        (:8081)
                                   │  GatewayToolCallback-  │──ping/listTools──▶ deployment-service (OAuth2) (:8082)
                                   │  Provider (circuit     │──ping/listTools──▶ notification-service    (:8083)
                                   │  breaker + retry +     │──ping/listTools──▶ hr-service               (:8084)
                                   │  rate limit + audit)   │──ping/listTools──▶ github-service            (:8085)
                                   │                        │──ping/listTools──▶ gmail-service             (:8086)
                                   └──────────────────────┘──ping/listTools──▶ travel-service             (:8087)
```

This mirrors the "secure front door / hub-and-spoke" MCP gateway pattern: reverse-proxy
protection for the backends, centralized authn/authz, tool-based routing, and centralized
observability — rather than every backend re-implementing its own `McpAuthFilter`,
`RateLimiter` and `OutputSizeCapUtil`.

---

## How tool aggregation works

`BackendRegistry` connects to every backend configured under
`spring.ai.mcp.client.streamable-http.connections` at startup (best-effort — an unreachable
backend is skipped, not fatal) and builds a tool-name → backend-name map purely from each
backend's own `listTools()` response. `GatewayToolCallbackProvider` then wraps every discovered
tool with per-backend Resilience4j circuit breaker + retry, a hard timeout, per-user write-tool
rate limiting, audit logging and output-size capping, and exposes the result as a
`ToolCallbackProvider` bean — which `spring-ai-starter-mcp-server-webmvc` automatically picks up
and serves as **this gateway's own** MCP tool catalog at `/mcp`. No tool-to-backend mapping is
hand-maintained: add a backend connection in `application.yaml` and its tools appear
automatically.

Three more catalog-shaping features, lifted from how Uber describes their MCP Gateway/Registry:

- **Description overrides** (`gateway.tool-overrides`) — replace a backend's generic tool
  description with workflow-specific wording, without touching the backend.
- **Derived tools** (`gateway.derived-tools`) — a new tool name wrapping an existing one with
  fixed arguments merged into every call (e.g. `createBugIssue` = `createIssue` +
  `labels: bug`), giving agents a narrower, purpose-built entry point.
- **Tool-quality metrics** — `ToolQualityRegistry` aggregates call count / success rate / p95
  latency per tool from the same audit call site, surfaced at `GET /gateway/tools/quality` (and
  as Micrometer timers for Prometheus/Grafana) so an agent-builder UI can rank tools by
  reliability.

PII/secret redaction (`PiiRedactor`) also scrubs every tool result — emails, SSNs, credit cards,
API keys, AWS keys, bearer tokens, private key blocks — before it's capped and returned,
defence-in-depth for backends that don't sanitize their own output.

Prompt injection protection (`PromptInjectionGuard`) guards against indirect injection via MCP
tool call arguments — every `call(String toolInput)` is checked against a configurable regex
catalogue before dispatch; matched calls are rejected immediately with a JSON error without
reaching the backend.

---

## Prompt Injection Security

### Why tool argument injection matters

In an LLM-driven workflow, the LLM generates tool call arguments based on its conversation
context. If an attacker can poison the context (via a crafted user message or a compromised
upstream result), the LLM may generate arguments containing injected instructions. Those
arguments arrive at the MCP gateway before being forwarded to a backend that acts on them.

### Defence layers

**Layer 1 — Tool argument injection guard (`PromptInjectionGuard`)**
Every `call(String toolInput)` on every `ResilientToolCallback` passes through
`PromptInjectionGuard.isInputSafe()` before dispatch. If an injection pattern matches, the
call is rejected immediately with a JSON error — the backend never sees it.

Patterns are externalised in `InjectionGuardProperties`
(`app.security.injection-guard.patterns`) so new attack signatures can be added in
configuration without code changes.

**Layer 2 — PII redaction (`PiiRedactor`)**
Tool results are scanned before being returned to the caller. Detected PII (email, SSN, IBAN,
API keys, bearer tokens, credit cards) is replaced with typed placeholders.

**Layer 3 — OAuth2 authentication**
Only callers with a valid Keycloak JWT for the `org-mcp` realm carrying the `gateway-invoke`
scope and `mcp-gateway` audience may invoke the gateway.

**Layer 4 — Write-tool rate limiting**
Tools whose name contains action keywords (create, update, delete, deploy, send, etc.) are
subject to a stricter per-user write-rate limit to slow any automated injection attack chain.

**Layer 5 — SSRF protection (`UrlAllowlistValidator`)**
Backend MCP server URLs (`TICKET_SERVICE_URL` … `TRAVEL_SERVICE_URL`) are validated at startup by `UrlAllowlistValidator`. Any URL that resolves to a loopback address, link-local range, or private RFC-1918 subnet not explicitly in the allowlist is rejected and the gateway refuses to start. This prevents a misconfigured or injected backend URL from redirecting tool calls to internal infrastructure.

### Enabling / disabling

```yaml
app:
  security:
    injection-guard:
      enabled: ${INJECTION_GUARD_ENABLED:true}   # set false for local dev/testing only
```

### Adding new injection patterns

```yaml
app:
  security:
    injection-guard:
      patterns:
        - "(?i)your new pattern here"
```

No code change required. Any value set here replaces the default list entirely.

---

## Best Practices Applied

| Practice | Status | Notes |
|---|---|---|
| Hub-and-spoke aggregation | ✅ | `BackendRegistry` + `GatewayToolCallbackProvider` — one MCP endpoint fronts N backends |
| OAuth2.1 inbound auth | ✅ | `GatewaySecurityConfig` — Keycloak JWT, `SCOPE_gateway-invoke` authority, `aud: mcp-gateway` |
| OAuth2.1 / legacy outbound auth | ✅ | `McpClientSecurityConfig` — per-connection: Keycloak client-credentials (`gateway.oauth2-backends`) or shared static token |
| Acting-user propagation | ✅ | Resolved from the inbound JWT (`preferred_username`/`sub`) when OAuth2 is enabled, else `X-Acting-User`; forwarded downstream as `X-Acting-User` |
| Correlation id / tracing | ✅ | `GatewayAuthContextFilter` — `X-Request-ID` read-or-generate, MDC, forwarded downstream; Micrometer Tracing → OTLP → Tempo |
| Rate limiting | ✅ | `GatewayRateLimiter` — Redis sliding window per user, plus a stricter per-user limit on write tools |
| Circuit breaker / retry | ✅ | Resilience4j, one instance per backend, auto-created on first use — no per-backend code |
| Audit logging | ✅ | `ToolAuditLog` — every tool call logs user/backend/tool/duration/outcome |
| Output truncation | ✅ | `OutputSizeCapUtil` caps tool results at `gateway.max-tool-result-chars` |
| PII/secret redaction | ✅ | `PiiRedactor` scrubs tool results (email/SSN/credit-card/IBAN/IP/phone/API-key/AWS-key/bearer-token/private-key) before capping |
| Prompt injection guard | ✅ | `PromptInjectionGuard` rejects tool calls whose arguments match injection/jailbreak patterns — config-driven, no code change needed |
| Tool-quality metrics | ✅ | `ToolQualityRegistry` — call count, success rate, p95 latency per tool, via `GET /gateway/tools/quality` and Micrometer |
| Derived tools / description overrides | ✅ | `gateway.tool-overrides` / `gateway.derived-tools` — config-driven catalog shaping, no backend changes needed |
| Centralised error handling | ✅ | `GlobalExceptionHandler` (`@RestControllerAdvice`) — uniform `{status, error, message, details, timestamp}` body for the admin REST API |
| Centralised management | ✅ | `GET /gateway/backends` — per-backend status + tool catalog; `GatewayBackendsHealthIndicator` on `/actuator/health` |
| Externalised config | ✅ | `GatewayProperties`, `GatewayOAuth2SecurityProperties`, `KeycloakOAuth2Properties`, `GatewayRateLimiterProperties` — all env-overridable |
| Structured logging | ✅ | SLF4J/Lombok `@Slf4j`, application-tagged via `spring.application.name` |
| Prometheus / Grafana / Tempo | ✅ | `micrometer-registry-prometheus` + OTLP tracing, full stack in `docker-compose.yml` |
| Non-root container | ✅ | Multi-stage Dockerfile, dedicated `spring:spring` user on a `jre`-only runtime image |

## Design Patterns (GoF)

| Pattern | Where | Role |
|---|---|---|
| **Facade** | `BackendRegistry` | Hides per-backend MCP client lifecycle (connect, listTools, ping) behind a simple name→client/tool view |
| **Decorator** | `GatewayToolCallbackProvider.ResilientToolCallback` | Wraps each delegate `ToolCallback` with circuit breaker, retry, timeout, audit and output capping without changing its interface |
| **Registry** | `BackendRegistry`, Resilience4j `CircuitBreakerRegistry`/`RetryRegistry` | Resolve a named instance (backend, breaker, retry) without the caller knowing how it was constructed |
| **Chain of Responsibility** | Servlet `FilterChain` (Spring Security chain → `GatewayAuthContextFilter`) | Authn → correlation/acting-user/rate-limit → MCP dispatch, each link handles or passes on |
| **Strategy** | `McpClientSecurityConfig`'s per-connection `McpSyncHttpClientRequestCustomizer` | OAuth2 vs. static-bearer outbound auth selected per backend connection name |
| **Decorator** | `DescriptionOverrideToolCallback`, `DerivedToolCallback` | Layer description overrides / fixed-argument derivation onto an already-wrapped tool without touching the original |
| **Template Method (framework)** | `GatewayAuthContextFilter extends OncePerRequestFilter` | Framework skeleton calls `doFilterInternal` |
| **Singleton** | All Spring beans | One shared, stateless instance per container |

## Configuration

| Property / Env Var | Default | Description |
|---|---|---|
| `SERVER_PORT` | `8088` | HTTP port |
| `TICKET_SERVICE_URL` … `TRAVEL_SERVICE_URL` | `http://localhost:808{1..7}` | Backend MCP server base URLs |
| `GATEWAY_OAUTH2_ENABLED` (`gateway.security.oauth2.enabled`) | `true` | Inbound OAuth2 kill switch — `false` for local dev/tests |
| `gateway.security.oauth2.required-scope` | `gateway-invoke` | Scope every inbound caller's token must carry |
| `gateway.security.oauth2.required-audience` | `mcp-gateway` | Required `aud` claim |
| `MCP_OAUTH2_ISSUER_URI` | `http://localhost:8180/realms/org-mcp` | Keycloak issuer (inbound JWT validation) |
| `MCP_OAUTH2_TOKEN_URI` / `_CLIENT_ID` / `_CLIENT_SECRET` | see `application.yaml` | Outbound client-credentials, used to call OAuth2-protected backends |
| `gateway.oauth2-backends` | `[deployment]` | Backend connection names that require an OAuth2 bearer token instead of the static one |
| `MCP_AUTH_TOKEN` (`gateway.static-auth-token`) | *(empty)* | Shared bearer token forwarded to non-OAuth2 backends |
| `DEFAULT_USER` (`gateway.default-user`) | `system` | Fallback acting user |
| `gateway.rate-limit-per-minute` | `120` | Per-user request cap on `/mcp/**` |
| `gateway.write-rate-limit-per-minute` | `10` | Stricter per-user cap on write/destructive tools |
| `gateway.tool-timeout-seconds` | `30` | Hard timeout per backend tool call |
| `gateway.max-tool-result-chars` | `8000` | Truncation threshold for tool results |
| `gateway.pii.enabled` | `true` | PII/secret redaction kill switch |
| `gateway.pii.patterns` | see `PiiRedactionProperties` | `type -> regex` map; any entry replaces the corresponding default |
| `INJECTION_GUARD_ENABLED` (`app.security.injection-guard.enabled`) | `true` | Tool argument injection guard kill switch |
| `app.security.injection-guard.patterns` | see `InjectionGuardProperties` | Regex list; any value replaces the full default list |
| `gateway.tool-overrides.<tool>.description` | *(none)* | Replace a tool's description for callers, without changing the backend |
| `gateway.derived-tools.<name>.base-tool` / `.description` / `.fixed-arguments` | *(none)* | Define a new tool wrapping `base-tool` with fixed arguments merged into every call |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | Backing store for the rate limiter |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4318` | OTLP traces endpoint (Tempo) |

See [`KEYCLOAK_SETUP.md`](KEYCLOAK_SETUP.md) for the Keycloak realm/client/scope setup this
gateway needs (it shares the `org-mcp` realm with the rest of the `llm-mcp` fleet).

---

## Running

This gateway expects the backend MCP servers from `../llm-mcp` to be reachable (run them via
`./mvnw spring-boot:run` per service, or `docker compose up` in that repo) and, if
`GATEWAY_OAUTH2_ENABLED=true`, a running Keycloak from that same repo (`docker compose up -d keycloak`).

```bash
# local dev, no Keycloak
GATEWAY_OAUTH2_ENABLED=false ./mvnw spring-boot:run

# full stack: this gateway + Redis + Prometheus + Grafana + Tempo
docker compose up --build
```

Grafana: http://localhost:3001 (admin/admin). Prometheus: http://localhost:9091.

## curl Commands

> MCP requests are JSON-RPC 2.0 over the Streamable HTTP endpoint `/mcp`. Replace `$TOKEN` with
> a Keycloak access token carrying the `gateway-invoke` scope (see `KEYCLOAK_SETUP.md`), or omit
> the header entirely when running with `GATEWAY_OAUTH2_ENABLED=false`.

### List the aggregated tool catalog

```bash
curl -s http://localhost:8088/mcp \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

### Call a tool (routed to whichever backend owns it)

```bash
curl -s http://localhost:8088/mcp \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $TOKEN" \
  -H 'X-Acting-User: jane.doe' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"getRepository","arguments":{"owner":"spring-projects","repo":"spring-boot"}}}'
```

### Backend catalog / status (admin endpoint)

```bash
curl -s http://localhost:8088/gateway/backends -H "Authorization: Bearer $TOKEN" | jq
curl -s http://localhost:8088/gateway/backends/github -H "Authorization: Bearer $TOKEN" | jq
```

### Tool quality (call count / success rate / p95 latency)

```bash
curl -s http://localhost:8088/gateway/tools/quality -H "Authorization: Bearer $TOKEN" | jq
```

### Actuator

```bash
curl -s http://localhost:8088/actuator/health | jq
curl -s http://localhost:8088/actuator/prometheus | head -40
```
