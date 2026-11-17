package com.ykm.stock.analytics.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ykm.common.common_lib.service.DateTimeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class SecCompanyFactsArchiveTests {
    @TempDir Path root;
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final DateTimeService clock = mock(DateTimeService.class);
    private final String modified = "Fri, 13 Nov 2026 08:00:00 GMT";

    private SecCompanyFactsArchive archive() {
        when(clock.now()).thenReturn(Instant.parse("2026-11-13T08:00:00Z"));
        return new SecCompanyFactsArchive(builder.build(), mapper, clock,
                "stock-project owner@real-domain.net", root.resolve("new").toString(), root.resolve("legacy").toString());
    }

    @Test
    void downloadsOnceAndReusesSameDayCacheThenChecksNextDay() throws Exception {
        byte[] bytes = zip("CIK0000320193.json", "{\"cik\":320193}");
        expectHead("\"v1\"");
        server.expect(requestTo(SecCompanyFactsArchive.URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(bytes, MediaType.APPLICATION_OCTET_STREAM).header("ETag", "\"v1\"").header("Last-Modified", modified));
        expectHead("\"v1\"");
        SecCompanyFactsArchive source = archive();
        try (SecCompanyFactsArchive.Snapshot first = source.prepare()) {
            assertThat(first.read("0000320193").path("cik").asInt()).isEqualTo(320193);
            assertThatThrownBy(() -> first.read("0000000001")).isInstanceOf(SecCompanyFactsArchive.EntryMissingException.class)
                    .hasMessage("SEC_SHARE_ENTRY_MISSING:0000000001");
        }
        try (SecCompanyFactsArchive.Snapshot second = source.prepare()) { assertThat(second.version()).startsWith("\"v1\"|"); }
        when(clock.now()).thenReturn(Instant.parse("2026-11-14T04:59:59Z"));
        try (SecCompanyFactsArchive.Snapshot sameNyDay = source.prepare()) { assertThat(sameNyDay.version()).startsWith("\"v1\"|"); }
        when(clock.now()).thenReturn(Instant.parse("2026-11-14T05:00:00Z"));
        try (SecCompanyFactsArchive.Snapshot nextNyDay = source.prepare()) { assertThat(nextNyDay.version()).startsWith("\"v1\"|"); }
        server.verify();
        assertThat(Files.list(root.resolve("new"))).noneMatch(path -> path.toString().endsWith(".part"));
    }

    @Test
    void reusesMatchingLegacyZipWithoutWritingIt() throws Exception {
        Path legacy = Files.createDirectories(root.resolve("legacy"));
        byte[] bytes = zip("CIK0000320193.json", "{}");
        Files.write(legacy.resolve("companyfacts.zip"), bytes);
        Files.writeString(legacy.resolve("sec-bulk-metadata.json"), "{\"companyfacts.zip\":{\"etag\":\"v1\",\"lastModified\":\"" + modified + "\"}}");
        expectHead("v1");
        try (SecCompanyFactsArchive.Snapshot snapshot = archive().prepare()) { assertThat(snapshot.read("0000320193").isObject()).isTrue(); }
        assertThat(Files.readAllBytes(legacy.resolve("companyfacts.zip"))).isEqualTo(bytes);
        assertThat(root.resolve("new")).doesNotExist();
        server.verify();
    }

    @Test
    void remoteFailureDoesNotUseOldArchiveAsSuccessfulRefresh() throws Exception {
        Files.createDirectories(root.resolve("legacy"));
        Files.write(root.resolve("legacy/companyfacts.zip"), zip("CIK0000320193.json", "{}"));
        server.expect(requestTo(SecCompanyFactsArchive.URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> archive().prepare()).hasMessageContaining("HTTP:503");
    }

    @Test
    void invalidZipNeverPublishesAndPartialIsRemoved() throws Exception {
        expectHead("v1");
        server.expect(requestTo(SecCompanyFactsArchive.URL)).andRespond(withSuccess("not a zip", MediaType.TEXT_PLAIN).header("ETag", "v1").header("Last-Modified", modified));
        assertThatThrownBy(() -> archive().prepare()).isInstanceOf(RuntimeException.class);
        assertThat(root.resolve("new/companyfacts.zip")).doesNotExist();
        try (java.util.stream.Stream<Path> paths = Files.list(root.resolve("new"))) { assertThat(paths).isEmpty(); }
    }

    private void expectHead(String etag) {
        server.expect(requestTo(SecCompanyFactsArchive.URL)).andExpect(method(HttpMethod.HEAD))
                .andRespond(withSuccess().header("ETag", etag).header("Last-Modified", modified));
    }

    private byte[] zip(String name, String body) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
}
