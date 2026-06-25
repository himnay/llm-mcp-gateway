package com.org.llm.mcpgateway.security;

import com.org.llm.mcpgateway.exception.GatewayException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Fetches and caches the Keycloak client-credentials token this gateway presents to
 * OAuth2-protected backends, refreshing 60 seconds before expiry to avoid mid-request failures.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KeycloakTokenService {

    private static final long REFRESH_BUFFER_SECONDS = 60;

    private final KeycloakOAuth2Properties properties;
    private final RestClient restClient = RestClient.create();
    private final ReentrantLock lock = new ReentrantLock();

    private volatile String cachedToken;
    private volatile Instant expiresAt = Instant.EPOCH;

    public String getToken() {
        if (isValid()) {
            return cachedToken;
        }
        lock.lock();
        try {
            if (isValid()) {
                return cachedToken;
            }
            return fetchToken();
        } finally {
            lock.unlock();
        }
    }

    private boolean isValid() {
        return cachedToken != null
                && Instant.now().isBefore(expiresAt.minusSeconds(REFRESH_BUFFER_SECONDS));
    }

    private String fetchToken() {
        log.debug("Fetching new Keycloak OAuth2 client-credentials token");
        String body = "grant_type=client_credentials"
                + "&client_id=" + properties.getClientId()
                + "&client_secret=" + properties.getClientSecret();

        KeycloakTokenResponse response = restClient.post()
                .uri(properties.getTokenUri())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(body)
                .retrieve()
                .body(KeycloakTokenResponse.class);

        if (response == null || response.accessToken() == null) {
            throw new GatewayException("Keycloak token response was null or missing access_token");
        }

        cachedToken = response.accessToken();
        expiresAt = Instant.now().plusSeconds(response.expiresIn());
        log.info("Keycloak OAuth2 token refreshed for gateway — expires in {}s", response.expiresIn());
        return cachedToken;
    }
}
