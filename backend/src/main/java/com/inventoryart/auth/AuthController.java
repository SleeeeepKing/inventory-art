package com.inventoryart.auth;

import com.inventoryart.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.Duration;
import java.util.Arrays;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private static final String COOKIE = "refresh_token";
    private final AuthService service; private final AppProperties properties;
    public AuthController(AuthService service, AppProperties properties) { this.service=service; this.properties=properties; }
    @PostMapping("/login") public ResponseEntity<AuthDtos.AuthResponse> login(@Valid @RequestBody AuthDtos.LoginRequest request, HttpServletRequest http) {
        AuthService.Session session = service.login(request.username(), request.password(), clientIp(http), http.getHeader("User-Agent")); return response(session);
    }
    @PostMapping("/refresh") public ResponseEntity<AuthDtos.AuthResponse> refresh(@CookieValue(name=COOKIE, required=false) String token, HttpServletRequest http) {
        validateOrigin(http);
        return response(service.refresh(token, clientIp(http), http.getHeader("User-Agent")));
    }
    @PostMapping("/logout") public ResponseEntity<Void> logout(@CookieValue(name=COOKIE, required=false) String token, HttpServletRequest http) {
        validateOrigin(http);
        service.logout(token); return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString()).build();
    }
    private ResponseEntity<AuthDtos.AuthResponse> response(AuthService.Session session) {
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie(session.refreshToken(), Duration.ofDays(properties.getJwt().getRefreshTokenDays())).toString()).body(session.response());
    }
    private ResponseCookie cookie(String value, Duration maxAge) { return ResponseCookie.from(COOKIE, value).httpOnly(true).secure(properties.getSecurity().isCookieSecure()).sameSite("Lax").path("/api/v1/auth").maxAge(maxAge).build(); }
    private String clientIp(HttpServletRequest request) { String forwarded=request.getHeader("X-Forwarded-For"); return forwarded == null ? request.getRemoteAddr() : forwarded.split(",")[0].trim(); }
    private void validateOrigin(HttpServletRequest request) {
        String origin=request.getHeader("Origin");
        if(origin!=null&&Arrays.stream(properties.getSecurity().getCorsAllowedOrigins().split(",")).map(String::trim).noneMatch(origin::equals))
            throw new com.inventoryart.exception.BusinessException("INVALID_ORIGIN","Origin is not allowed",org.springframework.http.HttpStatus.FORBIDDEN);
    }
}
