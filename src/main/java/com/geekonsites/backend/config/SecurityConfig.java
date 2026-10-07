package com.geekonsites.backend.config;

import com.geekonsites.backend.jwt.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.http.HttpMethod;
import org.springframework.beans.factory.annotation.Value;
import java.util.Arrays;
import java.util.List;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final UserDetailsService userDetailsService;
    private final PasswordEncoder passwordEncoder;
    private final RestAuthenticationEntryPoint restAuthenticationEntryPoint;
    private final RestAccessDeniedHandler restAccessDeniedHandler;

    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {

        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(request -> {
                    CorsConfiguration config = new CorsConfiguration();
                    config.setAllowedOrigins(Arrays.stream(allowedOrigins.split(","))
                            .map(String::trim)
                            .filter(origin -> !origin.isEmpty())
                            .toList());
                    config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
                    config.setAllowedHeaders(List.of("*"));
                    config.setAllowCredentials(true);
                    return config;
                }))
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/auth/change-password").authenticated()
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/admin/auth/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/contact").permitAll()
                        .requestMatchers(HttpMethod.DELETE, "/api/contact/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/contact/**").hasAnyAuthority("ROLE_AGENT", "ROLE_ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/contact/**").hasAnyAuthority("ROLE_AGENT", "ROLE_ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/payments/webhook").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/health", "/api/ratings/technician/*").permitAll()
                        // PHASE 6: public service discovery (public marketplace); admin management is ADMIN-only.
                        .requestMatchers(HttpMethod.GET, "/api/services", "/api/services/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/ratings").hasRole("CUSTOMER")
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/refunds/**").hasRole("CUSTOMER")
                        .requestMatchers("/api/remote-session-chat/**").authenticated()
                        .requestMatchers("/api/remote-sessions/**").hasRole("TECHNICIAN")
                        // PHASE 5: normal customer booking creation is CUSTOMER-only.
                        .requestMatchers(HttpMethod.POST, "/api/bookings").hasRole("CUSTOMER")
                        .requestMatchers(HttpMethod.GET, "/api/bookings").hasAnyRole("AGENT", "ADMIN")
                        // PHASE 9: paginated operational booking list (AGENT/ADMIN).
                        .requestMatchers(HttpMethod.GET, "/api/bookings/page").hasAnyRole("AGENT", "ADMIN")
                        .requestMatchers("/api/bookings/*/close").hasAnyRole("AGENT", "ADMIN")
                        .requestMatchers("/api/bookings/customer/*").hasAnyRole("AGENT", "ADMIN")
                        .requestMatchers("/api/bookings/agent/*").hasAnyRole("AGENT", "ADMIN")
                        .requestMatchers("/api/bookings/*/assign-technician/*").hasAnyRole("AGENT", "ADMIN")
                        .requestMatchers("/api/bookings/*/technician/**").hasRole("TECHNICIAN")
                        // PHASE 9 E2E fix: meeting-link is a technician action; without this it fell
                        // through to authenticated() and a non-technician got a 500.
                        .requestMatchers("/api/bookings/*/meeting-link").hasRole("TECHNICIAN")
                        .requestMatchers("/api/bookings/**").authenticated()
                        .requestMatchers("/api/payments/**").authenticated()
                        .requestMatchers("/api/invoices/**").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/notifications/my-notifications").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/notifications").hasAnyRole("AGENT", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/notifications/*").hasAnyRole("AGENT", "ADMIN")
                        .requestMatchers("/api/notifications/**").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/technicians").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/technicians/onboarding/set-password").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/technicians/*/verification/**").hasRole("ADMIN")
                        .requestMatchers("/api/technicians/me", "/api/technicians/me/**", "/api/technicians/my-bookings", "/api/technicians/my-notifications").hasRole("TECHNICIAN")
                        .requestMatchers(HttpMethod.GET, "/api/technicians", "/api/technicians/pending", "/api/technicians/*").hasAnyRole("AGENT", "ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/technicians/*/approve", "/api/technicians/*/reject").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/technicians/*/resend-onboarding").hasRole("ADMIN")
                        .requestMatchers("/api/technicians/**").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/agents").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/agents/my-notifications")
                        .hasAnyAuthority("ROLE_AGENT", "ROLE_ADMIN")
                        .requestMatchers("/api/agent-crm/**").hasAnyAuthority("ROLE_AGENT", "ROLE_ADMIN")
                        .requestMatchers("/api/agents/**").hasAnyAuthority("ROLE_AGENT", "ROLE_ADMIN")
                        .requestMatchers("/api/users/**").authenticated()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll()
                )
                // PHASE 7: 401/403 also use the standard ApiErrorResponse contract.
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(restAuthenticationEntryPoint)
                        .accessDeniedHandler(restAccessDeniedHandler))
                .authenticationProvider(authenticationProvider())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration configuration
    ) throws Exception {
        return configuration.getAuthenticationManager();
    }
}
