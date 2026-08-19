# <span style="color:hsl(256,80%,58%)">OAuth 2.1 Setup for `llm-mcp-gateway` via Keycloak</span>

This gateway is both an **OAuth2 resource server** (it validates tokens from callers like
`llm-mcp-client`) and an **OAuth2 client** (it authenticates itself to `mcp-server-deployment-service`,
the one backend that's already migrated to OAuth2.1 — see `../llm-mcp/KEYCLOAK_OAUTH2.md`).

```
llm-mcp-client  --(client_credentials, scope=gateway-invoke)-->  Keycloak
llm-mcp-client  --(Bearer <JWT>)-->  llm-mcp-gateway                         [resource server]
llm-mcp-gateway --(client_credentials, scope=deployment-invoke)-->  Keycloak [client]
llm-mcp-gateway --(Bearer <JWT>)-->  mcp-server-deployment-service           [resource server]
```

**Important:** this gateway must point at the *same* Keycloak instance and realm
(`org-mcp`) that the rest of the `llm-mcp` fleet uses — tokens are only valid against the
issuer that signed them. Do not stand up a second Keycloak for this repo; reuse the one
started by `../llm-mcp` (`docker compose up -d keycloak`).

## <span style="color:hsl(34,80%,58%)">1. Add a `gateway-invoke` client scope to the shared `org-mcp` realm</span>

In the Keycloak admin console (http://localhost:8180, `admin`/`admin`):

1. **Client scopes** → **Create client scope**. Name: `gateway-invoke`. Type: **Default**.
   Protocol: `openid-connect`. Include In Token Scope: **On**. → **Save**.
2. Open the new scope → **Mappers** → **Add mapper** → **By configuration** → **Audience**.
   Name: `gateway-audience`. Included Custom Audience: `mcp-gateway`. Add to access token:
   **On**. → **Save**.

## <span style="color:hsl(171,80%,58%)">2. Give callers the `gateway-invoke` scope</span>

Any client that needs to call this gateway (e.g. `llm-mcp-client`) must have `gateway-invoke`
added as a **Default** (or at least optional, requested explicitly) client scope:

**Clients** → `llm-mcp-client` → **Client scopes** tab → **Add client scope** → select
`gateway-invoke` → **Add** as **Default**.

Verify:

```bash
curl -s http://localhost:8180/realms/org-mcp/protocol/openid-connect/token \
  -d grant_type=client_credentials \
  -d client_id=llm-mcp-client \
  -d client_secret=llm-mcp-client-secret | jq -r .access_token | cut -d. -f2 | base64 -d | jq .
```

Confirm `scope` contains `gateway-invoke` and `aud` contains `mcp-gateway`.

## <span style="color:hsl(309,80%,58%)">3. Create a service-account client for the gateway itself</span>

The gateway needs its own client identity to call `deployment-service` on its own behalf:

1. **Clients** → **Create client**. Client ID: `llm-mcp-gateway`. Client authentication: **On**.
   Enable only **Service accounts roles**. → **Save**.
2. **Credentials** tab → copy the client secret (or set it to `llm-mcp-gateway-secret` for local
   dev, matching the default in `application.yaml`).
3. **Client scopes** tab → add `deployment-invoke` (created in `../llm-mcp/KEYCLOAK_OAUTH2.md`)
   as a **Default** scope, so tokens minted for `llm-mcp-gateway` carry `aud: deployment-service`.

## <span style="color:hsl(86,80%,58%)">4. Wire the URLs</span>

| URL        | Used by                                                  | Property                                               | Points at                                          |
|------------|----------------------------------------------------------|--------------------------------------------------------|----------------------------------------------------|
| Issuer URI | This gateway (resource server)                           | `spring.security.oauth2.resourceserver.jwt.issuer-uri` | `.../realms/org-mcp`                               |
| Token URI  | This gateway (OAuth2 client, calling deployment-service) | `gateway.oauth2.token-uri`                             | `.../realms/org-mcp/protocol/openid-connect/token` |

```yaml
# application.yaml (defaults shown; override via env vars in docker-compose.yml)
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${MCP_OAUTH2_ISSUER_URI:http://localhost:8180/realms/org-mcp}
gateway:
  oauth2:
    token-uri: ${MCP_OAUTH2_TOKEN_URI:http://localhost:8180/realms/org-mcp/protocol/openid-connect/token}
    client-id: ${MCP_OAUTH2_CLIENT_ID:llm-mcp-gateway}
    client-secret: ${MCP_OAUTH2_CLIENT_SECRET:llm-mcp-gateway-secret}
```

## <span style="color:hsl(224,80%,58%)">5. Local dev without Keycloak</span>

Set `GATEWAY_OAUTH2_ENABLED=false` (`gateway.security.oauth2.enabled=false`) to drop the inbound
resource-server filter chain entirely — a permissive chain takes its place so the app still
starts. Outbound calls to `deployment-service` will then need `deployment` removed from
`gateway.oauth2-backends`, or `deployment-service` itself run with its own OAuth2 disabled.
This is also the test-profile default (`src/test/resources/application.yaml`).

## <span style="color:hsl(1,80%,58%)">Rolling this out as more backends adopt OAuth2</span>

Today only `deployment-service` is an OAuth2 resource server; every other backend
(`ticket`, `notification`, `hr`, `github`, `gmail`, `travel`) still uses the legacy shared
bearer token (`MCP_AUTH_TOKEN`), which this gateway forwards automatically to any connection
name not listed in `gateway.oauth2-backends`. To migrate another backend:

1. Keycloak: add a `<name>-invoke` scope with an `aud: <name>-service` mapper, add it as a
   Default scope on the `llm-mcp-gateway` client.
2. That backend: copy `OAuth2ResourceServerConfig` from `mcp-server-deployment-service`.
3. This gateway: add `<name>` to `gateway.oauth2-backends` in `application.yaml`.
