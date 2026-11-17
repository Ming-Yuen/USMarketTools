package com.ykm.stock.analytics.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ykm.stock.analytics.converter.sec.SecFinancialFactConverter;
import com.ykm.stock.analytics.model.entity.SecFinancialFact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SecEdgarFeedArchiveTests {
    private static final String ACC = "0000320193-26-000026";
    private static final LocalDate FILED = LocalDate.of(2026, 11, 3);
    private static final Instant CLOSE = Instant.parse("2026-11-13T21:00:00Z");
    private static final String TAG = "DepreciationDepletionAndAmortization";
    private static final String QUARTER = SecEdgarFeedArchive.URL + "2026/QTR4/";
    @TempDir Path directory;
    private final RestClient.Builder http = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void valuationDoesNotReadTheTarOrRequestTheSecDirectoryWhenJsonIsMissing() throws IOException {
        SecFinancialFact stock = fact(TAG, "13423000000");
        SecEdgarFeedArchive archive = new SecEdgarFeedArchive(http.build(), mapper, directory, FILED, 64);
        SecEdgarFeedArchive.Session session = archive.session();
        Files.write(directory.resolve("20261103.nc.tar.gz"), tar(submission("Depreciation and amortization", "us-gaap:" + TAG, "", "")));

        SecEdgarFeedArchive.Confirmation missing = session.confirm(List.of(stock), CLOSE);
        assertThat(missing.facts().apply(List.of(stock))).isEmpty();
        assertThat(missing.missingFacts()).containsExactly(SecFinancialFactConverter.key(stock));
        assertThat(missing.failedDates()).isEmpty();
        assertThat(session.archivesOpened()).isZero();
        assertThat(directory.resolve("20261103.json")).doesNotExist();

        Files.delete(directory.resolve("20261103.nc.tar.gz"));
        assertThat(session.confirm(List.of(stock), CLOSE).facts().apply(List.of(stock))).isEmpty();
        server.verify();
    }

    @Test
    void valuationMatchesACachedSpilloverAndReportsMissingByAccession() throws IOException {
        SecFinancialFact stock = fact(TAG, "13423000000");
        SecFinancialFact missing = fact(TAG, "13423000000");
        missing.setAccession("0000320193-26-000027");
        Files.write(directory.resolve("20261104.nc.tar.gz"), tar(submission("Depreciation and amortization", "us-gaap:" + TAG, "", "")));
        SecEdgarFeedArchive archive = new SecEdgarFeedArchive(http.build(), mapper, directory, FILED, 64);
        server.expect(requestTo(QUARTER + "index.json")).andRespond(withSuccess("""
                {"directory":{"item":[{"name":"20261104.nc.tar.gz"}]}}
                """, MediaType.APPLICATION_JSON));
        archive.downloadingSession().confirm(List.of(stock), CLOSE);

        SecEdgarFeedArchive.Confirmation cached = archive.session().confirm(List.of(stock, missing), CLOSE);
        assertThat(cached.facts().apply(List.of(stock, missing))).containsExactly(SecFinancialFactConverter.key(stock));
        assertThat(cached.missingFacts()).containsExactly(SecFinancialFactConverter.key(missing));
        assertThat(cached.failedDates()).isEmpty();
        server.verify();
    }

    @Test
    void valuationDistinguishesCorruptEvidenceFromUnavailableHistoricalEvidence() throws IOException {
        SecFinancialFact stock = fact(TAG, "13423000000");
        SecEdgarFeedArchive archive = new SecEdgarFeedArchive(http.build(), mapper, directory, FILED.plusDays(8), 64);

        SecEdgarFeedArchive.Confirmation historical = archive.session().confirm(List.of(stock), CLOSE);
        assertThat(historical.failedDates()).isEmpty();
        assertThat(historical.missingFacts()).isEmpty();
        assertThat(historical.facts().apply(List.of(stock))).isEmpty();

        Files.writeString(directory.resolve("20261103.json"), "{");
        SecEdgarFeedArchive.Confirmation corrupt = archive.session().confirm(List.of(stock), CLOSE);
        assertThat(corrupt.failedDates()).containsExactly(FILED);
        assertThat(corrupt.missingFacts()).isEmpty();
        assertThat(corrupt.facts().apply(List.of(stock))).isEmpty();
        server.verify();
    }

    @Test
    void indexesOneDailyFileAndReusesOnlyTheStandardConsolidatedValueAcrossRuns() throws IOException {
        String html = submission("Amortization &amp; depreciation", "us-gaap:" + TAG, "", "20261103080515");
        Files.write(directory.resolve("20261103.nc.tar.gz"), tar(html));
        SecEdgarFeedArchive archive = new SecEdgarFeedArchive(http.build(), mapper, directory, FILED, 64);
        SecFinancialFact stock = fact(TAG, "13423000000");

        SecEdgarFeedArchive.Session first = archive.downloadingSession();
        assertThat(first.confirm(List.of(stock), CLOSE).facts().apply(List.of(stock))).containsExactly(SecFinancialFactConverter.key(stock));
        assertThat(first.archivesOpened()).isEqualTo(1);

        SecEdgarFeedArchive.Session later = new SecEdgarFeedArchive(http.build(), mapper, directory, FILED, 64).session();
        SecFinancialFact wrongValue = fact(TAG, "13424000000");
        assertThat(later.confirm(List.of(wrongValue), CLOSE).facts().apply(List.of(wrongValue))).isEmpty();
        assertThat(later.confirm(List.of(stock), CLOSE).facts().apply(List.of(stock))).containsExactly(SecFinancialFactConverter.key(stock));
        assertThat(later.archivesOpened()).isZero();
        server.verify();
    }

    @Test
    void leavesAccretionCustomDimensionalAndPostCloseNumbersUnverified() throws IOException {
        SecFinancialFact stock = fact(TAG, "13423000000");
        for (String html : List.of(
                submission("Depreciation, amortization and accretion", "us-gaap:" + TAG, "", ""),
                submission("Depreciation and amortization", "private:" + TAG, "", ""),
                submission("Depreciation and amortization", "us-gaap:" + TAG, "<xbrli:segment><member/></xbrli:segment>", ""),
                submission("Depreciation and amortization", "us-gaap:" + TAG, "", "20261113160001"),
                submission("Depreciation and amortization", "us-gaap:" + TAG, "", "").replace("2025-09-28", "2025-10-01"))) {
            Files.deleteIfExists(directory.resolve("20261103.json"));
            Files.write(directory.resolve("20261103.nc.tar.gz"), tar(html));
            SecEdgarFeedArchive archive = new SecEdgarFeedArchive(http.build(), mapper, directory, FILED, 64);
            SecEdgarFeedArchive.Confirmation result = archive.downloadingSession().confirm(List.of(stock), CLOSE);
            assertThat(result.facts().apply(List.of(stock))).isEmpty();
            assertThat(result.failedDates()).isEmpty();
            assertThat(archive.session().confirm(List.of(stock), CLOSE).missingFacts()).isEmpty();
        }
        server.verify();
    }

    @Test
    void downloadsPublishedArchiveInValidatedRangesAndDoesNotDownloadAgain() throws IOException {
        byte[] gzip = tar(submission("Depreciation and amortization", "us-gaap:" + TAG, "", ""));
        int segment = (gzip.length + 1) / 2;
        server.expect(requestTo(QUARTER + "index.json")).andRespond(withSuccess("""
                {"directory":{"item":[{"name":"20261103.nc.tar.gz"}]}}
                """, MediaType.APPLICATION_JSON));
        for (int start = 0; start < gzip.length; start += segment) {
            int last = start + segment - 1;
            int through = Math.min(gzip.length, last + 1);
            server.expect(requestTo(QUARTER + "20261103.nc.tar.gz")).andExpect(header(HttpHeaders.RANGE, "bytes=" + start + "-" + (start == 0 ? last : through - 1)))
                    .andRespond(withStatus(HttpStatus.PARTIAL_CONTENT).header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + (through - 1) + "/" + gzip.length)
                            .body(Arrays.copyOfRange(gzip, start, through)));
        }
        SecEdgarFeedArchive archive = new SecEdgarFeedArchive(http.build(), mapper, directory, FILED, segment);
        SecFinancialFact stock = fact(TAG, "13423000000");
        SecEdgarFeedArchive.Session download = archive.downloadingSession();
        assertThat(download.confirm(List.of(stock), CLOSE).facts().apply(List.of(stock))).containsExactly(SecFinancialFactConverter.key(stock));
        assertThat(download.confirm(List.of(stock), CLOSE).facts().apply(List.of(stock))).containsExactly(SecFinancialFactConverter.key(stock));
        assertThat(download.archivesOpened()).isEqualTo(1);
        assertThat(archive.downloadingSession().confirm(List.of(stock), CLOSE).facts().apply(List.of(stock))).containsExactly(SecFinancialFactConverter.key(stock));
        assertThat(archive.session().confirm(List.of(stock), CLOSE).facts().apply(List.of(stock))).containsExactly(SecFinancialFactConverter.key(stock));
        assertThat(directory.resolve("20261103.json")).exists();
        assertThat(directory.resolve("20261103.nc.tar.gz")).doesNotExist();
        server.verify();
    }

    @Test
    void corruptedDayRemainsRetryableAndDoesNotPublishEvidence() throws IOException {
        byte[] gzip = tar(submission("Depreciation and amortization", "us-gaap:" + TAG, "", ""));
        Files.write(directory.resolve("20261103.nc.tar.gz"), Arrays.copyOf(gzip, gzip.length - 8));
        SecEdgarFeedArchive archive = new SecEdgarFeedArchive(http.build(), mapper, directory, FILED, 64);
        SecFinancialFact stock = fact(TAG, "13423000000");
        assertThat(archive.downloadingSession().confirm(List.of(stock), CLOSE).failedDates()).containsExactly(FILED);
        assertThat(directory.resolve("20261103.json")).doesNotExist();
        Files.write(directory.resolve("20261103.nc.tar.gz"), gzip);
        assertThat(archive.downloadingSession().confirm(List.of(stock), CLOSE).facts().apply(List.of(stock))).containsExactly(SecFinancialFactConverter.key(stock));
        server.verify();
    }

    @Test
    void resumesAnInterruptedDailyFileAtTheLastCompleteRange() throws IOException {
        byte[] gzip = tar(submission("Depreciation and amortization", "us-gaap:" + TAG, "", ""));
        int segment = (gzip.length + 1) / 2;
        String index = """
                {"directory":{"item":[{"name":"20261103.nc.tar.gz"}]}}
                """;
        String url = QUARTER + "20261103.nc.tar.gz";
        server.expect(requestTo(QUARTER + "index.json")).andRespond(withSuccess(index, MediaType.APPLICATION_JSON));
        server.expect(requestTo(url)).andExpect(header(HttpHeaders.RANGE, "bytes=0-" + (segment - 1)))
                .andRespond(withStatus(HttpStatus.PARTIAL_CONTENT).header(HttpHeaders.CONTENT_RANGE, "bytes 0-" + (segment - 1) + "/" + gzip.length)
                        .body(Arrays.copyOfRange(gzip, 0, segment)));
        server.expect(requestTo(url)).andExpect(header(HttpHeaders.RANGE, "bytes=" + segment + "-" + (gzip.length - 1)))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(QUARTER + "index.json")).andRespond(withSuccess(index, MediaType.APPLICATION_JSON));
        server.expect(requestTo(url)).andExpect(header(HttpHeaders.RANGE, "bytes=" + segment + "-" + (segment * 2 - 1)))
                .andRespond(withStatus(HttpStatus.PARTIAL_CONTENT)
                        .header(HttpHeaders.CONTENT_RANGE, "bytes " + segment + "-" + (gzip.length - 1) + "/" + gzip.length)
                        .body(Arrays.copyOfRange(gzip, segment, gzip.length)));
        SecEdgarFeedArchive archive = new SecEdgarFeedArchive(http.build(), mapper, directory, FILED, segment);
        SecFinancialFact stock = fact(TAG, "13423000000");
        assertThat(archive.downloadingSession().confirm(List.of(stock), CLOSE).failedDates()).containsExactly(FILED);
        assertThat(Files.size(directory.resolve("20261103.tar.gz.part"))).isEqualTo(segment);
        assertThat(directory.resolve("20261103.json")).doesNotExist();
        assertThat(archive.downloadingSession().confirm(List.of(stock), CLOSE).facts().apply(List.of(stock))).containsExactly(SecFinancialFactConverter.key(stock));
        assertThat(directory.resolve("20261103.tar.gz.part")).doesNotExist();
        server.verify();
    }

    private SecFinancialFact fact(String tag, String value) {
        SecFinancialFact fact = new SecFinancialFact();
        fact.setCik("0000320193");
        fact.setTaxonomy("us-gaap");
        fact.setTag(tag);
        fact.setUnit("USD");
        fact.setAccession(ACC);
        fact.setFiled(FILED);
        fact.setForm("10-K");
        fact.setPeriodStart(LocalDate.of(2025, 9, 28));
        fact.setPeriodEnd(LocalDate.of(2026, 9, 26));
        fact.setValue(new BigDecimal(value));
        return fact;
    }

    private String submission(String label, String tag, String segment, String accepted) {
        return """
                <SUBMISSION>
                <ACCESSION-NUMBER>%s
                <TYPE>10-K
                <FILING-DATE>20261103
                <ACCEPTANCE-DATETIME>%s
                <DOCUMENT>
                <TYPE>10-K
                <TEXT>
                <html xmlns:us-gaap="http://fasb.org/us-gaap/2026" xmlns:private="http://example.test/private"
                      xmlns:iso4217="http://www.xbrl.org/2003/iso4217">
                <body>
                <ix:header><ix:resources>
                <xbrli:context id="fy"><xbrli:entity><xbrli:identifier scheme="http://www.sec.gov/CIK">0000320193</xbrli:identifier>%s</xbrli:entity>
                <xbrli:period><xbrli:startDate>2025-09-28</xbrli:startDate><xbrli:endDate>2026-09-26</xbrli:endDate></xbrli:period></xbrli:context>
                <xbrli:unit id="usd"><xbrli:measure>iso4217:USD</xbrli:measure></xbrli:unit>
                </ix:resources></ix:header>
                <table><tr><td>Operating activities</td></tr>
                <tr><td>%s</td><td><ix:nonFraction name="%s" contextRef="fy" unitRef="usd" scale="6" format="ixt:num-dot-decimal">13,423</ix:nonFraction></td></tr>
                <tr><td>Investing activities</td></tr><tr><td>Financing activities</td></tr></table>
                </body></html>
                </TEXT>
                </DOCUMENT>
                </SUBMISSION>
                """.formatted(ACC, accepted, segment, label, tag);
    }

    private byte[] tar(String sgml) throws IOException {
        byte[] data = sgml.getBytes(StandardCharsets.UTF_8);
        byte[] header = new byte[512];
        byte[] name = ("./" + ACC + ".nc").getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(name, 0, header, 0, name.length);
        byte[] size = String.format(Locale.ROOT, "%011o", data.length).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(size, 0, header, 124, size.length);
        header[156] = '0';
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(header);
            gzip.write(data);
            gzip.write(new byte[(512 - data.length % 512) % 512 + 1024]);
        }
        return bytes.toByteArray();
    }
}
