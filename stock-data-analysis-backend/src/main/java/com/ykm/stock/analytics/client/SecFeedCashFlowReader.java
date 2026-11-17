package com.ykm.stock.analytics.client;

import com.ykm.stock.analytics.client.SecEdgarFeedArchive.CashFlowFact;
import com.ykm.stock.analytics.converter.sec.SecFinancialFactConverter.FactKey;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.ykm.stock.analytics.service.market.MarketStatusService.NY_ZONE;
import static com.ykm.stock.analytics.service.valuation.SecFinancialFactsCalculator.DEPRECIATION_TOTAL_TAGS;

/** 從整批 Feed 的原始申報，核對標準標籤在合併現金流量表上的列名。 */
final class SecFeedCashFlowReader {
    private static final String GAAP = "http://fasb.org/us-gaap/";
    private static final Pattern SUBMISSION_ENTRY = Pattern.compile("(?:.*/)?([0-9]{10}-[0-9]{2}-[0-9]{6})\\.nc");
    private static final Pattern DOCUMENT = Pattern.compile("(?im)^<DOCUMENT>[ \\t]*\\r?$");
    private static final Pattern DOCUMENT_END = Pattern.compile("(?im)^</DOCUMENT>[ \\t]*\\r?$");
    private static final Pattern HTML = Pattern.compile("(?i)<html\\b");
    private static final Pattern TAGGED_NON_FRACTION = Pattern.compile("(?i)<(?:[a-z0-9._-]+:)?nonFraction\\b");
    private static final Pattern DURATION_VALUE = Pattern.compile("[0-9]+(?:\\.[0-9]+)?");
    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\p{Z}]+");
    private static final Set<String> FORMS = Set.of("10-K", "10-Q", "10-KT", "10-QT", "20-F", "40-F", "6-K", "8-K");
    private static final Set<String> OPERATING = Set.of("NetCashProvidedByUsedInOperatingActivities", "NetCashProvidedByUsedInOperatingActivitiesContinuingOperations");
    private static final Set<String> INVESTING = Set.of("NetCashProvidedByUsedInInvestingActivities", "NetCashProvidedByUsedInInvestingActivitiesContinuingOperations");
    private static final Set<String> FINANCING = Set.of("NetCashProvidedByUsedInFinancingActivities", "NetCashProvidedByUsedInFinancingActivitiesContinuingOperations");
    static final Set<String> PURE_LABELS = Set.of("depreciation and amortization", "depreciation and amortization expense",
            "depreciation and amortization expenses", "depreciation & amortization", "depreciation & amortization expense",
            "depreciation & amortization expenses", "depreciation amortization", "amortization and depreciation",
            "provision for depreciation and amortization");
    private static final DateTimeFormatter ACCEPTANCE = DateTimeFormatter.ofPattern("uuuuMMddHHmmss");

    private record Context(String cik, LocalDate start, LocalDate end) {}
    private record PresentedFact(FactKey key, BigDecimal value) {}

    List<CashFlowFact> read(InputStream tar, Set<String> accessions) throws IOException {
        Map<CashFlowFact, Boolean> presented = new LinkedHashMap<>();
        byte[] header = new byte[512];
        while (tar.readNBytes(header, 0, header.length) == header.length) {
            if (header[0] == 0) {
                // 讀到 gzip 結尾，讓壓縮檔 CRC 與尾段一併驗證。
                tar.transferTo(java.io.OutputStream.nullOutputStream());
                return presented.entrySet().stream().filter(Map.Entry::getValue).map(Map.Entry::getKey).toList();
            }
            long size = entrySize(header);
            Matcher accession = SUBMISSION_ENTRY.matcher(tarField(header, 0, 100));
            if ((header[156] == '0' || header[156] == 0) && accession.matches()) {
                accessions.add(accession.group(1));
                readSubmission(tar, size, accession.group(1), presented);
            } else tar.skipNBytes(size);
            tar.skipNBytes((512 - size % 512) % 512);
        }
        throw new IOException("SEC_EDGAR_FEED_TAR_TRUNCATED");
    }

    private void readSubmission(InputStream tar, long size, String accession, Map<CashFlowFact, Boolean> result) throws IOException {
        // 先讀申報頭；基金、持股申報及其他不含合併財報的附件按 tar 長度跳過。
        byte[] prefix = tar.readNBytes((int) Math.min(8192, size));
        if (prefix.length != Math.min(8192, size)) throw new IOException("SEC_EDGAR_FEED_ENTRY_TRUNCATED:" + accession);
        String submissionHeader = new String(prefix, StandardCharsets.UTF_8);
        int headerEnd = submissionHeader.indexOf("<DOCUMENT>");
        String header = headerEnd < 0 ? submissionHeader : submissionHeader.substring(0, headerEnd);
        String form = field(header, "TYPE");
        String filedDate = field(header, "FILING-DATE");
        if (!FORMS.contains(form.replaceFirst("/A$", "")) || !accession.equals(field(header, "ACCESSION-NUMBER"))
                || !filedDate.matches("[0-9]{8}")) {
            tar.skipNBytes(size - prefix.length);
            return;
        }
        if (size > Integer.MAX_VALUE) throw new IOException("SEC_EDGAR_FEED_SUBMISSION_TOO_LARGE:" + accession);
        byte[] remainder = tar.readNBytes((int) size - prefix.length);
        if (remainder.length != size - prefix.length) throw new IOException("SEC_EDGAR_FEED_ENTRY_TRUNCATED:" + accession);
        LocalDate filed = LocalDate.parse(filedDate, DateTimeFormatter.BASIC_ISO_DATE);
        String stamp = field(header, "ACCEPTANCE-DATETIME");
        Instant accepted = stamp.matches("[0-9]{14}") ? LocalDateTime.parse(stamp, ACCEPTANCE).atZone(NY_ZONE).toInstant() : null;
        String submission = submissionHeader + new String(remainder, StandardCharsets.UTF_8);
        Map<PresentedFact, Boolean> filings = new HashMap<>();
        Matcher document = DOCUMENT.matcher(submission);
        Matcher end = DOCUMENT_END.matcher(submission);
        int offset = 0;
        while (document.find(offset) && end.find(document.end())) {
            String markup = submission.substring(document.end(), end.start());
            String type = field(markup, "TYPE");
            if ((form.equals(type) || type.startsWith("EX-99") && (form.startsWith("6-K") || form.startsWith("8-K")))
                    && HTML.matcher(markup).find() && TAGGED_NON_FRACTION.matcher(markup).find()
                    && DEPRECIATION_TOTAL_TAGS.stream().anyMatch(markup::contains)) {
                readCashFlows(Jsoup.parse(markup), accession, filings);
            }
            offset = end.end();
        }
        for (Map.Entry<PresentedFact, Boolean> entry : filings.entrySet()) {
            PresentedFact fact = entry.getKey();
            result.merge(new CashFlowFact(fact.key(), fact.value(), filed, form, accepted), entry.getValue(), (first, second) -> first && second);
        }
    }

    private void readCashFlows(Document doc, String accession, Map<PresentedFact, Boolean> found) {
        Map<String, Context> contexts = new HashMap<>();
        Map<String, String> units = new HashMap<>();
        for (Element element : doc.getAllElements()) {
            if (localName(element.tagName()).equals("context")) {
                Context context = context(element);
                if (context != null) contexts.put(element.id(), context);
            } else if (localName(element.tagName()).equals("unit") && element.childrenSize() == 1) {
                Element measure = element.child(0);
                String unit = measure.text().strip();
                int separator = unit.indexOf(':');
                if (localName(measure.tagName()).equals("measure") && separator > 0
                        && "http://www.xbrl.org/2003/iso4217".equals(namespace(measure, unit.substring(0, separator))))
                    units.put(element.id(), unit.substring(separator + 1));
            }
        }
        for (Element table : doc.getElementsByTag("table")) {
            List<Element> totals = new ArrayList<>();
            Set<String> tags = new HashSet<>();
            for (Element element : table.getAllElements()) {
                if (!localName(element.tagName()).equals("nonfraction") || element.closest("table") != table) continue;
                String name = standardTag(element);
                if (name.isEmpty()) continue;
                tags.add(name);
                if (DEPRECIATION_TOTAL_TAGS.contains(name)) totals.add(element);
            }
            if (totals.isEmpty()) continue;
            String text = normalize(table.text());
            if (!containsSection(tags, OPERATING, text, "operating activities")
                    || !containsSection(tags, INVESTING, text, "investing activities")
                    || !containsSection(tags, FINANCING, text, "financing activities")) continue;
            for (Element total : totals) {
                Context context = contexts.get(total.attr("contextref"));
                String currency = units.get(total.attr("unitref"));
                BigDecimal value = value(total);
                String label = rowLabel(total);
                if (context == null || currency == null || value == null || value.signum() < 0 || label.isEmpty()) continue;
                FactKey key = new FactKey(context.cik(), "us-gaap", standardTag(total), currency, context.start(), context.end(), accession);
                found.merge(new PresentedFact(key, value), PURE_LABELS.contains(label), (first, second) -> first && second);
            }
        }
    }

    private Context context(Element element) {
        String cik = "";
        String start = "";
        String end = "";
        for (Element child : element.getAllElements()) {
            switch (localName(child.tagName())) {
                case "segment", "scenario" -> { if (child.childrenSize() > 0) return null; }
                case "identifier" -> {
                    if (!child.attr("scheme").equals("http://www.sec.gov/CIK") && !child.attr("scheme").equals("https://www.sec.gov/CIK")) return null;
                    cik = child.text().strip();
                }
                case "startdate" -> start = child.text().strip();
                case "enddate" -> end = child.text().strip();
                default -> {}
            }
        }
        if (!cik.matches("[0-9]{1,10}") || start.isEmpty() || end.isEmpty()) return null;
        return new Context("0".repeat(10 - cik.length()) + cik, LocalDate.parse(start), LocalDate.parse(end));
    }

    private String standardTag(Element element) {
        String name = element.attr("name");
        int colon = name.indexOf(':');
        return colon > 0 && namespace(element, name.substring(0, colon)).startsWith(GAAP) ? name.substring(colon + 1) : "";
    }

    private String namespace(Element element, String prefix) {
        String attribute = "xmlns:" + prefix;
        for (Element parent = element; parent != null; parent = parent.parent()) {
            if (parent.hasAttr(attribute)) return parent.attr(attribute).replaceFirst("^https:", "http:");
        }
        return "";
    }

    private boolean containsSection(Set<String> tags, Set<String> subtotals, String text, String heading) {
        return !Collections.disjoint(tags, subtotals) || text.contains(heading);
    }

    private String rowLabel(Element fact) {
        Element row = fact.closest("tr");
        if (row == null) return "";
        for (Element cell : row.children()) {
            Element labelCell = cell.clone();
            for (Element element : labelCell.getAllElements()) {
                if ("nonfraction".equals(localName(element.tagName()))) element.remove();
            }
            String label = normalize(labelCell.text());
            if (label.chars().anyMatch(Character::isLetter)) return label;
        }
        return "";
    }

    private BigDecimal value(Element fact) {
        if (fact.hasAttr("xsi:nil") && "true".equalsIgnoreCase(fact.attr("xsi:nil"))) return null;
        String format = localName(fact.attr("format")).toLowerCase(Locale.ROOT);
        if ("numdash".equals(format) || "fixed-zero".equals(format)) return BigDecimal.ZERO;
        if (!format.isEmpty() && !Set.of("num-dot-decimal", "numdotdecimal", "num-comma-decimal", "numcommadecimal").contains(format)) return null;
        String number = WHITESPACE.matcher(fact.text()).replaceAll("");
        number = format.contains("comma") ? number.replace(".", "").replace(',', '.') : number.replace(",", "");
        if (!DURATION_VALUE.matcher(number).matches()) return null;
        int scale = fact.attr("scale").isBlank() ? 0 : Integer.parseInt(fact.attr("scale"));
        BigDecimal value = new BigDecimal(number).scaleByPowerOfTen(scale);
        return ("-".equals(fact.attr("sign")) ? value.negate() : value).stripTrailingZeros();
    }

    private String normalize(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replace("&", " and ");
        return WHITESPACE.matcher(normalized).replaceAll(" ").strip().replaceFirst("[.:]+$", "");
    }

    private String localName(String tag) { return tag.substring(tag.indexOf(':') + 1); }

    private String field(String header, String name) {
        String label = "<" + name + ">";
        int start = header.indexOf(label);
        if (start < 0) return "";
        int valueStart = start + label.length();
        int end = header.indexOf('\n', valueStart);
        String value = header.substring(valueStart, end < 0 ? header.length() : end).strip();
        int closing = value.indexOf('<');
        return closing < 0 ? value : value.substring(0, closing).strip();
    }

    private long entrySize(byte[] header) throws IOException {
        try {
            return Long.parseLong(tarField(header, 124, 12).strip(), 8);
        } catch (NumberFormatException e) { throw new IOException("SEC_EDGAR_FEED_TAR_INVALID_SIZE", e); }
    }

    private String tarField(byte[] header, int offset, int length) {
        int end = offset;
        while (end < offset + length && header[end] != 0) end++;
        return new String(header, offset, end - offset, StandardCharsets.US_ASCII);
    }
}
