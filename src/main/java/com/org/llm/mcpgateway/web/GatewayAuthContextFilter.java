package com.org.llm.mcpgateway.web;

import com.org.llm.mcpgateway.config.GatewayProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Runs after Spring Security's filter chain has already authenticated the request (a plain
 * {@code @Component} filter is ordered after the security {@code FilterChainProxy} by default).
 * Responsible for:
 * <ol>
 *   <li>Correlation id — read {@code X-Request-ID} or generate one, set in MDC + response header.</li>
 *   <li>Acting user — when OAuth2 authenticated this request, the JWT's {@code preferred_username}
 *       (falling back to {@code sub}) is the source of truth; otherwise {@code X-Acting-User} is
 *       trusted (dev/test mode only, since OAuth2 is disabled there).</li>
 *   <li>Per-user rate limiting on {@code /mcp/**}.</li>
 * </ol>
 * Always clears {@link RequestContext} in a finally block.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GatewayAuthContextFilter extends OncePerRequestFilter {

    private static final String ACTING_USER_HEADER = "X-Acting-User";
    private static final String REQUEST_ID_HEADER = "X-Request-ID";

    private final GatewayProperties properties;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<GatewayRateLimiter> rateLimiterProvider;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {

        String path = request.getRequestURI();
        if (isPermitted(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        String correlationId = request.getHeader(REQUEST_ID_HEADER);
        if (!StringUtils.hasText(correlationId)) {
            correlationId = UUID.randomUUID().toString();
        }
        org.slf4j.MDC.put("requestId", correlationId);
        response.setHeader(REQUEST_ID_HEADER, correlationId);

        String actingUser = resolveActingUser(request);

        GatewayRateLimiter rateLimiter = rateLimiterProvider.getIfAvailable();
        if (path.startsWith("/mcp") && rateLimiter != null
                && !rateLimiter.tryAcquire(actingUser, properties.getRateLimitPerMinute())) {
            log.warn("Rate limit exceeded | user={} path={}", actingUser, path);
            writeError(response, HttpStatus.TOO_MANY_REQUESTS,
                    "Rate limit exceeded. Maximum " + properties.getRateLimitPerMinute() + " requests per minute.");
            return;
        }

        RequestContext.set(actingUser, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            RequestContext.clear();
            org.slf4j.MDC.remove("requestId");
        }
    }

    private String resolveActingUser(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            Jwt jwt = jwtAuth.getToken();
            String username = jwt.getClaimAsString("preferred_username");
            if (StringUtils.hasText(username)) {
                return username;
            }
            String subject = jwt.getSubject();
            if (StringUtils.hasText(subject)) {
                return subject;
            }
        }
        String headerUser = request.getHeader(ACTING_USER_HEADER);
        return StringUtils.hasText(headerUser) ? headerUser.trim() : properties.getDefaultUser();
    }

    private boolean isPermitted(String path) {
        return path.startsWith("/actuator/");
    }

    private void writeError(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        body.put("timestamp", Instant.now().toEpochMilli());
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), body);
    }
}
