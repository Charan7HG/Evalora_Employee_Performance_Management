package com.evalora.backend.security;

import com.evalora.backend.entity.User;
import com.evalora.backend.repository.UserRepository;
import com.evalora.backend.util.EmailNormalizer;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;

/**
 * Reads {@code Authorization: Bearer <token>} and, when the token is valid and
 * the account is usable, puts an {@link EvaloraPrincipal} into the security
 * context.
 *
 * <p>Everything that matters is re-read from the database on every request:
 * the role, {@code enabled}, {@code locked_until} and {@code token_version}.
 * The role claim inside the token is never used for authorization, so a role
 * change or a disabled account takes effect immediately. A token whose version
 * is older than the account's current version is rejected, which invalidates
 * every session after a password change.</p>
 *
 * <p>When the token is missing the filter simply continues; the request then
 * reaches the entry point and gets a 401 if the endpoint is protected. When the
 * token is invalid, expired, belongs to an unknown account or the account is
 * disabled/locked, no authentication is created and the reason is stored as a
 * request attribute for {@link RestAuthenticationEntryPoint}.</p>
 *
 * <p>Registered inside the Spring Security chain only (not as a servlet filter
 * bean), so it runs exactly once per request.</p>
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public JwtAuthenticationFilter(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = resolveToken(request);
        if (token == null) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            String email = EmailNormalizer.normalize(jwtService.extractEmail(token));
            User user = userRepository.findByEmailIgnoreCase(email).orElse(null);

            if (user == null) {
                SecurityContextHolder.clearContext();
                request.setAttribute(SecurityErrorWriter.ERROR_ATTRIBUTE, SecurityErrorWriter.INVALID_TOKEN);
            } else if (!user.isEnabled() || isLocked(user)) {
                SecurityContextHolder.clearContext();
                request.setAttribute(SecurityErrorWriter.ERROR_ATTRIBUTE, SecurityErrorWriter.ACCOUNT_UNAVAILABLE);
            } else if (jwtService.extractTokenVersion(token) != user.getTokenVersion()) {
                // A password change bumps the account's token version, which
                // invalidates every token issued before the change.
                SecurityContextHolder.clearContext();
                request.setAttribute(SecurityErrorWriter.ERROR_ATTRIBUTE, SecurityErrorWriter.INVALID_TOKEN);
            } else {
                EvaloraPrincipal principal = EvaloraPrincipal.from(user);
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        } catch (JwtException | IllegalArgumentException ex) {
            SecurityContextHolder.clearContext();
            request.setAttribute(SecurityErrorWriter.ERROR_ATTRIBUTE, SecurityErrorWriter.INVALID_TOKEN);
        }

        filterChain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    private static boolean isLocked(User user) {
        return user.getLockedUntil() != null && user.getLockedUntil().toInstant().isAfter(Instant.now());
    }
}
