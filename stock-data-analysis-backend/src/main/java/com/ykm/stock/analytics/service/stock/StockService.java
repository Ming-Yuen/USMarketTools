package com.ykm.stock.analytics.service.stock;

import com.ykm.stock.analytics.converter.jpa.StockClassificationConverter;
import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.model.dto.stockInsight.StockClassificationRequest;
import com.ykm.stock.analytics.model.dto.stockInsight.StockClassificationResponse;
import com.ykm.stock.analytics.model.projection.ActiveStockQuoteDate;
import com.ykm.stock.analytics.model.projection.ActiveStockWithStatus;
import com.ykm.stock.analytics.model.entity.*;
import com.ykm.stock.analytics.repository.jpa.StockIndustryRepository;
import com.ykm.stock.analytics.repository.jpa.StockConceptRepository;
import com.ykm.stock.analytics.repository.jpa.StockStatusRepository;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.service.analysis.SecurityTypePolicy;
import com.ykm.stock.analytics.model.dto.stockInsight.StockScreenResponse;
import com.ykm.stock.analytics.model.dto.stockInsight.StockScreenResponse.Concept;
import com.ykm.stock.analytics.model.dto.stockInsight.StockScreenResponse.Item;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.sql.ResultSet;
import java.sql.SQLException;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
// 用途：負責「股票服務」相關的業務協調與處理，集中管理此領域的服務邏輯。
public class StockService {
    private final StockRepository stockRepository;

    private final StockStatusRepository stockStatusRepository;


    private final StockIndustryRepository stockClassificationRepository;
    private final StockClassificationConverter stockClassificationConverter;
    private final StockConceptRepository stockConceptRepository;

    // 用途：處理股票Exists，作為「股票服務」流程中的處理步驟。
    public boolean stockExists(String figi) {
        return stockRepository.existsById(figi);
    }

    // 用途：查找ActiveStocks，作為「股票服務」流程中的處理步驟。
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

    // 用途：查找By股票狀態，作為「股票服務」流程中的處理步驟。
    public StockStatus findByStockStatus(String figi) {
        return stockStatusRepository.findById(figi).orElse(null);
    }

    // 用途：取得所有股票狀態中最新的行情日期，避免掃描大型 STOCK_QUOTE 表。
    @Transactional(readOnly = true)
    public LocalDate findMaxQuoteDate() {
        return stockStatusRepository.findMaxQuoteDate();
    }

    // 用途：更新股票狀態，作為「股票服務」流程中的處理步驟。
    public void updateStockStatus(StockStatus stockStatus) {
        stockStatusRepository.saveAndFlush(stockStatus);
    }

    // 用途：更新股票Statuses，作為「股票服務」流程中的處理步驟。
    public void updateStockStatuses(List<StockStatus> stockStatuses) {
        stockStatusRepository.saveAllAndFlush(stockStatuses);
    }

    // 用途：批次建立新增股票的 StockStatus，不逐筆查詢既有狀態。
    public void updateStockStatuses(Set<String> newFigis) {
        if (newFigis.isEmpty()) return;
        updateStockStatuses(newFigis.stream().map(this::newStockStatus).toList());
    }

    /** 僅供 StockDailyTask 的新股批次建立狀態。 */
    private StockStatus newStockStatus(String figi) {
        StockStatus status = new StockStatus();
        status.setFigi(figi);
        return status;
    }

    // 用途：更新股票，作為「股票服務」流程中的處理步驟。
    public void updateStock(Stock stock) {
        stockRepository.saveAndFlush(stock);
    }

    // 用途：更新Stocks，作為「股票服務」流程中的處理步驟。
    public void updateStocks(List<Stock> stocks) {
        if (stocks == null || stocks.isEmpty()) return;
        stockRepository.saveAllAndFlush(stocks);
    }

    // 用途：查找ByFigi，作為「股票服務」流程中的處理步驟。
    public Stock findByFigi(String figi) {
        return stockRepository.findById(figi).orElse(null);
    }

    /** 全市場主檔同步一次讀取全部 Stock（含非 ACTIVE）；Scenario：不帶入分類與概念的 EntityGraph。 */
    public List<Stock> findAllStocks() {
        return stockRepository.findAll();
    }

    /** 批次同步依 FIGI 讀取主檔；在寫入交易內呼叫時更新同一批受管理實體。 */
    public List<Stock> findStocksByFigis(Collection<String> figis) {
        return stockRepository.findAllById(figis);
    }

    // 用途：查找BySymbol，作為「股票服務」流程中的處理步驟。
    public Stock findBySymbol(String symbol) {
        return stockRepository.findFirstBySymbol(symbol);
    }

    // 用途：查找ActiveBySymbol，作為「股票服務」流程中的處理步驟。
    public Stock findActiveBySymbol(String symbol) {
        return stockRepository.findFirstBySymbolAndActiveStatus(symbol, ActiveStatus.ACTIVE);
    }

    // 用途：查找ByActive狀態，作為「股票服務」流程中的處理步驟。
    public List<Stock> findByActiveStatus(){
        return stockRepository.findByActiveStatus(ActiveStatus.ACTIVE);
    }

    /** 查詢尚未同步至最新已收市交易日的 ACTIVE 股票及行情水位。 */
    @Transactional(readOnly = true)
    public List<ActiveStockQuoteDate> findActiveStockQuoteDates(LocalDate lastClosedTradingDay) {
        return stockRepository.findAllActiveStockQuoteDates(ActiveStatus.ACTIVE, lastClosedTradingDay);
    }

    @Transactional(readOnly = true)
    public List<ActiveStockWithStatus> findActiveStocksWithStatus() {
        return stockRepository.findAllActiveStocksWithStatus(ActiveStatus.ACTIVE);
    }

    /** 查詢獨立的產業與概念字典，供股票顯示與篩選共用。 */
    public StockClassificationResponse getStockClassification(StockClassificationRequest stockClassificationRequest) {
        StockClassificationResponse response = new StockClassificationResponse();
        StockClassificationRequest request = stockClassificationRequest == null ? new StockClassificationRequest() : stockClassificationRequest;

        Specification<StockIndustry> filter = (root, query, builder) -> builder.conjunction();
        if (StringUtils.isNotBlank(request.getCode())) filter = filter.and((root, query, builder) -> builder.equal(root.get("code"), request.getCode()));
        if (StringUtils.isNotBlank(request.getIndustry())) filter = filter.and((root, query, builder) -> builder.equal(root.get("industry"), request.getIndustry()));
        List<StockIndustry> classifications = stockClassificationRepository.findAll(filter,
                Sort.by("industry", "code"));
        response.setClassifications(stockClassificationConverter.toClassificationResponse(classifications));
        response.setConcepts(stockClassificationConverter.toConceptResponse(stockConceptRepository.findAll(Sort.by("name", "code"))));
        return response;
    }

    public StockIndustry getStockClassification(String code) {
        return stockClassificationRepository.findById(code).orElse(null);
    }

    public record TrackedUpdateResult(String figi, String symbol, boolean tracked) {}

    public record BatchTrackedUpdateResult(int updatedCount, List<String> figis, boolean tracked) {}

    /** 更新單一股票的人工追蹤開關；不影響同發行人關聯行情或歷史資料。 */
    @Transactional
    public TrackedUpdateResult updateTracked(String figi, Boolean tracked) {
        if (StringUtils.isBlank(figi) || tracked == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "TRACKED_REQUEST_INVALID");
        Stock stock = stockRepository.findById(figi.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "STOCK_NOT_FOUND"));
        stock.setTracked(tracked);
        stockRepository.saveAndFlush(stock);
        return new TrackedUpdateResult(stock.getFigi(), stock.getSymbol(), stock.isTracked());
    }

    /** 批量更新人工追蹤開關；任一指定 FIGI 不存在時整批拒絕，避免部分靜默跳過。 */
    @Transactional
    public BatchTrackedUpdateResult updateTrackedBatch(Collection<String> figis, Boolean tracked) {
        if (figis == null || figis.isEmpty() || tracked == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "TRACKED_BATCH_REQUEST_INVALID");
        Set<String> normalized = new LinkedHashSet<>();
        for (String figi : figis) {
            if (StringUtils.isBlank(figi))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "TRACKED_BATCH_REQUEST_INVALID");
            normalized.add(figi.trim());
        }
        List<Stock> found = stockRepository.findAllById(normalized);
        if (found.size() != normalized.size())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "STOCK_NOT_FOUND");
        for (Stock stock : found) {
            stock.setTracked(tracked);
        }
        stockRepository.saveAllAndFlush(found);
        return new BatchTrackedUpdateResult(found.size(), List.copyOf(normalized), tracked);
    }

    private final NamedParameterJdbcTemplate jdbc;
    private static final String FROM = """
            from stock s
            left join stock_industry_history h on h.figi = s.figi and h.observed_to is null
            left join stock_industry i on i.code = h.industry_code
            """;

    /** Controller 入口：先分頁選取 ACTIVE 且 tracked=true 的營運股票，再批次讀行情、估值與概念；不觸發同步或計算。 */
    @Transactional(readOnly = true)
    public StockScreenResponse screen(String symbol, String industry, String conceptCode, int page, int size) {
        if (page < 0 || size < 1 || size > 200)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page 必須非負，size 必須介乎 1 至 200");
        MapSqlParameterSource params = new MapSqlParameterSource("limit", size)
                .addValue("offset", (long) page * size)
                .addValue("stockTypes", SecurityTypePolicy.OPERATING_STOCK_TYPES);
        StringBuilder where = new StringBuilder(" where s.active_status = 'ACTIVE' and s.tracked = true and s.stock_type in (:stockTypes) ");
        if (StringUtils.isNotBlank(symbol)) {
            where.append(" and (s.symbol ilike :symbol or s.long_name ilike :symbol) ");
            params.addValue("symbol", "%" + symbol.trim() + "%");
        }
        if (StringUtils.isNotBlank(industry)) {
            where.append(" and (i.code = :industry or i.industry = :industry) ");
            params.addValue("industry", industry.trim());
        }
        if (StringUtils.isNotBlank(conceptCode)) {
            where.append(" and exists (select 1 from stock_concept_mapping m where m.figi = s.figi and m.concept_code = :concept) ");
            params.addValue("concept", conceptCode.trim());
        }
        Long total = jdbc.queryForObject("select count(*) " + FROM + where, params, Long.class);
        if (total == null || total == 0) return new StockScreenResponse(List.of(), 0, page, size);
        String sql = """
                with selected as (
                  select s.figi, s.symbol, s.long_name, s.stock_type, i.code as industry_code, i.industry
                """ + FROM + where + """
                  order by s.symbol, s.figi limit :limit offset :offset
                )
                select s.*, ss.quote_date, q.close_price, v.valuation_date, v.pe, v.pb, v.ps, v.pfcf, v.ev_ebitda,
                       case when v.figi is null then 'NOT_CALCULATED' else v.status end as valuation_status,
                       case when v.figi is null then 'VALUATION_NOT_CALCULATED' else v.reason_code end as reason_code
                from selected s
                left join stock_status ss on ss.figi = s.figi
                left join stock_quote q on q.figi = s.figi and q.quote_date = ss.quote_date
                left join stock_valuation_latest v on v.figi = s.figi
                order by s.symbol, s.figi
                """;
        List<Item> rows = jdbc.query(sql, params, (rs, row) -> new Item(
                rs.getString("figi"), rs.getString("symbol"), rs.getString("long_name"), rs.getString("stock_type"),
                rs.getString("industry_code"), rs.getString("industry"), List.of(),
                date(rs, "quote_date"), rs.getBigDecimal("close_price"), date(rs, "valuation_date"),
                rs.getBigDecimal("pe"), rs.getBigDecimal("pb"), rs.getBigDecimal("ps"), rs.getBigDecimal("pfcf"),
                rs.getBigDecimal("ev_ebitda"), rs.getString("valuation_status"), rs.getString("reason_code")));
        if (rows.isEmpty()) return new StockScreenResponse(List.of(), total, page, size);
        Map<String, List<Concept>> concepts = new HashMap<>();
        jdbc.query("""
                select m.figi, c.code, c.name from stock_concept_mapping m join stock_concept c on c.code = m.concept_code
                where m.figi in (:figis) order by c.name, c.code
                """, new MapSqlParameterSource("figis", rows.stream().map(Item::figi).toList()),
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> concepts
                        .computeIfAbsent(rs.getString("figi"), key -> new ArrayList<>())
                        .add(new Concept(rs.getString("code"), rs.getString("name"))));
        return new StockScreenResponse(rows.stream().map(row -> row.withConcepts(
                List.copyOf(concepts.getOrDefault(row.figi(), List.of())))).toList(), total, page, size);
    }

    private LocalDate date(ResultSet row, String name) throws SQLException {
        java.sql.Date value = row.getDate(name);
        return value == null ? null : value.toLocalDate();
    }
}
