package com.ykm.common.common_lib.filter;

import io.micrometer.common.util.StringUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Filter that logs request/response content for debugging.
 */
@Slf4j
@Component
public class RequestResponseLoggingFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) throws ServletException {
        String path = request.getRequestURI();

        // 去掉 context-path，避免部署在 /app 時比對失敗
        String ctx = request.getContextPath();
        if (ctx != null && !ctx.isEmpty() && path.startsWith(ctx)) {
            path = path.substring(ctx.length());
        }

        return path.startsWith("/swagger-ui")
                || path.startsWith("/v3/api-docs")
                || path.equals("/swagger-ui.html")
                || path.startsWith("/swagger-ui/favicon")
                || path.startsWith("/favicon");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {

        ContentCachingRequestWrapper requestWrapper = new ContentCachingRequestWrapper(request);
        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);

        String path = request.getRequestURL() + (StringUtils.isNotBlank(request.getQueryString()) ? "?" + request.getQueryString() : "");
        log.info("IP : {}, port : {}, method : {}, url : {}", getRequestIP(request), request.getRemotePort(), request.getMethod().toLowerCase(), path);

        try {
            filterChain.doFilter(requestWrapper, responseWrapper);
        } finally {
            byte[] reqBodyBytes = requestWrapper.getContentAsByteArray();
            if (reqBodyBytes.length > 0) {
                String reqBody = new String(reqBodyBytes, StandardCharsets.UTF_8);
                log.info("request body : {}", reqBody);
            }

            byte[] respBodyBytes = responseWrapper.getContentAsByteArray();
            String respBody = new String(respBodyBytes, StandardCharsets.UTF_8);
            if (StringUtils.isNotBlank(respBody)) {
                log.info("response status : {}, response body : {}", responseWrapper.getStatus(), respBody);
            } else {
                log.info("response status : {}, No response content", responseWrapper.getStatus());
            }

            responseWrapper.copyBodyToResponse();
        }
    }


    /**
     * Retrieve the client's real IP address.
     *
     * <p>This method first checks the X-Forwarded-For header, which is set by
     * proxies, load balancers, or CDNs to indicate the original client IP.
     * If that header is absent or empty, it falls back to the remote address
     * provided by the servlet request.</p>
     *
     * <p><strong>Note:</strong> The X-Forwarded-For header can be spoofed by clients.
     * Do not rely on it for critical security checks without proper validation.</p>
     *
     * @param request the HttpServletRequest object
     * @return the client's IP address, or the remote address if no forwarded header is present
     * @see HttpServletRequest#getHeader(String)
     * @see HttpServletRequest#getRemoteAddr()
     */
    public String getRequestIP(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            // X-Forwarded-For may contain multiple IPs; take the first one
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
