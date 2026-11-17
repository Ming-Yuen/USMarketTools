package com.ykm.stock.analytics.client;

import com.ykm.stock.analytics.model.entity.SecFinancialFact;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static com.ykm.stock.analytics.converter.sec.SecFinancialFactConverter.*;

/** SEC 季度批量檔提供申報主表列名；金額和實際起訖日沿用 Company Facts。 */
@Slf4j
@Component
public class SecFinancialStatementArchive {
    static final String INDEX = "https://www.sec.gov/data-research/sec-markets-data/financial-statement-data-sets";
    private static final Pattern SEC_ZIP = Pattern.compile("https://www\\.sec\\.gov/files/(?:dera/)?data/financial-statement-data-sets/([0-9]{4}q[1-4]\\.zip)");
    private static final Pattern ZIP_NAME = Pattern.compile("[0-9]{4}q[1-4]\\.zip");
    private static final Set<String> DEPRECIATION_TAGS =
            Set.of(DEPRECIATION_AMORTIZATION, DEPRECIATION_AMORTIZATION_ACCRETION, DEPRECIATION_DEPLETION_AMORTIZATION);
    private final Path directory;
    private final RestClient http;

    @Autowired
    public SecFinancialStatementArchive(RestClient.Builder builder, @Value("${sec.user-agent}") String userAgent,
                                        @Value("${sec.shares.archive-directory:./data/sec-shares}") String directory) {
        this(SecCompanyFactsArchive.http(builder, userAgent), directory);
    }

    SecFinancialStatementArchive(RestClient http, String directory) {
        this.http = http;
        this.directory = Path.of(directory);
    }

    public void downloadMissing(int firstYear) {
        String html = http.get().uri(INDEX).retrieve().body(String.class);
        if (html == null) throw new IllegalStateException("SEC_FINANCIAL_STATEMENT_INDEX_EMPTY");
        Map<String, URI> published = new TreeMap<>();
        Jsoup.parse(html, INDEX).select("a[href]").forEach(link -> {
            String url = link.absUrl("href");
            Matcher matcher = SEC_ZIP.matcher(url);
            if (matcher.matches() && Integer.parseInt(matcher.group(1).substring(0, 4)) >= firstYear)
                published.put(matcher.group(1), URI.create(url));
        });
        if (published.isEmpty()) throw new IllegalStateException("SEC_FINANCIAL_STATEMENT_INDEX_HAS_NO_QUARTERS");
        try {
            Files.createDirectories(directory);
            for (Map.Entry<String, URI> quarter : published.entrySet()) {
                Path archive = directory.resolve(quarter.getKey());
                if (Files.notExists(archive)) download(quarter.getValue(), archive);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("SEC_FINANCIAL_STATEMENT_DOWNLOAD_FAILED:" + directory, e);
        }
    }

    private void download(URI source, Path archive) throws IOException {
        Path partial = Files.createTempFile(directory, archive.getFileName().toString(), ".part");
        try {
            http.get().uri(source).exchange((request, response) -> {
                if (response.getStatusCode().value() != 200)
                    throw new IllegalStateException("SEC_FINANCIAL_STATEMENT_HTTP:" + response.getStatusCode().value() + ":" + archive.getFileName());
                Files.copy(response.getBody(), partial, StandardCopyOption.REPLACE_EXISTING);
                return null;
            });
            try (ZipFile zip = new ZipFile(partial.toFile())) {
                for (String table : List.of("sub.txt", "pre.txt", "num.txt")) {
                    if (zip.getEntry(table) == null) throw new IOException("SEC_FINANCIAL_STATEMENT_TABLE_MISSING:" + table);
                }
            }
            Files.move(partial, archive, StandardCopyOption.ATOMIC_MOVE);
            log.info("SEC quarterly archive downloaded | file={}", archive.getFileName());
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    private record Submission(long cik, LocalDate filed, String form, String sic, LocalDate periodEnd) {}
    private record Line(String accession, String tag, String version) {}
    private record Amount(Line line, LocalDate monthEnd, int quarters, String unit) {}
    private record Disclosure(long cik, String accession, LocalDate filed, String form, String tag, LocalDate monthEnd,
                              int quarters, String unit, BigDecimal value) {}

    public record FilingIndustry(LocalDate filed, String sic) {}
    public record ClassFact(String accession, LocalDate filed, String form, String shareClass, LocalDate reportedEnd,
                            LocalDate roundedEnd, int quarters, String unit, BigDecimal value, String tag) {}
    private static final Set<String> CLASS_TAGS = Set.of("EarningsPerShareDiluted", "CommonStockSharesOutstanding");
    private static final Pattern CLASS_MEMBER = Pattern.compile("(?i)ClassOfStock=(?:Common|Capital|Class)?(?:Stock)?(?:Class)?([a-z0-9]);");
    private record Quarter(Instant modified, Set<Disclosure> disclosures, Map<Long, List<FilingIndustry>> industries,
                           Map<Long, List<ClassFact>> classFacts) {}
    private final Map<Path, Quarter> indexedQuarters = new HashMap<>();

    public synchronized Map<String, List<FilingIndustry>> filingIndustries(Set<String> ciks) {
        Map<String, List<FilingIndustry>> result = new HashMap<>();
        for (String cik : ciks) {
            List<FilingIndustry> filings = indexedQuarters.values().stream()
                    .flatMap(quarter -> quarter.industries().getOrDefault(Long.parseLong(cik), List.of()).stream())
                    .distinct().sorted(Comparator.comparing(FilingIndustry::filed).reversed()).toList();
            if (!filings.isEmpty()) result.put(cik, filings);
        }
        return result;
    }

    public synchronized Map<String, List<ClassFact>> classFacts(Set<String> ciks) {
        Map<String, List<ClassFact>> result = new HashMap<>();
        for (String cik : ciks) {
            List<ClassFact> facts = indexedQuarters.values().stream()
                    .flatMap(quarter -> quarter.classFacts().getOrDefault(Long.parseLong(cik), List.of()).stream()).distinct().toList();
            if (!facts.isEmpty()) result.put(cik, facts);
        }
        return result;
    }

    public synchronized Function<List<SecFinancialFact>, Set<FactKey>> loadDepreciation(Set<String> ciks) {
        if (ciks.isEmpty() || !Files.exists(directory)) return facts -> Set.of();
        Set<Long> issuers = new HashSet<>();
        for (String cik : ciks) issuers.add(Long.parseLong(cik));
        Map<Disclosure, Integer> rows = new HashMap<>();
        int archives = 0;
        try (Stream<Path> paths = Files.list(directory)) {
            List<Path> localArchives = paths.filter(file -> ZIP_NAME.matcher(file.getFileName().toString()).matches()).sorted().toList();
            indexedQuarters.keySet().retainAll(localArchives);
            for (Path path : localArchives) {
                Instant modified = Files.getLastModifiedTime(path).toInstant();
                Quarter quarter = indexedQuarters.get(path);
                if (quarter == null || !quarter.modified().equals(modified)) {
                    Set<Disclosure> disclosures = new HashSet<>();
                    Map<Long, List<FilingIndustry>> industries = new HashMap<>();
                    Map<Long, List<ClassFact>> classFacts = new HashMap<>();
                    readQuarter(path, disclosures, industries, classFacts);
                    quarter = new Quarter(modified, disclosures, industries, classFacts);
                    indexedQuarters.put(path, quarter);
                }
                for (Disclosure disclosure : quarter.disclosures()) {
                    if (issuers.contains(disclosure.cik())) rows.merge(disclosure, 1, Integer::sum);
                }
                archives++;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("SEC_FINANCIAL_STATEMENT_ARCHIVE_READ_FAILED:" + directory, e);
        }
        Set<Disclosure> amounts = new HashSet<>();
        rows.forEach((row, count) -> { if (count == 1) amounts.add(row); });
        log.info("SEC quarterly depreciation loaded | archives={} issuers={} amounts={}", archives, issuers.size(), amounts.size());
        return facts -> {
            Map<Disclosure, FactKey> matched = new HashMap<>();
            Set<Disclosure> ambiguous = new HashSet<>();
            for (SecFinancialFact fact : facts) {
                Disclosure disclosure = disclosure(fact);
                if (!amounts.contains(disclosure)) continue;
                FactKey key = key(fact);
                FactKey prior = matched.putIfAbsent(disclosure, key);
                if (prior != null && !prior.equals(key)) ambiguous.add(disclosure);
            }
            ambiguous.forEach(matched::remove);
            return new HashSet<>(matched.values());
        };
    }

    private Disclosure disclosure(SecFinancialFact fact) {
        if (!DEPRECIATION_TAGS.contains(fact.getTag()) || fact.getPeriodStart() == null || fact.getValue().signum() < 0) return null;
        int quarters = (int) Math.round((ChronoUnit.DAYS.between(fact.getPeriodStart(), fact.getPeriodEnd()) + 1) / (365.25 / 4));
        LocalDate end = fact.getPeriodEnd();
        LocalDate monthEnd = YearMonth.from(end).atEndOfMonth();
        LocalDate previousMonthEnd = end.withDayOfMonth(1).minusDays(1);
        if (ChronoUnit.DAYS.between(previousMonthEnd, end) < ChronoUnit.DAYS.between(end, monthEnd)) monthEnd = previousMonthEnd;
        return new Disclosure(Long.parseLong(fact.getCik()), fact.getAccession(), fact.getFiled(), fact.getForm(),
                fact.getTag(), monthEnd, quarters, fact.getUnit(), fact.getValue().stripTrailingZeros());
    }

    private void readQuarter(Path path, Set<Disclosure> rows, Map<Long, List<FilingIndustry>> industries,
                             Map<Long, List<ClassFact>> classFacts) throws IOException {
        try (ZipFile zip = new ZipFile(path.toFile())) {
            Map<String, Submission> submissions = new HashMap<>();
            read(zip, "sub", List.of("adsh", "cik", "filed", "form"), accession -> true, (accession, row) -> {
                long cik = Long.parseLong(row.apply("cik"));
                String period = row.apply("period");
                Submission sub = new Submission(cik, date(row.apply("filed")), row.apply("form"), row.apply("sic"), period.isBlank() ? null : date(period));
                submissions.put(accession, sub);
                if (!sub.sic().isBlank()) industries.computeIfAbsent(cik, key -> new ArrayList<>()).add(new FilingIndustry(sub.filed(), sub.sic()));
            });
            if (submissions.isEmpty()) return;
            Map<Line, Boolean> labels = new HashMap<>();
            read(zip, "pre", List.of("adsh", "stmt", "inpth", "tag", "version", "plabel", "negating"), submissions::containsKey, (accession, row) -> {
                if (!"CF".equals(row.apply("stmt")) || !"0".equals(row.apply("inpth"))
                        || !DEPRECIATION_TAGS.contains(row.apply("tag")) || !row.apply("version").startsWith("us-gaap/")) return;
                Line line = new Line(accession, row.apply("tag"), row.apply("version"));
                // 同一標籤出現在多個現金流量表列時，NUM 無法確認數值屬於哪一列。
                boolean pure = "0".equals(row.apply("negating"))
                        && SecFeedCashFlowReader.PURE_LABELS.contains(row.apply("plabel").strip().toLowerCase(Locale.ROOT));
                labels.merge(line, pure, (first, second) -> false);
            });
            Set<String> labelled = new HashSet<>();
            labels.forEach((line, pure) -> { if (pure) labelled.add(line.accession()); });
            Map<Amount, List<BigDecimal>> numbers = new HashMap<>();
            read(zip, "num", List.of("adsh", "tag", "version", "ddate", "qtrs", "uom", "coreg", "value"), submissions::containsKey, (accession, row) -> {
                if (!row.apply("coreg").isBlank() || row.apply("value").isBlank()) return;
                String tag = row.apply("tag");
                String segments = row.apply("segments");
                if (CLASS_TAGS.contains(tag) && row.apply("version").startsWith("us-gaap/") && !segments.isBlank()) {
                    Matcher member = CLASS_MEMBER.matcher(segments);
                    if (member.matches()) {
                        Submission sub = submissions.get(accession);
                        classFacts.computeIfAbsent(sub.cik(), key -> new ArrayList<>()).add(new ClassFact(accession, sub.filed(), sub.form(),
                                member.group(1).toUpperCase(Locale.ROOT), sub.periodEnd(), date(row.apply("ddate")), Integer.parseInt(row.apply("qtrs")),
                                row.apply("uom"), new BigDecimal(row.apply("value")).stripTrailingZeros(), tag));
                    }
                    return;
                }
                if (!labelled.contains(accession) || !segments.isBlank()) return;
                Line line = new Line(accession, row.apply("tag"), row.apply("version"));
                if (!Boolean.TRUE.equals(labels.get(line))) return;
                Amount key = new Amount(line, date(row.apply("ddate")), Integer.parseInt(row.apply("qtrs")), row.apply("uom"));
                numbers.computeIfAbsent(key, ignored -> new ArrayList<>(1)).add(new BigDecimal(row.apply("value")).stripTrailingZeros());
            });
            for (Map.Entry<Amount, List<BigDecimal>> entry : numbers.entrySet()) {
                Amount key = entry.getKey();
                if (entry.getValue().size() != 1 || key.quarters() < 1) continue;
                Submission sub = submissions.get(key.line().accession());
                Disclosure disclosure = new Disclosure(sub.cik(), key.line().accession(), sub.filed(), sub.form(), key.line().tag(),
                        key.monthEnd(), key.quarters(), key.unit(), entry.getValue().getFirst());
                rows.add(disclosure);
            }
        } catch (IOException e) {
            throw new IOException("SEC_FINANCIAL_STATEMENT_ZIP:" + path, e);
        }
    }

    private LocalDate date(String value) {
        return LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE);
    }

    private void read(ZipFile zip, String table, List<String> required, Predicate<String> wanted,
                      BiConsumer<String, Function<String, String>> accept) throws IOException {
        ZipEntry entry = zip.getEntry(table + ".txt");
        if (entry == null) throw new IOException("SEC_FINANCIAL_STATEMENT_TABLE_MISSING:" + table);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header == null) throw new IOException("SEC_FINANCIAL_STATEMENT_HEADER_MISSING:" + table);
            List<String> columns = Arrays.asList(header.split("\\t", -1));
            if (!columns.containsAll(required) || !"adsh".equals(columns.getFirst()))
                throw new IOException("SEC_FINANCIAL_STATEMENT_COLUMNS_MISSING:" + table);
            Map<String, Integer> indexes = new HashMap<>();
            for (int index = 0; index < columns.size(); index++) indexes.put(columns.get(index), index);
            for (String line; (line = reader.readLine()) != null;) {
                int firstTab = line.indexOf('\t');
                if (firstTab < 0) continue;
                String accession = line.substring(0, firstTab);
                if (!wanted.test(accession)) continue;
                if ("num".equals(table) && "tag".equals(columns.get(1))) {
                    int secondTab = line.indexOf('\t', firstTab + 1);
                    if (secondTab < 0) continue;
                    String tag = line.substring(firstTab + 1, secondTab);
                    if (!CLASS_TAGS.contains(tag) && !DEPRECIATION_TAGS.contains(tag)) continue;
                }
                String[] cells = line.split("\\t", -1);
                accept.accept(accession, column -> {
                    Integer index = indexes.get(column);
                    return index == null || index >= cells.length ? "" : cells[index];
                });
            }
        }
    }
}
