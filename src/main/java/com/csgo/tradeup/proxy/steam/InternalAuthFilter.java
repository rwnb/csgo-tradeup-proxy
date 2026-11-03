package com.csgo.tradeup.proxy.steam;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class InternalAuthFilter extends OncePerRequestFilter {
    private final byte[] key;
    public InternalAuthFilter(@Value("${steam.internal-key:}") String key) { this.key = key.getBytes(StandardCharsets.UTF_8); }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !request.getRequestURI().startsWith("/internal/steam/"); }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String token = request.getHeader("X-Internal-Key");
        if (key.length < 32 || token == null || !MessageDigest.isEqual(key, token.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(401); response.setHeader("Cache-Control", "no-store"); return;
        }
        response.setHeader("Cache-Control", "no-store"); chain.doFilter(request, response);
    }
}
