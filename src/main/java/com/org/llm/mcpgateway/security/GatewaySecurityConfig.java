package com.org.llm.mcpgateway.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;

/**
 * Inbound OAuth2.1 resource server protecting this gateway's own {@code /mcp/**} and
 * {@code /gateway/**} endpoints — every caller (e.g. {@code llm-mcp-client}, an IDE agent)
 * must present a Keycloak-issued JWT carrying the {@code gateway-invoke} scope and an
 * {@code aud} claim containing {@code mcp-gateway}, exactly mirroring how
 * {@code mcp-server-deployment-service} protects itself one hop downstream.
 *
 * <p>{@code jwtDecoder} is wrapped in {@link SupplierJwtDecoder} so the Keycloak issuer-discovery
 * HTTP call happens lazily on first token validation, not eagerly at context startup.
 *
 * <p>When {@code gateway.security.oauth2.enabled=false} (local dev / tests without Keycloak),
 * {@link #permissiveFilterChain(HttpSecurity)} registers instead so Spring Boot's own
 * authenticate-everything default chain never activates — exactly one {@code SecurityFilterChain}
 * bean always exists.
 */
@Configuration
public class GatewaySecurityConfig {

    private static final String[] PROTECTED_PATHS = {"/mcp/**", "/mcp", "/gateway/**"};

    /** Defines the oauth2 resource server filter chain bean. */
    @Bean
    @ConditionalOnProperty(prefix = "gateway.security.oauth2", name = "enabled", matchIfMissing = true)
    public SecurityFilterChain oauth2ResourceServerFilterChain(
            HttpSecurity http, GatewayOAuth2SecurityProperties properties) throws Exception {

        String requiredAuthority = "SCOPE_" + properties.getRequiredScope();

        http
                .securityMatcher(PROTECTED_PATHS)
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().hasAuthority(requiredAuthority))
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {
                }));
        return http.build();
    }

    /** Defines the permissive filter chain bean. */
    @Bean
    @ConditionalOnProperty(prefix = "gateway.security.oauth2", name = "enabled", havingValue = "false")
    public SecurityFilterChain permissiveFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    /** Defines the jwt decoder bean. */
    @Bean
    @ConditionalOnProperty(prefix = "gateway.security.oauth2", name = "enabled", matchIfMissing = true)
    public JwtDecoder jwtDecoder(
            org.springframework.core.env.Environment env, GatewayOAuth2SecurityProperties properties) {
        String issuerUri = env.getRequiredProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri");
        String requiredAudience = properties.getRequiredAudience();
        return new SupplierJwtDecoder(() -> buildDecoder(issuerUri, requiredAudience));
    }

    private NimbusJwtDecoder buildDecoder(String issuerUri, String requiredAudience) {
        NimbusJwtDecoder jwtDecoder = JwtDecoders.fromIssuerLocation(issuerUri);

        OAuth2TokenValidator<Jwt> withIssuer = JwtValidators.createDefaultWithIssuer(issuerUri);
        OAuth2TokenValidator<Jwt> withAudience = new JwtClaimValidator<List<String>>(
                "aud", audience -> audience != null && audience.contains(requiredAudience));
        jwtDecoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(withIssuer, withAudience));

        return jwtDecoder;
    }
}
