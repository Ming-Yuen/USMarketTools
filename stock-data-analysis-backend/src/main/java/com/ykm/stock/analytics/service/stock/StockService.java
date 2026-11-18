package com.ykm.stock.analytics.service.stock;

import com.ykm.stock.analytics.converter.jpa.StockClassificationConverter;
import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.model.dto.stockInsight.StockClassificationRequest;
import com.ykm.stock.analytics.model.dto.stockInsight.StockClassificationResponse;
import com.ykm.stock.analytics.model.dto.stockInsight.StockScreenResponse;
import com.ykm.stock.analytics.model.dto.stockInsight.StockScreenResponse.Concept;
import com.ykm.stock.analytics.model.dto.stockInsight.StockScreenResponse.Item;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockIndustry;
import com.ykm.stock.analytics.model.entity.StockStatus;
import com.ykm.stock.analytics.model.projection.ActiveStockQuoteDate;
import com.ykm.stock.analytics.model.projection.ActiveStockWithStatus;
import com.ykm.stock.analytics.repository.jpa.StockConceptRepository;
import com.ykm.stock.analytics.repository.jpa.StockIndustryRepository;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.repository.jpa.StockStatusRepository;
import com.ykm.stock.analytics.service.analysis.SecurityTypePolicy;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class StockService {
    public static final List<String> SCREEN_MARKET_CAP_TIERS = List.of("MEGA_LARGE", "MID", "SMALL");
    public static final List<String> SCREEN_VERDICTS = List.of(
            "DEEP_UNDERVALUED", "MODERATELY_UNDERVALUED", "FAIR_LOWER",
            "FAIR_UPPER", "MODERATELY_OVERVALUED", "OVERVALUED_RISK", "INSUFFICIENT_EVIDENCE"
    );
    private static final Map<String, String> SORT_SQL_COLUMNS = buildSortSqlColumns();
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final StockRepository stockRepository;
    private final StockStatusRepository stockStatusRepository;
    private final StockIndustryRepository stockClassificationRepository;
    private final StockClassificationConverter stockClassificationConverter;
    private final StockConceptRepository stockConceptRepository;
    private final NamedParameterJdbcTemplate jdbc;

    public record ScreenCriteria(
            String symbol,
            String industry,
            String conceptCode,
            String marketCapTier,
            String verdict,
            BigDecimal minRevenueGrowthYoy,
            BigDecimal minRoe,
            BigDecimal minFcfMargin,
            BigDecimal minHistoricalPercentile,
            BigDecimal maxHistoricalPercentile,
            String sortBy,
            String sortDir,
            int page,
            int size
    ) {
        public static ScreenCriteria basic(String symbol, String industry, String conceptCode, int page, int size) {
            return new ScreenCriteria(symbol, industry, conceptCode, null, null, null, null, null, null, null, null, null, page, size);
        }
    }

    public boolean stockExists(String figi) {
        return stockRepository.existsById(figi);
    }

    public List<Stock> findActiveStocks(@NotBlank String exchangeCode) {
        return stockRepository.findByActiveStatus(ActiveStatus.ACTIVE);
    }

    /** 個股行情錯誤只更新訊息，不推進水位或覆蓋其他來源狀態。 */
    @Transactional
    public void recordQuoteFailures(Map<String, String> failures) {
        if (failures.isEmpty()) return;
        List<StockStatus> locked = stockStatusRepository.findAllByIdForUpdate(failures.keySet());
        if (locked.size() != failures.size()) throw new IllegalStateException("QUOTE_STOCK_STATUS_MISSING");
        for (StockStatus status : locked) status.setQuoteMessage(failures.get(status.getFigi()));
        stockStatusRepository.saveAll(locked);
    }

    public List<StockStatus> findStockStatuses(Collection<String> figis) {
        return stockStatusRepository.findAllById(figis);
    }

    public StockStatus findByStockStatus(String figi) {
        return stockStatusRepository.findById(figi).orElse(null);
    }

    @Transactional(readOnly = true)
    public LocalDate findMaxQuoteDate() {
        return stockStatusRepository.findMaxQuoteDate();
    }

    public void updateStockStatus(StockStatus stockStatus) {
        stockStatusRepository.saveAndFlush(stockStatus);
    }

    public void updateStockStatuses(List<StockStatus> stockStatuses) {
        stockStatusRepository.saveAllAndFlush(stockStatuses);
    }

    public void updateStockStatuses(Set<String> newFigis) {
        if (newFigis.isEmpty()) return;
        updateStockStatuses(newFigis.stream().map(this::newStockStatus).toList());
    }

    private StockStatus newStockStatus(String figi) {
        StockStatus status = new StockStatus();
        status.setFigi(figi);
        return status;
    }

    public void updateStock(Stock stock) {
        stockRepository.saveAndFlush(stock);
    }

    public void updateStocks(List<Stock> stocks) {
        if (stocks == null || stocks.isEmpty()) return;
        stockRepository.saveAllAndFlush(stocks);
    }

    public Stock findByFigi(String figi) {
        return stockRepository.findById(figi).orElse(null);
    }

    public List<Stock> findAllStocks() {
        return stockRepository.findAll();
    }

    public List<Stock> findStocksByFigis(Collection<String> figis) {
        return stockRepository.findAllById(figis);
    }

    public Stock findBySymbol(String symbol) {
        return stockRepository.findFirstBySymbol(symbol);
    }

    public Stock findActiveBySymbol(String symbol) {
        return stockRepository.findFirstBySymbolAndActiveStatus(symbol, ActiveStatus.ACTIVE);
    }

    public List<Stock> findByActiveStatus() {
        return stockRepository.findByActiveStatus(ActiveStatus.ACTIVE);
    }

    @Transactional(readOnly = true)
    public List<ActiveStockQuoteDate> findActiveStockQuoteDates(LocalDate lastClosedTradingDay) {
        return stockRepository.findAllActiveStockQuoteDates(ActiveStatus.ACTIVE, lastClosedTradingDay);
    }

    @Transactional(readOnly = true)
    public List<ActiveStockWithStatus> findActiveStocksWithStatus() {
        return stockRepository.findAllActiveStocksWithStatus(ActiveStatus.ACTIVE);
    }

    /** 查詢獨立的產業、概念字典與選股器權威篩選／排序選項代碼。 */
    public StockClassificationResponse getStockClassification(StockClassificationRequest stockClassificationRequest) {
        StockClassificationResponse response = new StockClassificationResponse();
        StockClassificationRequest request = stockClassificationRequest == null ? new StockClassificationRequest() : stockClassificationRequest;

        Specification<StockIndustry> filter = (root, query, builder) -> builder.conjunction();
        if (StringUtils.isNotBlank(request.getCode())) {
            filter = filter.and((root, query, builder) -> builder.equal(root.get("code"), request.getCode()));
        }
        if (StringUtils.isNotBlank(request.getIndustry())) {
            filter = filter.and((root, query, builder) -> builder.equal(root.get("industry"), request.getIndustry()));
        }
        List<StockIndustry> classifications = stockClassificationRepository.findAll(filter, Sort.by("industry", "code"));
        response.setClassifications(stockClassificationConverter.toClassificationResponse(classifications));
        response.setConcepts(stockClassificationConverter.toConceptResponse(stockConceptRepository.findAll(Sort.by("name", "code"))));
        response.setMarketCapTiers(SCREEN_MARKET_CAP_TIERS.toArray(new String[0]));
        response.setVerdicts(SCREEN_VERDICTS.toArray(new String[0]));
        response.setSortFields(SORT_SQL_COLUMNS.keySet().toArray(new String[0]));
        return response;
    }

    public StockIndustry getStockClassification(String code) {
        return stockClassificationRepository.findById(code).orElse(null);
    }

    public record TrackedUpdateResult(
            String figi,
            String symbol,
            boolean tracked,
            @com.fasterxml.jackson.annotation.JsonIgnore boolean changed
    ) {
        public TrackedUpdateResult(String figi, String symbol, boolean tracked) {
            this(figi, symbol, tracked, true);
        }
    }

    public record BatchTrackedUpdateResult(
            int updatedCount,
            List<String> figis,
            boolean tracked,
            @com.fasterxml.jackson.annotation.JsonIgnore List<String> changedFigis
    ) {
        public BatchTrackedUpdateResult(int updatedCount, List<String> figis, boolean tracked) {
            this(updatedCount, figis, tracked, figis);
        }
    }

    /** 更新單一股票的人工追蹤開關；不影響同發行人關聯行情或歷史資料。 */
    @Transactional
    public TrackedUpdateResult updateTracked(String figi, Boolean tracked) {
        if (StringUtils.isBlank(figi) || tracked == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "TRACKED_REQUEST_INVALID");
        }
        Stock stock = stockRepository.findById(figi.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "STOCK_NOT_FOUND"));
        boolean changed = stock.isTracked() != tracked;
        stock.setTracked(tracked);
        stockRepository.saveAndFlush(stock);
        return new TrackedUpdateResult(stock.getFigi(), stock.getSymbol(), stock.isTracked(), changed);
    }

    /** 批量更新人工追蹤開關；任一指定 FIGI 不存在時整批拒絕，避免部分靜默跳過。 */
    @Transactional
    public BatchTrackedUpdateResult updateTrackedBatch(Collection<String> figis, Boolean tracked) {
        if (figis == null || figis.isEmpty() || tracked == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "TRACKED_BATCH_REQUEST_INVALID");
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String figi : figis) {
            if (StringUtils.isBlank(figi)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "TRACKED_BATCH_REQUEST_INVALID");
            }
            normalized.add(figi.trim());
        }
        List<Stock> found = stockRepository.findAllById(normalized);
        if (found.size() != normalized.size()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "STOCK_NOT_FOUND");
        }
        Map<String, Stock> foundByFigi = new HashMap<>();
        for (Stock stock : found) {
            foundByFigi.put(stock.getFigi(), stock);
        }
        List<String> changedFigis = new ArrayList<>();
        for (String figi : normalized) {
            Stock stock = foundByFigi.get(figi);
            if (stock != null && stock.isTracked() != tracked) {
                changedFigis.add(figi);
            }
        }
        List<Stock> sortedFound = new ArrayList<>(found);
        sortedFound.sort(java.util.Comparator.comparing(Stock::getFigi));
        for (Stock stock : sortedFound) {
            stock.setTracked(tracked);
        }
        stockRepository.saveAllAndFlush(sortedFound);
        return new BatchTrackedUpdateResult(found.size(), List.copyOf(normalized), tracked, List.copyOf(changedFigis));
    }

    private static final String FROM = """
            from stock s
            left join stock_industry_history h on h.figi = s.figi and h.observed_to is null
            left join stock_industry i on i.code = h.industry_code
            left join stock_valuation_latest v on v.figi = s.figi
            """;

    /** Controller 入口：先於全市場候選集完成估值／基本面篩選與白名單全域排序後分頁，再批次讀行情與概念；最多三次 SQL 零 N+1。 */
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public StockScreenResponse screen(ScreenCriteria criteria) {
        if (criteria == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "screen criteria is required");
        }
        int page = criteria.page();
        int size = criteria.size();
        if (page < 0 || size < 1 || size > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page 必須非負，size 必須介乎 1 至 200");
        }

        String normalizedTier = normalizeEnumFilter(criteria.marketCapTier(), SCREEN_MARKET_CAP_TIERS, "INVALID_MARKET_CAP_TIER");
        String normalizedVerdict = normalizeEnumFilter(criteria.verdict(), SCREEN_VERDICTS, "INVALID_VERDICT");
        validatePercentileBounds(criteria.minHistoricalPercentile(), criteria.maxHistoricalPercentile());
        String orderByInner = buildOrderByClause(criteria.sortBy(), criteria.sortDir(), "match_rank", "s.symbol", "s.figi");
        String orderByOuter = buildOrderByClause(criteria.sortBy(), criteria.sortDir(), "s.match_rank", "s.symbol", "s.figi");

        MapSqlParameterSource params = new MapSqlParameterSource("limit", size)
                .addValue("offset", (long) page * size)
                .addValue("stockTypes", SecurityTypePolicy.OPERATING_STOCK_TYPES);
        StringBuilder where = new StringBuilder(" where s.active_status = 'ACTIVE' and s.tracked = true and s.stock_type in (:stockTypes) ");
        String rankSelect = "0 as match_rank";

        if (StringUtils.isNotBlank(criteria.symbol())) {
            String trimmed = criteria.symbol().trim();
            where.append(" and (s.symbol ilike :symbol or s.long_name ilike :symbol) ");
            params.addValue("symbol", "%" + trimmed + "%");
            params.addValue("symbolExact", trimmed.toUpperCase());
            params.addValue("symbolPrefix", trimmed + "%");
            rankSelect = """
                    case
                      when upper(s.symbol) = :symbolExact then 0
                      when s.symbol ilike :symbolPrefix then 1
                      when s.symbol ilike :symbol then 2
                      else 3
                    end as match_rank""";
        }
        if (StringUtils.isNotBlank(criteria.industry())) {
            where.append(" and (i.code = :industry or i.industry = :industry) ");
            params.addValue("industry", criteria.industry().trim());
        }
        if (StringUtils.isNotBlank(criteria.conceptCode())) {
            where.append(" and exists (select 1 from stock_concept_mapping m where m.figi = s.figi and m.concept_code = :concept) ");
            params.addValue("concept", criteria.conceptCode().trim());
        }
        if (normalizedTier != null) {
            where.append(" and v.market_cap_tier = :marketCapTier ");
            params.addValue("marketCapTier", normalizedTier);
        }
        if (normalizedVerdict != null) {
            where.append(" and v.verdict = :verdict ");
            params.addValue("verdict", normalizedVerdict);
        }
        if (criteria.minRevenueGrowthYoy() != null) {
            where.append(" and v.revenue_growth_yoy >= :minRevenueGrowthYoy ");
            params.addValue("minRevenueGrowthYoy", criteria.minRevenueGrowthYoy());
        }
        if (criteria.minRoe() != null) {
            where.append(" and v.roe >= :minRoe ");
            params.addValue("minRoe", criteria.minRoe());
        }
        if (criteria.minFcfMargin() != null) {
            where.append(" and v.fcf_margin >= :minFcfMargin ");
            params.addValue("minFcfMargin", criteria.minFcfMargin());
        }
        if (criteria.minHistoricalPercentile() != null) {
            where.append(" and v.historical_percentile >= :minHistoricalPercentile ");
            params.addValue("minHistoricalPercentile", criteria.minHistoricalPercentile());
        }
        if (criteria.maxHistoricalPercentile() != null) {
            where.append(" and v.historical_percentile <= :maxHistoricalPercentile ");
            params.addValue("maxHistoricalPercentile", criteria.maxHistoricalPercentile());
        }

        Long total = jdbc.queryForObject("select count(*) " + FROM + where, params, Long.class);
        if (total == null || total == 0) return new StockScreenResponse(List.of(), 0, page, size);

        String sql = """
                with selected as (
                  select s.figi, s.symbol, s.long_name, s.stock_type, s.currency as stock_currency,
                         i.code as industry_code, i.industry,
                         v.valuation_date, v.currency as val_currency, v.market_cap, v.market_cap_tier,
                         v.pe, v.pb, v.ps, v.pfcf, v.ev_ebitda,
                         v.revenue_growth_yoy, v.roe, v.fcf_margin, v.historical_percentile,
                         v.primary_metric, v.verdict,
                         case when v.figi is null then 'NOT_CALCULATED' else v.status end as valuation_status,
                         case when v.figi is null then 'VALUATION_NOT_CALCULATED' else v.reason_code end as reason_code,"""
                + " " + rankSelect + "\n" + FROM + where
                + "  order by " + orderByInner + "\n  limit :limit offset :offset\n)\n"
                + """
                select s.*, ss.quote_date, q.close_price
                from selected s
                left join stock_status ss on ss.figi = s.figi
                left join stock_quote q on q.figi = s.figi and q.quote_date = ss.quote_date
                """
                + "order by " + orderByOuter + "\n";

        List<Item> rows = jdbc.query(sql, params, (rs, row) -> {
            String valCurrency = rs.getString("val_currency");
            String resolvedCurrency = valCurrency != null ? valCurrency : rs.getString("stock_currency");
            return new Item(
                    rs.getString("figi"),
                    rs.getString("symbol"),
                    rs.getString("long_name"),
                    rs.getString("stock_type"),
                    rs.getString("industry_code"),
                    rs.getString("industry"),
                    List.of(),
                    date(rs, "quote_date"),
                    rs.getBigDecimal("close_price"),
                    resolvedCurrency,
                    date(rs, "valuation_date"),
                    rs.getBigDecimal("market_cap"),
                    rs.getString("market_cap_tier"),
                    rs.getBigDecimal("pe"),
                    rs.getBigDecimal("pb"),
                    rs.getBigDecimal("ps"),
                    rs.getBigDecimal("pfcf"),
                    rs.getBigDecimal("ev_ebitda"),
                    rs.getBigDecimal("revenue_growth_yoy"),
                    rs.getBigDecimal("roe"),
                    rs.getBigDecimal("fcf_margin"),
                    rs.getBigDecimal("historical_percentile"),
                    rs.getString("primary_metric"),
                    rs.getString("verdict"),
                    rs.getString("valuation_status"),
                    rs.getString("reason_code")
            );
        });
        if (rows.isEmpty()) return new StockScreenResponse(List.of(), total, page, size);

        Map<String, List<Concept>> concepts = new HashMap<>();
        jdbc.query("""
                select m.figi, c.code, c.name from stock_concept_mapping m join stock_concept c on c.code = m.concept_code
                where m.figi in (:figis) order by c.name, c.code
                """, new MapSqlParameterSource("figis", rows.stream().map(Item::figi).toList()),
                (RowCallbackHandler) rs -> concepts
                        .computeIfAbsent(rs.getString("figi"), key -> new ArrayList<>())
                        .add(new Concept(rs.getString("code"), rs.getString("name"))));
        return new StockScreenResponse(rows.stream().map(row -> row.withConcepts(
                List.copyOf(concepts.getOrDefault(row.figi(), List.of())))).toList(), total, page, size);
    }

    private String normalizeEnumFilter(String raw, List<String> allowed, String errorCode) {
        if (StringUtils.isBlank(raw)) return null;
        String upper = raw.trim().toUpperCase();
        if (!allowed.contains(upper)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, errorCode);
        }
        return upper;
    }

    private void validatePercentileBounds(BigDecimal minP, BigDecimal maxP) {
        if (minP != null && (minP.compareTo(ZERO) < 0 || minP.compareTo(HUNDRED) > 0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "HISTORICAL_PERCENTILE_OUT_OF_RANGE");
        }
        if (maxP != null && (maxP.compareTo(ZERO) < 0 || maxP.compareTo(HUNDRED) > 0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "HISTORICAL_PERCENTILE_OUT_OF_RANGE");
        }
        if (minP != null && maxP != null && minP.compareTo(maxP) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "HISTORICAL_PERCENTILE_MIN_EXCEEDS_MAX");
        }
    }

    private String buildOrderByClause(String sortBy, String sortDir, String rankRef, String symbolRef, String figiRef) {
        boolean outer = "s.match_rank".equals(rankRef);
        if (StringUtils.isBlank(sortBy)) {
            return rankRef + ", " + symbolRef + ", " + figiRef + " ";
        }
        String key = sortBy.trim();
        if (!SORT_SQL_COLUMNS.containsKey(key)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_SORT_BY");
        }
        String dir = "asc";
        if (StringUtils.isNotBlank(sortDir)) {
            String trimmedDir = sortDir.trim().toLowerCase();
            if (!"asc".equals(trimmedDir) && !"desc".equals(trimmedDir)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_SORT_DIR");
            }
            dir = trimmedDir;
        }
        String expr = outer ? outerSortExpression(key) : SORT_SQL_COLUMNS.get(key);
        return expr + " " + dir + " nulls last, " + rankRef + ", " + symbolRef + ", " + figiRef + " ";
    }

    private static Map<String, String> buildSortSqlColumns() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("symbol", "s.symbol");
        map.put("marketCap", "case when v.currency = 'USD' then v.market_cap end");
        map.put("marketCapTier", "case v.market_cap_tier when 'MEGA_LARGE' then 3 when 'MID' then 2 when 'SMALL' then 1 end");
        map.put("verdict", """
                case v.verdict
                  when 'DEEP_UNDERVALUED' then 1
                  when 'MODERATELY_UNDERVALUED' then 2
                  when 'FAIR_LOWER' then 3
                  when 'FAIR_UPPER' then 4
                  when 'MODERATELY_OVERVALUED' then 5
                  when 'OVERVALUED_RISK' then 6
                end""");
        map.put("pe", "v.pe");
        map.put("pb", "v.pb");
        map.put("ps", "v.ps");
        map.put("pfcf", "v.pfcf");
        map.put("evEbitda", "v.ev_ebitda");
        map.put("revenueGrowthYoy", "v.revenue_growth_yoy");
        map.put("roe", "v.roe");
        map.put("fcfMargin", "v.fcf_margin");
        map.put("historicalPercentile", "v.historical_percentile");
        return Map.copyOf(map);
    }

    private String outerSortExpression(String key) {
        return switch (key) {
            case "symbol" -> "s.symbol";
            case "marketCap" -> "case when s.val_currency = 'USD' then s.market_cap end";
            case "marketCapTier" -> "case s.market_cap_tier when 'MEGA_LARGE' then 3 when 'MID' then 2 when 'SMALL' then 1 end";
            case "verdict" -> """
                    case s.verdict
                      when 'DEEP_UNDERVALUED' then 1
                      when 'MODERATELY_UNDERVALUED' then 2
                      when 'FAIR_LOWER' then 3
                      when 'FAIR_UPPER' then 4
                      when 'MODERATELY_OVERVALUED' then 5
                      when 'OVERVALUED_RISK' then 6
                    end""";
            case "pe" -> "s.pe";
            case "pb" -> "s.pb";
            case "ps" -> "s.ps";
            case "pfcf" -> "s.pfcf";
            case "evEbitda" -> "s.ev_ebitda";
            case "revenueGrowthYoy" -> "s.revenue_growth_yoy";
            case "roe" -> "s.roe";
            case "fcfMargin" -> "s.fcf_margin";
            case "historicalPercentile" -> "s.historical_percentile";
            default -> throw new IllegalStateException("Unexpected sort key: " + key);
        };
    }

    private LocalDate date(ResultSet row, String name) throws SQLException {
        java.sql.Date value = row.getDate(name);
        return value == null ? null : value.toLocalDate();
    }
}
