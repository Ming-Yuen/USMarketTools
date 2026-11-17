package com.ykm.stock.analytics.client;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ykm.common.common_lib.service.DateTimeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** 讀取與快取 SEC 官方 submissions.zip，提供 4 碼 SIC 及每份 accession 的 reportDate 與 acceptanceDateTime。 */
@Component
public class SecSubmissionsArchive {
    static final String URL = "https://www.sec.gov/Archives/edgar/daily-index/bulkdata/submissions.zip";
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final LocalDate HISTORY_FLOOR = LocalDate.of(2014, 1, 1);
    private static final DateTimeFormatter COMPACT_ET = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final Set<String> PERIODIC_FORMS = Set.of(
            "10-K", "10-K/A", "10-Q", "10-Q/A", "20-F", "20-F/A", "40-F", "40-F/A", "6-K", "6-K/A");

    private final RestClient http;
    private final ObjectMapper mapper;
    private final DateTimeService clock;
    private final Path directory;
    private final String userAgent;

    public record Metadata(String etag, String lastModified, Instant downloadedAt) {
        public String version() { return etag + "|" + lastModified; }
    }

    public record FilingSubmission(String accession, String form, LocalDate filingDate, LocalDate reportDate, Instant acceptedAt) {}

    public record CompanySubmissions(String sic, Map<String, FilingSubmission> filingsByAccession, LocalDate latestPeriodicFilingDate) {}

    @Autowired
    public SecSubmissionsArchive(RestClient.Builder builder, ObjectMapper mapper, DateTimeService clock,
                                 @Value("${sec.user-agent}") String userAgent,
                                 @Value("${sec.submissions.archive-directory:./data/sec-submissions}") String directory) {
        this(SecCompanyFactsArchive.http(builder, userAgent), mapper, clock, userAgent, directory);
    }

    SecSubmissionsArchive(RestClient http, ObjectMapper mapper, DateTimeService clock, String userAgent, String directory) {
        this.http = http;
        this.mapper = mapper;
        this.clock = clock;
        this.userAgent = userAgent;
        this.directory = Path.of(directory).toAbsolutePath().normalize();
    }

    public Snapshot prepare() {
        String contact = userAgent == null ? "" : userAgent.toLowerCase(Locale.ROOT);
        if (!contact.contains("@") || contact.contains("@localhost") || contact.contains("example.com"))
            throw new IllegalStateException("SEC_USER_AGENT requires application name and real contact email");
        try {
            Path cached = directory.resolve("submissions.zip");
            Path metadataFile = directory.resolve("archive.json");
            Metadata previous = null;
            if (Files.isRegularFile(cached) && Files.isRegularFile(metadataFile)) {
                previous = mapper.readValue(metadataFile.toFile(), Metadata.class);
                LocalDate today = clock.now().atZone(NEW_YORK).toLocalDate();
                if (previous.downloadedAt().atZone(NEW_YORK).toLocalDate().equals(today)) return open(cached, previous);
            }
            Metadata remote = http.head().uri(URL).header(HttpHeaders.CACHE_CONTROL, "no-cache").exchange((request, response) -> {
                requireOk(response.getStatusCode().value());
                return metadata(response.getHeaders());
            });
            if (previous != null && remote.version().equals(previous.version())) return open(cached, previous);
            Files.createDirectories(directory);
            return http.get().uri(URL).header(HttpHeaders.CACHE_CONTROL, "no-cache").exchange((request, response) -> {
                requireOk(response.getStatusCode().value());
                Metadata responseVersion = metadata(response.getHeaders());
                Path partial = Files.createTempFile(directory, "submissions-", ".part");
                try {
                    long size = Files.copy(response.getBody(), partial, StandardCopyOption.REPLACE_EXISTING);
                    if (size == 0 || response.getHeaders().getContentLength() >= 0 && size != response.getHeaders().getContentLength())
                        throw new IOException("SEC_SUBMISSIONS_ARCHIVE_TRUNCATED");
                    Metadata downloaded = new Metadata(responseVersion.etag(), responseVersion.lastModified(), clock.now());
                    try (Snapshot ignored = open(partial, downloaded)) { /* 發布前確認 ZIP 目錄可讀。 */ }
                    Files.move(partial, cached, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    Path metadataPartial = Files.createTempFile(directory, "archive-", ".part");
                    try {
                        mapper.writeValue(metadataPartial.toFile(), downloaded);
                        Files.move(metadataPartial, metadataFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } finally {
                        Files.deleteIfExists(metadataPartial);
                    }
                    return open(cached, downloaded);
                } finally {
                    Files.deleteIfExists(partial);
                }
            });
        } catch (IOException ex) {
            throw new UncheckedIOException("SEC_SUBMISSIONS_ARCHIVE_FAILED", ex);
        }
    }

    private Metadata metadata(HttpHeaders headers) {
        String etag = SecCompanyFactsArchive.resolveVersionToken(headers);
        String modified = headers.getFirst(HttpHeaders.LAST_MODIFIED);
        if (etag == null || etag.isBlank() || modified == null || modified.isBlank())
            throw new IllegalStateException("SEC_SUBMISSIONS_ARCHIVE_VERSION_MISSING");
        Metadata result = new Metadata(etag, modified, clock.now());
        ZonedDateTime.parse(modified, DateTimeFormatter.RFC_1123_DATE_TIME);
        if (result.version().length() > 512) throw new IllegalStateException("SEC_SUBMISSIONS_ARCHIVE_VERSION_TOO_LONG");
        return result;
    }

    private void requireOk(int status) {
        if (status != 200) throw new IllegalStateException("SEC_SUBMISSIONS_ARCHIVE_HTTP:" + status);
    }

    private Snapshot open(Path path, Metadata metadata) throws IOException {
        return new Snapshot(new ZipFile(path.toFile()), mapper, metadata);
    }

    static String normalizeSic(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (!trimmed.matches("[0-9]{3,4}")) return null;
        int code = Integer.parseInt(trimmed);
        return code <= 0 ? null : String.format("%04d", code);
    }

    static Instant parseAcceptedAt(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String trimmed = raw.trim();
        if (trimmed.matches("[0-9]{14}")) {
            return LocalDateTime.parse(trimmed, COMPACT_ET).atZone(NEW_YORK).toInstant();
        }
        return OffsetDateTime.parse(trimmed).toInstant();
    }

    public static class Snapshot implements AutoCloseable {
        private final ZipFile zip;
        private final ObjectMapper mapper;
        private final Metadata metadata;

        Snapshot(ZipFile zip, ObjectMapper mapper, Metadata metadata) throws IOException {
            this.zip = zip;
            this.mapper = mapper;
            this.metadata = metadata;
            if (zip.stream().noneMatch(entry -> entry.getName().matches("CIK[0-9]{10}\\.json"))) {
                zip.close();
                throw new IOException("SEC_SUBMISSIONS_ARCHIVE_INVALID");
            }
        }

        public String version() { return metadata.version(); }

        public Optional<String> readSic(String cik) {
            if (cik == null || !cik.matches("[0-9]{10}")) return Optional.empty();
            ZipEntry entry = zip.getEntry("CIK" + cik + ".json");
            if (entry == null) return Optional.empty();
            JsonNode root = readEntry(entry, cik);
            return Optional.ofNullable(normalizeSic(root.path("sic").asText(null)));
        }

        public Optional<CompanySubmissions> findCompany(String cik) {
            if (cik == null || !cik.matches("[0-9]{10}")) throw new IllegalArgumentException("SEC_SUBMISSIONS_CIK_INVALID");
            ZipEntry entry = zip.getEntry("CIK" + cik + ".json");
            if (entry == null) return Optional.empty();
            JsonNode root = readEntry(entry, cik);
            String sic = normalizeSic(root.path("sic").asText(null));
            Map<String, FilingSubmission> filings = new LinkedHashMap<>();
            LocalDate latestPeriodic = collectColumnar(root.path("filings").path("recent"), filings, null);
            JsonNode files = root.path("filings").path("files");
            if (files.isArray()) {
                for (JsonNode overflow : files) {
                    String filingTo = overflow.path("filingTo").asText("");
                    if (!filingTo.isBlank() && LocalDate.parse(filingTo).isBefore(HISTORY_FLOOR)) continue;
                    String fileName = overflow.path("name").asText("");
                    if (fileName.isBlank()) continue;
                    ZipEntry overflowEntry = zip.getEntry(fileName);
                    if (overflowEntry != null) {
                        latestPeriodic = collectColumnar(readEntry(overflowEntry, cik), filings, latestPeriodic);
                    }
                }
            }
            return Optional.of(new CompanySubmissions(sic, Map.copyOf(filings), latestPeriodic));
        }

        private LocalDate collectColumnar(JsonNode columnar, Map<String, FilingSubmission> target, LocalDate currentLatest) {
            if (columnar == null || !columnar.isObject()) return currentLatest;
            JsonNode accessions = columnar.path("accessionNumber");
            if (!accessions.isArray()) return currentLatest;
            JsonNode forms = columnar.path("form");
            JsonNode filingDates = columnar.path("filingDate");
            JsonNode reportDates = columnar.path("reportDate");
            JsonNode acceptances = columnar.path("acceptanceDateTime");
            LocalDate latestPeriodic = currentLatest;
            int count = accessions.size();
            for (int index = 0; index < count; index++) {
                String accession = textAt(accessions, index);
                String filingText = textAt(filingDates, index);
                if (accession == null || filingText == null) continue;
                LocalDate filingDate = LocalDate.parse(filingText);
                String reportText = textAt(reportDates, index);
                LocalDate reportDate = reportText == null ? null : LocalDate.parse(reportText);
                if (filingDate.isBefore(HISTORY_FLOOR) && (reportDate == null || reportDate.isBefore(HISTORY_FLOOR))) continue;
                String form = textAt(forms, index);
                Instant acceptedAt = parseAcceptedAt(textAt(acceptances, index));
                target.putIfAbsent(accession, new FilingSubmission(accession, form == null ? "" : form, filingDate, reportDate, acceptedAt));
                if (form != null && PERIODIC_FORMS.contains(form) && (latestPeriodic == null || filingDate.isAfter(latestPeriodic))) {
                    latestPeriodic = filingDate;
                }
            }
            return latestPeriodic;
        }

        private String textAt(JsonNode array, int index) {
            if (!array.isArray() || index >= array.size()) return null;
            JsonNode item = array.get(index);
            if (item == null || !item.isTextual()) return null;
            String value = item.asText().trim();
            return value.isEmpty() ? null : value;
        }

        private JsonNode readEntry(ZipEntry entry, String cik) {
            CRC32 checksum = new CRC32();
            try (InputStream input = new CheckedInputStream(zip.getInputStream(entry), checksum)) {
                byte[] bytes = input.readAllBytes();
                if (entry.getSize() != bytes.length || entry.getCrc() != checksum.getValue())
                    throw new IOException("SEC_SUBMISSIONS_ENTRY_CORRUPT:" + cik);
                return mapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(bytes);
            } catch (IOException ex) {
                throw new UncheckedIOException("SEC_SUBMISSIONS_ENTRY_FAILED:" + cik, ex);
            }
        }

        @Override
        public void close() {
            try {
                zip.close();
            } catch (IOException ex) {
                throw new UncheckedIOException("SEC_SUBMISSIONS_ARCHIVE_CLOSE_FAILED", ex);
            }
        }
    }
}
