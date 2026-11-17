package com.ykm.stock.analytics.service.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.model.dto.finnhub.response.EarningsCalendarResponse;
import com.ykm.stock.analytics.model.entity.MarketEvent;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.repository.jpa.MarketEventRepository;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class MarketEventSyncService {
    private static final String BLS_ICS_URL = "https://www.bls.gov/schedule/news_release/bls.ics";
    private static final String FOMC_URL = "https://www.federalreserve.gov/monetarypolicy/fomccalendars.htm";
    private static final String COMPASS_CALENDAR_URL = "https://compasseconomiccalendar.github.io/calendar.json";
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private final MarketEventRepository events;
    private final DateTimeService dateTimeService;
    private final StockRepository stocks;
    private final FinnhubService finnhubService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private final RestClient publicRestClient = RestClient.builder().defaultHeader("User-Agent", "MarketScope/1.0 market-calendar-sync").build();

    /** 先完成一個來源的解析，再原子保存目前日程；不建立證據或覆蓋率表。 */
    public int syncMacroCalendar(LocalDate from, LocalDate through) {
        List<MarketEvent> rows;
        try { rows = downloadBls(from, through); }
        catch (RuntimeException failure) {
            log.warn("BLS calendar unavailable; use existing Compass fallback: {}", failure.getMessage());
            rows = downloadCompass(from, through);
        }
        save(rows);
        return rows.size();
    }

    public int syncFederalReserveCalendar(LocalDate from, LocalDate through) {
        String html = publicRestClient.get().uri(FOMC_URL).retrieve().body(String.class);
        if (html == null || html.isBlank()) throw new IllegalStateException("FOMC calendar response is empty");
        Document document = Jsoup.parse(html, FOMC_URL);
        if (document.select("div.fomc-meeting").isEmpty()) throw new IllegalStateException("FOMC calendar schema contains no meeting nodes");
        List<MarketEvent> rows = new ArrayList<>();
        for (Element panel : document.select("div.panel")) {
            Element heading = panel.selectFirst("h4");
            if (heading == null) continue;
            String yearText = heading.text().replaceAll("\\D", "");
            if (yearText.length() != 4) continue;
            for (Element meeting : panel.select("div.fomc-meeting")) {
                Element month = meeting.selectFirst(".fomc-meeting__month");
                Element day = meeting.selectFirst(".fomc-meeting__date");
                if (month == null || day == null) continue;
                LocalDate date = parseFomcDecisionDate(Integer.parseInt(yearText), month.text(), day.text());
                if (date == null || date.isBefore(from) || date.isAfter(through)) continue;
                rows.add(event("MACRO|FOMC|" + date, null, "MACRO", "FOMC", "FOMC interest-rate decision", date,
                        "DATE_ONLY", null, "Federal Reserve Board", FOMC_URL, "FOMC-" + date));
            }
        }
        save(rows);
        return rows.size();
    }

    /** Finnhub 是財報日曆的單一來源；富途 Job 只存 EPS/收入預期。 */
    public int syncEarningsCalendar(LocalDate from, LocalDate through) {
        EarningsCalendarResponse response = finnhubService.getEarningsCalendar(from, through);
        if (response == null || response.getEarningsCalendar() == null) throw new IllegalStateException("Finnhub earnings calendar unavailable");
        Set<String> symbols = response.getEarningsCalendar().stream().filter(Objects::nonNull)
                .map(EarningsCalendarResponse.EarningsCalendarEntry::getSymbol).filter(Objects::nonNull)
                .map(value -> value.toUpperCase(Locale.ROOT)).collect(Collectors.toSet());
        Map<String, List<Stock>> identities = symbols.isEmpty() ? Map.of()
                : stocks.findBySymbolIn(symbols).stream().collect(Collectors.groupingBy(Stock::getSymbol));
        Map<String, MarketEvent> rows = new LinkedHashMap<>();
        for (EarningsCalendarResponse.EarningsCalendarEntry entry : response.getEarningsCalendar()) {
            if (entry == null || entry.getDate() == null || entry.getSymbol() == null
                    || entry.getDate().isBefore(from) || entry.getDate().isAfter(through)) continue;
            List<Stock> matches = identities.getOrDefault(entry.getSymbol().toUpperCase(Locale.ROOT), List.of());
            if (matches.size() != 1) continue;
            Stock stock = matches.getFirst();
            String period = entry.getYear() != null && entry.getQuarter() != null
                    ? entry.getYear() + "-Q" + entry.getQuarter() : entry.getDate().toString();
            String key = "EARNINGS|" + stock.getFigi() + "|" + period;
            MarketEvent row = event(key, stock.getFigi(), "EARNINGS", "EARNINGS_REPORT", stock.getSymbol() + " earnings",
                    entry.getDate(), earningsPrecision(entry.getHour()), null, "Finnhub",
                    "https://finnhub.io/docs/api/earnings-calendar", period);
            MarketEvent previous = rows.putIfAbsent(key, row);
            if (previous != null && !previous.equals(row)) throw new IllegalStateException("EARNINGS_CALENDAR_CONFLICT:" + key);
        }
        save(List.copyOf(rows.values()));
        return rows.size();
    }

    private List<MarketEvent> downloadBls(LocalDate from, LocalDate through) {
        String text = publicRestClient.get().uri(BLS_ICS_URL).retrieve().body(String.class);
        if (text == null || text.isBlank()) throw new IllegalStateException("BLS calendar response is empty");
        List<Map<String, String>> parsed = parseIcs(text);
        if (parsed.isEmpty()) throw new IllegalStateException("BLS calendar schema contains no VEVENT records");
        List<MarketEvent> rows = new ArrayList<>();
        for (Map<String, String> item : parsed) {
            String title = unescapeIcs(item.get("SUMMARY"));
            if (!isImportantBlsRelease(title)) continue;
            ParsedEventTime time = parseIcsDateTime(item);
            if (time == null || time.date().isBefore(from) || time.date().isAfter(through)) continue;
            String subtype = blsSubtype(title);
            rows.add(event("MACRO|" + subtype + "|" + time.date(), null, "MACRO", subtype, title, time.date(),
                    time.precision(), time.occurrenceAt(), "U.S. Bureau of Labor Statistics", BLS_ICS_URL,
                    item.getOrDefault("UID", title + "-" + time.date())));
        }
        return rows;
    }

    private List<MarketEvent> downloadCompass(LocalDate from, LocalDate through) {
        String json = publicRestClient.get().uri(COMPASS_CALENDAR_URL).retrieve().body(String.class);
        if (json == null || json.isBlank()) throw new IllegalStateException("Fallback economic calendar response is empty");
        try {
            JsonNode source = objectMapper.readTree(json).path("events");
            if (!source.isArray()) throw new IllegalStateException("Fallback economic calendar schema missing events array");
            List<MarketEvent> rows = new ArrayList<>();
            for (JsonNode item : source) {
                String type = item.path("event_type").asText();
                if (!type.startsWith("macro_release_")) continue;
                String subtype = type.substring("macro_release_".length()).toUpperCase(Locale.ROOT);
                if ("EMPLOYMENT_SITUATION".equals(subtype)) subtype = "NONFARM_PAYROLLS";
                if (!Set.of("CPI", "NONFARM_PAYROLLS", "PPI", "JOLTS").contains(subtype)) continue;
                OffsetDateTime at = OffsetDateTime.parse(item.path("start_utc").asText());
                LocalDate date = at.atZoneSameInstant(NEW_YORK).toLocalDate();
                if (date.isBefore(from) || date.isAfter(through)) continue;
                boolean approximate = item.path("approximate").asBoolean(false);
                rows.add(event("MACRO|" + subtype + "|" + date, null, "MACRO", subtype, item.path("title").asText(), date,
                        approximate ? "DATE_ONLY" : "EXACT", approximate ? null : at.toInstant(), "COMPASS_FALLBACK",
                        item.path("source_url").asText(COMPASS_CALENDAR_URL), item.path("id").asText()));
            }
            return rows;
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to parse fallback economic calendar", failure);
        }
    }

    private MarketEvent event(String key, String figi, String type, String subtype, String title, LocalDate date,
                              String precision, Instant occurrenceAt, String source, String url, String externalId) {
        MarketEvent row = new MarketEvent();
        row.setEventKey(key);
        row.setFigi(figi);
        row.setEventType(type);
        row.setEventSubtype(subtype);
        row.setTitle(title);
        row.setEventDate(date);
        row.setTimePrecision(precision);
        row.setOccurrenceAt(occurrenceAt);
        row.setSource(source);
        row.setSourceUrl(url);
        row.setExternalId(externalId);
        row.setObservedAt(dateTimeService.nowZonedDateTime(ZoneId.of("UTC")).toInstant());
        row.setStatus("SCHEDULED");
        return row;
    }

    void save(List<MarketEvent> incoming) {
        if (incoming.isEmpty()) return;
        transactions.executeWithoutResult(status -> {
            Map<String, MarketEvent> existing = events.findAllById(incoming.stream().map(MarketEvent::getEventKey).toList())
                    .stream().collect(Collectors.toMap(MarketEvent::getEventKey, Function.identity()));
            List<MarketEvent> changed = incoming.stream().filter(row -> !row.equals(existing.get(row.getEventKey()))).toList();
            if (!changed.isEmpty()) events.saveAllAndFlush(changed);
        });
    }

    private List<Map<String, String>> parseIcs(String raw) {
        String unfolded = raw.replace("\r\n", "\n").replaceAll("\n[ \\t]", "");
        List<Map<String, String>> events = new ArrayList<>();
        Map<String, String> current = null;
        for (String line : unfolded.split("\n")) {
            if ("BEGIN:VEVENT".equals(line)) {
                current = new HashMap<>();
            } else if ("END:VEVENT".equals(line)) {
                if (current != null) events.add(current);
                current = null;
            } else if (current != null) {
                int separator = line.indexOf(':');
                if (separator <= 0) continue;
                String property = line.substring(0, separator);
                String name = property.contains(";") ? property.substring(0, property.indexOf(';')) : property;
                current.put(name, line.substring(separator + 1));
                current.put(name + "_PROPERTY", property);
            }
        }
        return events;
    }

    // 用途：解析IcsDateTime，作為「市場事件同步服務」流程中的處理步驟。
    private ParsedEventTime parseIcsDateTime(Map<String, String> item) {
        String value = item.get("DTSTART");
        if (value == null) return null;
        String property = item.getOrDefault("DTSTART_PROPERTY", "");
        ZoneId zone = NEW_YORK;
        int timezoneIndex = property.indexOf("TZID=");
        if (timezoneIndex >= 0) {
            String zoneName = property.substring(timezoneIndex + 5).split(";", 2)[0];
            try { zone = ZoneId.of(zoneName); } catch (RuntimeException ignored) { }
        }
        try {
            if (value.endsWith("Z")) {
                Instant instant = OffsetDateTime.parse(value, DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssX")).toInstant();
                return new ParsedEventTime(instant.atZone(NEW_YORK).toLocalDate(), "EXACT", instant);
            }
            if (value.length() == 8) return new ParsedEventTime(LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE), "DATE_ONLY", null);
            ZonedDateTime zdt = LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")).atZone(zone);
            return new ParsedEventTime(zdt.withZoneSameInstant(NEW_YORK).toLocalDate(), "EXACT", zdt.toInstant());
        } catch (DateTimeParseException exception) {
            log.warn("Unable to parse BLS calendar timestamp: {}", value);
            return null;
        }
    }

    // 用途：判斷ImportantBlsRelease，作為「市場事件同步服務」流程中的處理步驟。
    private boolean isImportantBlsRelease(String title) {
        if (title == null) return false;
        String normalized = title.toLowerCase(Locale.ROOT);
        return normalized.contains("consumer price index")
                || normalized.contains("employment situation")
                || normalized.contains("producer price index")
                || normalized.contains("job openings and labor turnover");
    }

    // 用途：處理blsSubtype，作為「市場事件同步服務」流程中的處理步驟。
    private String blsSubtype(String title) {
        String normalized = title.toLowerCase(Locale.ROOT);
        if (normalized.contains("consumer price")) return "CPI";
        if (normalized.contains("employment situation")) return "NONFARM_PAYROLLS";
        if (normalized.contains("producer price")) return "PPI";
        return "JOLTS";
    }

    // 用途：解析Fomc決策Date，作為「市場事件同步服務」流程中的處理步驟。
    private LocalDate parseFomcDecisionDate(int year, String monthText, String dateText) {
        try {
            String monthName = Arrays.stream(monthText.split("/")) .reduce((first, second) -> second).orElse(monthText).trim();
            Month month = Month.from(DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH)
                    .parse(monthName.substring(0, Math.min(3, monthName.length()))));
            String cleanedDate = dateText.replace("*", "").replaceAll("[^0-9-]", "");
            String[] days = cleanedDate.split("-");
            int day = Integer.parseInt(days[days.length - 1]);
            return LocalDate.of(year, month, day);
        } catch (RuntimeException exception) {
            log.warn("Unable to parse FOMC date: {} {}", monthText, dateText);
            return null;
        }
    }

    // 用途：處理earningsTime，作為「市場事件同步服務」流程中的處理步驟。
    String earningsPrecision(String hour) {
        if (hour == null || hour.isBlank()) return "DATE_ONLY";
        return switch (hour.toLowerCase(Locale.ROOT)) {
            case "bmo" -> "BEFORE_OPEN";
            case "amc" -> "AFTER_CLOSE";
            default -> "DATE_ONLY";
        };
    }

    // 用途：處理unescapeIcs，作為「市場事件同步服務」流程中的處理步驟。
    private String unescapeIcs(String value) {
        return value == null ? null : value.replace("\\,", ",").replace("\\n", " ").replace("\\;", ";");
    }

    private record ParsedEventTime(LocalDate date, String precision, Instant occurrenceAt) {}
}
