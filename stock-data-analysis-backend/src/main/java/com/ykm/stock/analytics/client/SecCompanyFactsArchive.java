package com.ykm.stock.analytics.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ykm.common.common_lib.service.DateTimeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
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
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** 每輪確認 SEC ZIP 版本，只打開一個 ZipFile；舊的證據目錄只讀，不在原 hardlink 上覆寫。 */
@Component
public class SecCompanyFactsArchive {
    static final String URL = "https://www.sec.gov/Archives/edgar/daily-index/xbrl/companyfacts.zip";
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private final RestClient http;
    private final ObjectMapper mapper;
    private final DateTimeService clock;
    private final Path directory;
    private final Path reuseDirectory;
    private final String userAgent;

    public record Metadata(String etag, String lastModified, Instant downloadedAt) {
        public String version() { return etag + "|" + lastModified; }
    }

    @Autowired
    public SecCompanyFactsArchive(RestClient.Builder builder, ObjectMapper mapper, DateTimeService clock,
                                  @Value("${sec.user-agent}") String userAgent,
                                  @Value("${sec.shares.archive-directory:./data/sec-shares}") String directory,
                                  @Value("${sec.shares.reuse-directory:./data}") String reuseDirectory) {
        this(http(builder, userAgent), mapper, clock, userAgent, directory, reuseDirectory);
    }

    SecCompanyFactsArchive(RestClient http, ObjectMapper mapper, DateTimeService clock,
                           String userAgent, String directory, String reuseDirectory) {
        this.http = http;
        this.mapper = mapper;
        this.clock = clock;
        this.userAgent = userAgent;
        this.directory = Path.of(directory).toAbsolutePath().normalize();
        this.reuseDirectory = Path.of(reuseDirectory).toAbsolutePath().normalize();
    }

    static RestClient http(RestClient.Builder builder, String userAgent) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(20_000);
        factory.setReadTimeout(180_000);
        return builder.clone().requestFactory(factory).defaultHeader(HttpHeaders.USER_AGENT, userAgent)
                .defaultHeader(HttpHeaders.ACCEPT_ENCODING, "identity").build();
    }

    public Snapshot prepare() {
        String contact = userAgent == null ? "" : userAgent.toLowerCase(Locale.ROOT);
        if (!contact.contains("@") || contact.contains("@localhost") || contact.contains("example.com"))
            throw new IllegalStateException("SEC_USER_AGENT requires application name and real contact email");
        try {
            Path cached = directory.resolve("companyfacts.zip");
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
            // 只讀既存的舊同步快取；版本不符就下載到新目錄，不修改原檔。
            Path legacyMetadata = reuseDirectory.resolve("sec-bulk-metadata.json");
            Path legacyZip = reuseDirectory.resolve("companyfacts.zip");
            if (Files.isRegularFile(legacyZip) && Files.isRegularFile(legacyMetadata)) {
                JsonNode legacy = mapper.readTree(legacyMetadata.toFile()).path("companyfacts.zip");
                if (remote.etag().equals(legacy.path("etag").asText()) && remote.lastModified().equals(legacy.path("lastModified").asText()))
                    return open(legacyZip, remote);
            }
            Files.createDirectories(directory);
            return http.get().uri(URL).header(HttpHeaders.CACHE_CONTROL, "no-cache").exchange((request, response) -> {
                requireOk(response.getStatusCode().value());
                Metadata responseVersion = metadata(response.getHeaders());
                Path partial = Files.createTempFile(directory, "companyfacts-", ".part");
                try {
                    long size = Files.copy(response.getBody(), partial, StandardCopyOption.REPLACE_EXISTING);
                    if (size == 0 || response.getHeaders().getContentLength() >= 0 && size != response.getHeaders().getContentLength())
                        throw new IOException("SEC_SHARE_ARCHIVE_TRUNCATED");
                    Metadata downloaded = new Metadata(responseVersion.etag(), responseVersion.lastModified(), clock.now());
                    try (Snapshot ignored = open(partial, downloaded)) { /* 發布前確認 ZIP 目錄可讀。 */ }
                    Files.move(partial, cached, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    Path metadataPartial = Files.createTempFile(directory, "archive-", ".part");
                    try {
                        mapper.writeValue(metadataPartial.toFile(), downloaded);
                        Files.move(metadataPartial, metadataFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } finally { Files.deleteIfExists(metadataPartial); }
                    return open(cached, downloaded);
                } finally { Files.deleteIfExists(partial); }
            });
        } catch (IOException ex) { throw new UncheckedIOException("SEC_SHARE_ARCHIVE_FAILED", ex); }
    }

    private Metadata metadata(HttpHeaders headers) {
        String etag = resolveVersionToken(headers);
        String modified = headers.getFirst(HttpHeaders.LAST_MODIFIED);
        if (etag == null || etag.isBlank() || modified == null || modified.isBlank()) throw new IllegalStateException("SEC_SHARE_ARCHIVE_VERSION_MISSING");
        Metadata result = new Metadata(etag, modified, clock.now());
        ZonedDateTime.parse(modified, DateTimeFormatter.RFC_1123_DATE_TIME);
        if (result.version().length() > 512) throw new IllegalStateException("SEC_SHARE_ARCHIVE_VERSION_TOO_LONG");
        return result;
    }

    static String resolveVersionToken(HttpHeaders headers) {
        String etag = headers.getETag();
        if (etag != null && !etag.isBlank()) return etag;
        String s3VersionId = headers.getFirst("x-amz-version-id");
        if (s3VersionId != null && !s3VersionId.isBlank()) return s3VersionId.trim();
        return null;
    }

    private void requireOk(int status) {
        if (status != 200) throw new IllegalStateException("SEC_SHARE_ARCHIVE_HTTP:" + status);
    }

    private Snapshot open(Path path, Metadata metadata) throws IOException {
        return new Snapshot(new ZipFile(path.toFile()), mapper, metadata);
    }

    public static final class EntryMissingException extends IllegalStateException {
        public EntryMissingException(String cik) {
            super("SEC_SHARE_ENTRY_MISSING:" + cik);
        }
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
                throw new IOException("SEC_SHARE_ARCHIVE_INVALID");
            }
        }

        public String version() { return metadata.version(); }

        public JsonNode read(String cik) {
            if (!cik.matches("[0-9]{10}")) throw new IllegalArgumentException("SEC_SHARE_CIK_INVALID");
            ZipEntry entry = zip.getEntry("CIK" + cik + ".json");
            if (entry == null) throw new EntryMissingException(cik);
            CRC32 checksum = new CRC32();
            try (InputStream input = new CheckedInputStream(zip.getInputStream(entry), checksum)) {
                byte[] bytes = input.readAllBytes();
                if (entry.getSize() != bytes.length || entry.getCrc() != checksum.getValue()) throw new IOException("SEC_SHARE_ENTRY_CORRUPT:" + cik);
                return mapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(bytes);
            } catch (IOException ex) { throw new UncheckedIOException("SEC_SHARE_ENTRY_FAILED:" + cik, ex); }
        }

        @Override
        public void close() {
            try { zip.close(); }
            catch (IOException ex) { throw new UncheckedIOException("SEC_SHARE_ARCHIVE_CLOSE_FAILED", ex); }
        }
    }
}
