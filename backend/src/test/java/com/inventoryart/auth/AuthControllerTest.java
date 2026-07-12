package com.inventoryart.auth;

import com.inventoryart.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthControllerTest {
    @Test
    void usesCrossSiteRefreshCookieWhenConfiguredForProduction() {
        AppProperties properties = new AppProperties();
        properties.getJwt().setRefreshTokenDays(30);
        properties.getSecurity().setCookieSecure(true);
        properties.getSecurity().setCookieSameSite("None");

        AuthService service = mock(AuthService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(request.getHeader("User-Agent")).thenReturn("test-agent");
        var response = new AuthDtos.AuthResponse("access-token", Instant.now().plusSeconds(900), null);
        when(service.login("user", "password", "127.0.0.1", "test-agent"))
            .thenReturn(new AuthService.Session(response, "refresh-token"));

        String cookie = new AuthController(service, properties)
            .login(new AuthDtos.LoginRequest("user", "password"), request)
            .getHeaders().getFirst("Set-Cookie");

        assertThat(cookie)
            .contains("refresh_token=refresh-token")
            .contains("Path=/api/v1/auth")
            .contains("Secure")
            .contains("HttpOnly")
            .contains("SameSite=None");
    }
}
