package com.ykm.common.common_lib.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

/**
 * Global CORS (Cross-Origin Resource Sharing) configuration.
 *
 * <p>This configuration class defines the global CORS policy for the application,
 * allowing cross-origin requests from specified origins, methods, and headers.
 * In development, all origins are permitted; in production, adjust allowedOriginPattern
 * to specific domains for security.</p>
 *
 * @author Developer
 * @version 1.0
 * @since 2025-07-04
 */
@Configuration
public class CorsConfig {

    /**
     * Default constructor for CorsConfig.
     *
     * <p>This constructor is provided to satisfy Javadoc requirements
     * and does not perform any specific initialization.</p>
     */
    public CorsConfig() {
        // Default constructor - no specific initialization required
    }

    /**
     * Create and register a CorsFilter bean that applies the CORS configuration
     * to all incoming requests ("/**").
     *
     * <ul>
     *   <li>Allowed origins: all ("*") in development; restrict in production.</li>
     *   <li>Allows credentials (cookies, authorization headers).</li>
     *   <li>Allowed HTTP methods: all (GET, POST, PUT, DELETE, OPTIONS, etc.).</li>
     *   <li>Allowed request headers: all.</li>
     *   <li>Exposed response headers: all.</li>
     * </ul>
     *
     * @return a configured {@link CorsFilter} instance
     */
    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        // Allow all origins (use specific domains in production)
        config.addAllowedOriginPattern("*");
        // Allow cookies and credentials
        config.setAllowCredentials(false);
        // Allow all HTTP methods
        config.addAllowedMethod("*");
        // Allow all request headers
        config.addAllowedHeader("*");
        // Expose all response headers
        config.addExposedHeader("*");
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        // Apply this CORS configuration to all URL paths
        source.registerCorsConfiguration("/**", config);

        return new CorsFilter(source);
    }
}
