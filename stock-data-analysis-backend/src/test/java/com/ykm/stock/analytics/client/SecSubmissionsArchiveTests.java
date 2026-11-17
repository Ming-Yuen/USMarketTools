package com.ykm.stock.analytics.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.client.SecSubmissionsArchive.CompanySubmissions;
import com.ykm.stock.analytics.client.SecSubmissionsArchive.FilingSubmission;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SecSubmissionsArchiveTests {
    @TempDir Path root;
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final DateTimeService clock = mock(DateTimeService.class);
    private final String modified = "Fri, 13 Nov 2026 08:00:00 GMT";

    @Test
    void parsesZeroPaddedSicRecentColumnarFilingsAndPost2014OverflowFiles() throws Exception {
        when(clock.now()).thenReturn(Instant.parse("2026-11-13T08:00:00Z"));
        String mainJson = """
                {
                  "cik": "320193",
                  "sic": "737",
                  "filings": {
                    "recent": {
                      "accessionNumber": ["0000320193-26-000070", "0000320193-26-000055"],
                      "form": ["10-K", "8-K"],
                      "filingDate": ["2026-11-03", "2026-11-10"],
                      "reportDate": ["2026-09-26", "2026-11-10"],
                      "acceptanceDateTime": ["2026-11-03T21:05:12.000Z", "20261110163000"]
                    },
                    "files": [
                      {"name": "CIK0000320193-submissions-001.json", "filingFrom": "2015-01-01", "filingTo": "2018-12-31"},
                      {"name": "CIK0000320193-submissions-002.json", "filingFrom": "2008-01-01", "filingTo": "2013-12-31"}
                    ]
                  }
                }
                """;
        String overflowRecent = """
                {
                  "accessionNumber": ["0000320193-17-000070"],
                  "form": ["10-K"],
                  "filingDate": ["2017-11-03"],
                  "reportDate": ["2017-09-30"],
                  "acceptanceDateTime": ["2017-11-03T12:01:00.000Z"]
                }
                """;
        String overflowOld = """
                {
                  "accessionNumber": ["0000320193-12-000001"],
                  "form": ["10-K"],
                  "filingDate": ["2012-11-01"],
                  "reportDate": ["2012-09-29"],
                  "acceptanceDateTime": ["2012-11-01T12:01:00.000Z"]
                }
                """;
        byte[] zipBytes = zip(Map.of(
                "CIK0000320193.json", mainJson,
                "CIK0000320193-submissions-001.json", overflowRecent,
                "CIK0000320193-submissions-002.json", overflowOld));

        server.expect(requestTo(SecSubmissionsArchive.URL)).andExpect(method(HttpMethod.HEAD))
                .andRespond(withSuccess().header("ETag", "\"s1\"").header("Last-Modified", modified));
        server.expect(requestTo(SecSubmissionsArchive.URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(zipBytes, MediaType.APPLICATION_OCTET_STREAM)
                        .header("ETag", "\"s1\"").header("Last-Modified", modified));

        SecSubmissionsArchive archive = new SecSubmissionsArchive(builder.build(), mapper, clock,
                "stock-project owner@real-domain.net", root.resolve("submissions").toString());
        try (SecSubmissionsArchive.Snapshot snapshot = archive.prepare()) {
            assertThat(snapshot.readSic("0000320193")).contains("0737");
            assertThat(snapshot.findCompany("0000000001")).isEmpty();
            Optional<CompanySubmissions> company = snapshot.findCompany("0000320193");
            assertThat(company).isPresent();
            assertThat(company.get().sic()).isEqualTo("0737");
            assertThat(company.get().latestPeriodicFilingDate()).isEqualTo(LocalDate.of(2026, 11, 3));
            assertThat(company.get().filingsByAccession()).containsKeys(
                    "0000320193-26-000070", "0000320193-26-000055", "0000320193-17-000070")
                    .doesNotContainKey("0000320193-12-000001");
            FilingSubmission annual = company.get().filingsByAccession().get("0000320193-26-000070");
            assertThat(annual.reportDate()).isEqualTo(LocalDate.of(2026, 9, 26));
            assertThat(annual.acceptedAt()).isEqualTo(Instant.parse("2026-11-03T21:05:12Z"));
        }
        try (SecSubmissionsArchive.Snapshot cachedSameDay = archive.prepare()) {
            assertThat(cachedSameDay.version()).startsWith("\"s1\"|");
        }
        server.verify();
    }

    private byte[] zip(Map<String, String> entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
