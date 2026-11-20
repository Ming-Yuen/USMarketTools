package com.ykm.stock.analytics.service.valuation;

import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.model.entity.AdrShareRatio;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.repository.jpa.AdrShareRatioRepository;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.repository.jpa.StockStatusRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/** ADR 換股比例對照表管理服務（查詢與新增／更新已驗證比例）。 */
@Service
@RequiredArgsConstructor
public class AdrShareRatioService {
    private final AdrShareRatioRepository repository;
    private final DateTimeService dateTimeService;
    @Autowired(required = false)
    private StockRepository stockRepository;
    @Autowired(required = false)
    private StockStatusRepository stockStatusRepository;
    @Autowired(required = false)
    private StockValuationService stockValuationService;

    public record AdrShareRatioRequest(
            String symbol,
            String underlyingSymbol,
            BigDecimal adrRatio,
            String reportingCurrency,
            Boolean verified,
            String note
    ) {}

    @Transactional(readOnly = true)
    public List<AdrShareRatio> findAll() {
        return repository.findAll().stream()
                .sorted((a, b) -> a.getSymbol().compareToIgnoreCase(b.getSymbol()))
                .toList();
    }

    @Transactional(readOnly = true)
    public AdrShareRatio findBySymbol(String symbol) {
        String normalized = normalizeSymbol(symbol);
        return repository.findById(normalized)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ADR_RATIO_NOT_FOUND"));
    }

    @Transactional
    public AdrShareRatio upsert(AdrShareRatioRequest request) {
        if (request == null || request.symbol() == null || request.symbol().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ADR_SYMBOL_REQUIRED");
        }
        if (request.adrRatio() == null || request.adrRatio().signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ADR_RATIO_MUST_BE_POSITIVE");
        }
        String symbol = normalizeSymbol(request.symbol());
        String currency = request.reportingCurrency() == null || request.reportingCurrency().isBlank()
                ? null : request.reportingCurrency().trim().toUpperCase(Locale.ROOT);
        if (currency != null && currency.length() != 3) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ADR_CURRENCY_INVALID");
        }
        Instant now = dateTimeService.now();
        AdrShareRatio entity = repository.findById(symbol).orElseGet(AdrShareRatio::new);
        entity.setSymbol(symbol);
        entity.setUnderlyingSymbol(request.underlyingSymbol() == null || request.underlyingSymbol().isBlank()
                ? null : request.underlyingSymbol().trim().toUpperCase(Locale.ROOT));
        entity.setAdrRatio(request.adrRatio());
        entity.setReportingCurrency(currency);
        entity.setVerified(Boolean.TRUE.equals(request.verified()));
        entity.setNote(request.note() == null || request.note().isBlank() ? null : request.note().trim());
        entity.setUpdatedAt(now);
        List<String> affectedFigis = stockRepository == null ? List.of()
                : stockRepository.findByActiveStatusAndSymbolIn(ActiveStatus.ACTIVE, List.of(symbol)).stream()
                        .map(Stock::getFigi).distinct().sorted().toList();
        if (!affectedFigis.isEmpty() && stockStatusRepository != null) {
            stockStatusRepository.findAllByIdForUpdate(affectedFigis);
        }
        AdrShareRatio saved = repository.saveAndFlush(entity);
        if (!affectedFigis.isEmpty() && stockValuationService != null) {
            stockValuationService.rebuildFinancialPeriodsInCurrentTx(affectedFigis);
        }
        return saved;
    }

    private static String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ADR_SYMBOL_REQUIRED");
        }
        return symbol.trim().toUpperCase(Locale.ROOT);
    }
}
