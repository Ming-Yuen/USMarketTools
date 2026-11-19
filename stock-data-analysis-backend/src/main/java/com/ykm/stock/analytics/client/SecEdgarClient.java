package com.ykm.stock.analytics.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.util.concurrent.RateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Locale;

/**
 * 統一 SEC EDGAR 限流客戶端（5 req/sec，嚴守 SEC Fair Access 10 req/sec 紅線）。
 * 強制核驗真實聯絡信箱 User-Agent；對 HTTP 401/403 立即失敗不重試，網路等待與限流不放在 DB 交易內。
 */
@Component
@Slf4j
public class SecEdgarClient {
    private static final String DATA_BASE_URL = "https://data.sec.gov";
    private static final String ARCHIVES_BASE_URL = "https://www.sec.gov";

    private final RestClient dataClient;
    private final RestClient archiveClient;
    private final ObjectMapper objectMapper;
    private final String userAgent;
    private final RateLimiter rateLimiter;

    public SecEdgarClient(
            ObjectMapper objectMapper,
            @Value("${sec.user-agent:}") String userAgent,
            @Value("${sec.edgar.requests-per-second:5.0}") double requestsPerSecond) {
        this.objectMapper = objectMapper;
        this.userAgent = userAgent == null ? "" : userAgent.trim();
        double effectiveRate = (requestsPerSecond <= 0 || requestsPerSecond > 5.0) ? 5.0 : Math.max(1.0, requestsPerSecond);
        this.rateLimiter = RateLimiter.create(effectiveRate);
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(15));
        factory.setReadTimeout(Duration.ofSeconds(30));
        this.dataClient = RestClient.builder()
                .baseUrl(DATA_BASE_URL)
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.USER_AGENT, this.userAgent)
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .build();
        this.archiveClient = RestClient.builder()
                .baseUrl(ARCHIVES_BASE_URL)
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.USER_AGENT, this.userAgent)
                .defaultHeader(HttpHeaders.ACCEPT, "*/*")
                .build();
    }

    public boolean isConfigured() {
        String contact = userAgent.toLowerCase(Locale.ROOT);
        return !contact.isBlank() && contact.contains("@") && !contact.contains("@localhost") && !contact.contains("example.com");
    }

    public JsonNode fetchCompanySubmissions(String cik) {
        requireConfigured();
        String padded = normalizeCik(cik);
        String body = executeGet(dataClient, "/submissions/CIK" + padded + ".json", "SEC_SUBMISSIONS:" + padded);
        try {
            return objectMapper.readTree(body);
        } catch (Exception ex) {
            throw new IllegalStateException("SEC_SUBMISSIONS_PARSE_FAILED:" + padded, ex);
        }
    }

    public JsonNode fetchFilingIndexJson(String cik, String accession) {
        requireConfigured();
        String path = buildArchivePath(cik, accession, "index.json");
        String body = executeGet(archiveClient, path, "SEC_INDEX_JSON:" + accession);
        try {
            return objectMapper.readTree(body);
        } catch (Exception ex) {
            throw new IllegalStateException("SEC_INDEX_JSON_PARSE_FAILED:" + accession, ex);
        }
    }

    public String fetchArchiveFile(String cik, String accession, String relativePath) {
        requireConfigured();
        String path = buildArchivePath(cik, accession, relativePath);
        return executeGet(archiveClient, path, "SEC_ARCHIVE_FILE:" + accession + ":" + relativePath);
    }

    public String buildArchiveUrl(String cik, String accession, String relativePath) {
        return ARCHIVES_BASE_URL + buildArchivePath(cik, accession, relativePath);
    }

    public String buildFilingIndexHtmlUrl(String cik, String accession) {
        return buildArchiveUrl(cik, accession, accession + "-index.html");
    }

    public static String normalizeCik(String cik) {
        if (cik == null || !cik.trim().matches("[0-9]{1,10}")) {
            throw new IllegalArgumentException("SEC_CIK_INVALID:" + cik);
        }
        return String.format("%010d", Long.parseLong(cik.trim()));
    }

    public static String cikWithoutLeadingZeros(String cik) {
        return String.valueOf(Long.parseLong(normalizeCik(cik)));
    }

    public static String stripXsltPrefix(String primaryDocument) {
        if (primaryDocument == null || primaryDocument.isBlank()) {
            return "";
        }
        String trimmed = primaryDocument.trim();
        int slash = trimmed.lastIndexOf('/');
        return slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
    }

    private String buildArchivePath(String cik, String accession, String relativePath) {
        if (accession == null || !accession.trim().matches("[0-9]{10}-[0-9]{2}-[0-9]{6}")) {
            throw new IllegalArgumentException("SEC_ACCESSION_INVALID:" + accession);
        }
        if (relativePath == null || relativePath.isBlank() || relativePath.contains("..")) {
            throw new IllegalArgumentException("SEC_RELATIVE_PATH_INVALID:" + relativePath);
        }
        String cleanPath = relativePath.startsWith("/") ? relativePath.substring(1) : relativePath;
        String accNoDashes = accession.trim().replace("-", "");
        return "/Archives/edgar/data/" + cikWithoutLeadingZeros(cik) + "/" + accNoDashes + "/" + cleanPath;
    }

    private String executeGet(RestClient client, String path, String label) {
        rateLimiter.acquire();
        try {
            String body = client.get().uri(path).retrieve().body(String.class);
            if (body == null) {
                throw new IllegalStateException(label + ":EMPTY_BODY");
            }
            return body;
        } catch (HttpStatusCodeException ex) {
            HttpStatusCode status = ex.getStatusCode();
            if (status.value() == 401 || status.value() == 403) {
                throw new SecUnauthorizedException("SEC_EDGAR_UNAUTHORIZED:" + status.value() + ":" + label, ex);
            }
            if (status.value() == 404) {
                throw new SecNotFoundException("SEC_EDGAR_NOT_FOUND:" + label, ex);
            }
            throw new IllegalStateException("SEC_EDGAR_HTTP_" + status.value() + ":" + label, ex);
        }
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new SecUnauthorizedException("SEC_USER_AGENT_NOT_CONFIGURED", null);
        }
    }

    public static boolean isAuthFailure(Throwable ex) {
        Throwable cur = ex;
        while (cur != null) {
            if (cur instanceof SecUnauthorizedException) {
                return true;
            }
            String msg = cur.getMessage();
            if (msg != null && (msg.contains("SEC_EDGAR_UNAUTHORIZED")
                    || msg.contains("SEC_USER_AGENT_NOT_CONFIGURED")
                    || msg.contains("HTTP_401")
                    || msg.contains("HTTP_403"))) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    public static class SecUnauthorizedException extends IllegalStateException {
        public SecUnauthorizedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class SecNotFoundException extends IllegalStateException {
        public SecNotFoundException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
