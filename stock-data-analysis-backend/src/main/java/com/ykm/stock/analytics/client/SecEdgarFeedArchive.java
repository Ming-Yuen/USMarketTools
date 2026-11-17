package com.ykm.stock.analytics.client;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ykm.stock.analytics.converter.sec.SecFinancialFactConverter.FactKey;
import com.ykm.stock.analytics.model.entity.SecFinancialFact;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import static com.ykm.stock.analytics.converter.sec.SecFinancialFactConverter.key;
import static com.ykm.stock.analytics.service.market.MarketStatusService.NY_ZONE;
import static com.ykm.stock.analytics.service.valuation.SecFinancialFactsCalculator.DEPRECIATION_TOTAL_TAGS;

@Slf4j
@Component
public class SecEdgarFeedArchive {
    static final String URL = "https://www.sec.gov/Archives/edgar/Feed/";
    private static final long RANGE_BYTES = 64L * 1024 * 1024;
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;
    private static final Pattern DAILY_FILE = Pattern.compile("([0-9]{8})\\.nc\\.tar\\.gz");
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes ([0-9]+)-([0-9]+)/([0-9]+)");
    private final RestClient http;
    private final ObjectMapper mapper;
    private final Path directory;
    private final LocalDate automaticFrom;
    private final long rangeBytes;
    private final SecFeedCashFlowReader reader = new SecFeedCashFlowReader();

    public record CashFlowFact(FactKey key, BigDecimal value, LocalDate filed, String form, Instant acceptedAt) {}

    private record Evidence(LocalDate feedDate, Set<String> accessions, List<CashFlowFact> facts) {}

    public record Confirmation(Function<List<SecFinancialFact>, Set<FactKey>> facts, List<LocalDate> failedDates,
                               Set<FactKey> missingFacts) {
        public Confirmation(Function<List<SecFinancialFact>, Set<FactKey>> facts, List<LocalDate> failedDates) {
            this(facts, failedDates, Set.of());
        }
    }

    private record ByteRange(long end, long total) {}

    @Autowired
    public SecEdgarFeedArchive(RestClient.Builder builder, ObjectMapper mapper, @Value("${sec.user-agent}") String userAgent,
                               @Value("${sec.edgar-feed.directory:${sec.shares.archive-directory:./data/sec-shares}/edgar-feed}") String directory,
                               @Value("${sec.edgar-feed.automatic-from:2026-10-01}") LocalDate automaticFrom) {
        this(SecCompanyFactsArchive.http(builder, userAgent), mapper, Path.of(directory), automaticFrom, RANGE_BYTES);
    }

    SecEdgarFeedArchive(RestClient http, ObjectMapper mapper, Path directory, LocalDate automaticFrom, long rangeBytes) {
        this.http = http;
        this.mapper = mapper;
        this.directory = directory;
        this.automaticFrom = automaticFrom;
        this.rangeBytes = rangeBytes;
    }

    /** 估值只讀本地已索引的 JSON。 */
    public Session session() { return new Session(false); }

    /** SEC 同步下載及索引未準備的日包。 */
    public Session downloadingSession() { return new Session(true); }

    public LocalDate automaticFrom() { return automaticFrom; }

    public final class Session {
        private final boolean downloadsAllowed;
        private final Map<String, Set<LocalDate>> published = new HashMap<>();
        private final Map<LocalDate, Evidence> checked = new HashMap<>();
        private final Set<LocalDate> failed = new HashSet<>();
        private int archivesOpened;

        private Session(boolean downloadsAllowed) { this.downloadsAllowed = downloadsAllowed; }

        public int archivesOpened() { return archivesOpened; }

        public Confirmation confirm(Collection<SecFinancialFact> facts, Instant cutoff) {
            if (facts.isEmpty()) return new Confirmation(ignored -> Set.of(), List.of());
            LocalDate through = cutoff.atZone(NY_ZONE).toLocalDate();
            Map<LocalDate, List<SecFinancialFact>> dates = new TreeMap<>();
            for (SecFinancialFact fact : facts) {
                if (!"us-gaap".equals(fact.getTaxonomy()) || !DEPRECIATION_TOTAL_TAGS.contains(fact.getTag())
                        || fact.getFiled() == null || !fact.getFiled().isBefore(through)) continue;
                dates.computeIfAbsent(fact.getFiled(), ignored -> new ArrayList<>()).add(fact);
            }
            if (dates.isEmpty()) return new Confirmation(ignored -> Set.of(), List.of());
            Set<LocalDate> failures = new TreeSet<>();
            Set<FactKey> missing = new HashSet<>();
            Map<FactKey, BigDecimal> confirmed = new HashMap<>();
            for (Map.Entry<LocalDate, List<SecFinancialFact>> date : dates.entrySet()) {
                LocalDate filed = date.getKey();
                Evidence primary = load(filed, failures);
                match(primary, date.getValue(), cutoff, confirmed);
                if (primary != null && date.getValue().stream().allMatch(fact -> primary.accessions().contains(fact.getAccession()))) continue;
                if (failures.contains(filed)) continue;
                // 盤後送件可能在接下來的 SEC 營業日才進日包，仍核對原 filed 與 accession。
                if (downloadsAllowed) {
                    LocalDate spillover = nextPublished(filed, through, failures);
                    if (spillover != null) match(load(spillover, failures), date.getValue(), cutoff, confirmed);
                } else {
                    matchCachedSpillovers(filed, through, primary, date.getValue(), cutoff, confirmed, failures, missing);
                }
            }
            return new Confirmation(eligible -> {
                Set<FactKey> result = new HashSet<>();
                for (SecFinancialFact fact : eligible) {
                    BigDecimal amount = confirmed.get(key(fact));
                    if (amount != null && amount.compareTo(fact.getValue()) == 0) result.add(key(fact));
                }
                return result;
            }, List.copyOf(failures), Set.copyOf(missing));
        }

        private void matchCachedSpillovers(LocalDate filed, LocalDate through, Evidence primary, List<SecFinancialFact> candidates,
                                           Instant cutoff, Map<FactKey, BigDecimal> confirmed, Set<LocalDate> failures,
                                           Set<FactKey> missing) {
            Set<String> remaining = new HashSet<>();
            for (SecFinancialFact fact : candidates) {
                if (primary == null || !primary.accessions().contains(fact.getAccession())) remaining.add(fact.getAccession());
            }
            LocalDate last = filed.plusDays(7);
            boolean pending = primary == null && !filed.isBefore(automaticFrom);
            for (LocalDate day = filed.plusDays(1); !day.isAfter(last) && !day.isAfter(through); day = day.plusDays(1)) {
                Evidence cached = load(day, failures);
                if (cached == null) {
                    if (!day.isBefore(automaticFrom) && !failures.contains(day)) pending = true;
                    continue;
                }
                match(cached, candidates, cutoff, confirmed);
                remaining.removeIf(cached.accessions()::contains);
                if (remaining.isEmpty()) return;
            }
            if (pending) {
                for (SecFinancialFact fact : candidates) {
                    if (remaining.contains(fact.getAccession())) missing.add(key(fact));
                }
            }
        }

        private void match(Evidence daily, List<SecFinancialFact> candidates, Instant cutoff,
                           Map<FactKey, BigDecimal> confirmed) {
            if (daily == null) return;
            Map<FactKey, SecFinancialFact> wanted = new HashMap<>();
            for (SecFinancialFact candidate : candidates) wanted.put(key(candidate), candidate);
            for (CashFlowFact fact : daily.facts()) {
                SecFinancialFact input = wanted.get(fact.key());
                if (input == null || !fact.filed().equals(input.getFiled()) || !fact.form().equals(input.getForm())
                        || fact.value().compareTo(input.getValue()) != 0
                        || fact.acceptedAt() != null && fact.acceptedAt().isAfter(cutoff)) continue;
                confirmed.put(fact.key(), fact.value());
            }
        }

        private LocalDate nextPublished(LocalDate after, LocalDate through, Set<LocalDate> failures) {
            for (LocalDate day = after.plusDays(1); !day.isAfter(through) && !day.isAfter(after.plusDays(7)); day = day.plusDays(1)) {
                if (Files.isRegularFile(evidenceFile(day)) || Files.isRegularFile(archiveFile(day))) return day;
                if (day.isBefore(automaticFrom)) continue;
                try {
                    if (published(day).contains(day)) return day;
                } catch (RuntimeException problem) {
                    recordFailure(day, problem, failures);
                    return null;
                }
            }
            return null;
        }

        private Evidence load(LocalDate day, Set<LocalDate> failures) {
            if (failed.contains(day)) {
                failures.add(day);
                return null;
            }
            Evidence cached = checked.get(day);
            if (cached != null) return cached;
            try {
                Path evidenceFile = evidenceFile(day);
                Evidence loaded;
                if (Files.isRegularFile(evidenceFile)) {
                    loaded = mapper.readerFor(Evidence.class).with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readValue(evidenceFile.toFile());
                    if (!day.equals(loaded.feedDate())) throw new IOException("SEC_EDGAR_FEED_EVIDENCE_DATE_MISMATCH");
                } else if (downloadsAllowed) {
                    Path archive = archiveFile(day);
                    boolean download = !Files.isRegularFile(archive);
                    if (download && (day.isBefore(automaticFrom) || !published(day).contains(day))) return null;
                    Files.createDirectories(directory);
                    Path input = download ? download(day) : archive;
                    try {
                        try (InputStream tar = new GZIPInputStream(new BufferedInputStream(Files.newInputStream(input), 1024 * 1024), 1024 * 1024)) {
                            Set<String> accessions = new HashSet<>();
                            loaded = new Evidence(day, accessions, reader.read(tar, accessions));
                        }
                        archivesOpened++;
                        Path temporary = Files.createTempFile(directory, day.format(DAY), ".json.part");
                        try {
                            mapper.writeValue(temporary.toFile(), loaded);
                            Files.move(temporary, evidenceFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                        } finally { Files.deleteIfExists(temporary); }
                        log.info("SEC EDGAR Feed indexed | day={} bytes={} pureDepreciationFacts={}", day, Files.size(input), loaded.facts().size());
                    } finally {
                        if (download) Files.deleteIfExists(input);
                    }
                } else return null;
                checked.put(day, loaded);
                return loaded;
            } catch (IOException | RuntimeException problem) {
                recordFailure(day, problem, failures);
                return null;
            }
        }

        private void recordFailure(LocalDate day, Exception failure, Set<LocalDate> failures) {
            if (failed.add(day)) log.warn("SEC EDGAR Feed failed | day={} reason={}", day, failure.toString());
            failures.add(day);
        }

        private Set<LocalDate> published(LocalDate day) {
            String quarter = quarterUrl(day);
            return published.computeIfAbsent(quarter, url -> {
                JsonNode json = http.get().uri(url + "index.json").retrieve().body(JsonNode.class);
                JsonNode items = json == null ? null : json.path("directory").path("item");
                if (items == null || !items.isArray()) throw new IllegalStateException("SEC_EDGAR_FEED_INVALID_INDEX:" + url);
                Set<LocalDate> dates = new HashSet<>();
                for (JsonNode item : items) {
                    Matcher archive = DAILY_FILE.matcher(item.path("name").asText());
                    if (archive.matches()) dates.add(LocalDate.parse(archive.group(1), DAY));
                }
                return dates;
            });
        }
    }

    private Path evidenceFile(LocalDate day) { return directory.resolve(day.format(DAY) + ".json"); }

    private Path archiveFile(LocalDate day) { return directory.resolve(day.format(DAY) + ".nc.tar.gz"); }

    private String quarterUrl(LocalDate day) { return URL + day.getYear() + "/QTR" + (day.getMonthValue() + 2) / 3 + "/"; }

    private Path download(LocalDate day) throws IOException {
        Path partial = directory.resolve(day.format(DAY) + ".tar.gz.part");
        String url = quarterUrl(day) + archiveFile(day).getFileName();
        // 日包可超過 2 GB。中斷後保留已收完整的 64 MiB 區段，重跑從該處接續。
        try (RandomAccessFile output = new RandomAccessFile(partial.toFile(), "rw")) {
            ByteRange range = null;
            long first = output.length() / rangeBytes * rangeBytes;
            log.info("SEC EDGAR Feed downloading | day={} resumeBytes={}", day, first);
            try {
                do {
                    long last = range == null ? first + rangeBytes - 1 : Math.min(range.total() - 1, first + rangeBytes - 1);
                    range = copyRange(url, output, first, last, range == null ? -1L : range.total());
                    first = range.end() + 1;
                } while (first < range.total());
            } catch (IOException | RuntimeException failure) {
                output.setLength(first);
                throw failure;
            }
        }
        return partial;
    }

    private ByteRange copyRange(String url, RandomAccessFile output, long first, long last, long knownSize) throws IOException {
        for (int attempt = 0; ; attempt++) {
            output.setLength(first);
            output.seek(first);
            try {
                return http.get().uri(url).header(HttpHeaders.RANGE, "bytes=" + first + "-" + last).exchange((request, response) -> {
                    int status = response.getStatusCode().value();
                    HttpHeaders headers = response.getHeaders();
                    if (status == 200) {
                        if (first > 0) throw new IllegalStateException("SEC_EDGAR_FEED_RANGE_IGNORED_ON_RESUME:" + url);
                        output.setLength(0);
                        long size = write(response.getBody(), output);
                        if (size == 0 || headers.getContentLength() >= 0 && headers.getContentLength() != size)
                            throw new IOException("SEC_EDGAR_FEED_INCOMPLETE_DOWNLOAD");
                        return new ByteRange(size - 1, size);
                    }
                    if (status != 206) throw new IllegalStateException("SEC_EDGAR_FEED_HTTP:" + status + ":" + url);
                    Matcher actual = CONTENT_RANGE.matcher(Objects.toString(headers.getFirst(HttpHeaders.CONTENT_RANGE), ""));
                    if (!actual.matches()) throw new IOException("SEC_EDGAR_FEED_INVALID_RANGE");
                    long end = Long.parseLong(actual.group(2));
                    long size = Long.parseLong(actual.group(3));
                    if (Long.parseLong(actual.group(1)) != first || end != Math.min(size - 1, last) || size <= first
                            || knownSize > 0 && knownSize != size || write(response.getBody(), output) != end - first + 1)
                        throw new IOException("SEC_EDGAR_FEED_INCOMPLETE_RANGE");
                    return new ByteRange(end, size);
                });
            } catch (org.springframework.web.client.ResourceAccessException failure) {
                if (attempt != 0) throw failure;
            }
        }
    }

    private long write(InputStream from, RandomAccessFile to) throws IOException {
        byte[] bytes = new byte[256 * 1024];
        long total = 0;
        for (int n; (n = from.read(bytes)) != -1;) {
            to.write(bytes, 0, n);
            total += n;
        }
        return total;
    }
}
