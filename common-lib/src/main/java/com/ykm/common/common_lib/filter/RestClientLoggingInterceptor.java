package com.ykm.common.common_lib.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
@Slf4j
public class RestClientLoggingInterceptor implements ClientHttpRequestInterceptor {

    private static final int MAX_LOG_BYTES = 4 * 1024;

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        log.info("Request: {} {}", request.getMethod(), request.getURI());
        if (body.length > 0) {
            log.info("Request Body: {}", new String(body, StandardCharsets.UTF_8));
        }

        ClientHttpResponse response = execution.execute(request, body);

        byte[] respBytes = StreamUtils.copyToByteArray(response.getBody());
        log.info("Response Status: {}", response.getStatusCode().value());
        if (respBytes.length > MAX_LOG_BYTES) {
            String preview = new String(respBytes, 0, MAX_LOG_BYTES, StandardCharsets.UTF_8);
            log.info("Response Body (first {} bytes): {} ... [truncated, total {} bytes]", MAX_LOG_BYTES, preview, respBytes.length);
        } else if (respBytes.length > 0) {
            log.info("Response Body: {}", new String(respBytes, StandardCharsets.UTF_8));
        }

        return response;
    }
}