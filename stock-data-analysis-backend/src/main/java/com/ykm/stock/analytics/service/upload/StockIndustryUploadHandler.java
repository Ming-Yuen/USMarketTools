package com.ykm.stock.analytics.service.upload;

import com.ykm.orm.service.BusinessCodeGenerator;
import com.ykm.stock.analytics.converter.jpa.StockClassificationConverter;
import com.ykm.stock.analytics.model.dto.stockInsight.UploadResult;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockIndustry;
import com.ykm.stock.analytics.repository.jpa.StockIndustryRepository;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.service.stock.StockIndustryHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Deprecated
public class StockIndustryUploadHandler implements UploadHandler {
    private final StockRepository stocks;
    private final StockIndustryRepository classifications;
    private final StockIndustryHistoryService industryHistory;
    private final StockClassificationConverter converter;
    private final TransactionTemplate transactions;
    private final BusinessCodeGenerator businessCodeGenerator;

    @Override
    public String type() {
        return "STOCK_INDUSTRY";
    }

    /** 先完整解析及驗證，再將新增字典與股票關聯在同一交易提交；不採集行情或修改股票身份。 */
    @Override
    public UploadResult handle(byte[] content) {
        if (content == null || content.length == 0) throw new IllegalArgumentException("CSV_FILE_REQUIRED");
        if (content.length > 10 * 1024 * 1024) throw new IllegalArgumentException("CSV_FILE_TOO_LARGE");
        String text;
        try { text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(content)).toString(); }
        catch (java.nio.charset.CharacterCodingException ex) { throw new IllegalArgumentException("CSV_INVALID_UTF8", ex); }
        List<List<String>> records = parseCsv(text);
        if (records.size() < 2) throw new IllegalArgumentException("CSV_DATA_REQUIRED");
        List<String> headers = records.get(0).stream().map(String::trim).toList();
        if (new HashSet<>(headers).size() != headers.size()) throw new IllegalArgumentException("CSV_DUPLICATE_HEADER");
        for (String required : List.of("Symbol", "Name", "Industry"))
            if (!headers.contains(required)) throw new IllegalArgumentException("CSV_MISSING_HEADER:" + required);
        int symbolColumn = headers.indexOf("Symbol"), industryColumn = headers.indexOf("Industry");
        List<Input> inputs = new ArrayList<>();
        Set<String> symbols = new HashSet<>();
        for (int i = 1; i < records.size(); i++) {
            List<String> row = records.get(i);
            if (row.size() != headers.size()) throw new IllegalArgumentException("CSV_COLUMN_COUNT:row=" + (i + 1));
            String symbol = row.get(symbolColumn).trim();
            String industry = row.get(industryColumn).trim();
            if (symbol.isBlank() || symbol.length() > 32 || industry.length() > 128)
                throw new IllegalArgumentException("CSV_INVALID_VALUE:row=" + (i + 1));
            if (!symbols.add(symbol)) throw new IllegalArgumentException("CSV_DUPLICATE_SYMBOL:" + symbol);
            inputs.add(new Input(i + 1, symbol, industry));
        }

        return transactions.execute(status -> {
            // 同一功能先取得交易級鎖，再讀取字典，避免並行匯入各自新增相同產業。
            classifications.lockForIndustryImport();
            // 一次載入配對對象；不按 Symbol 猜測新 FIGI，不任選重複股票；產業名稱唯一。
            Map<String, List<Stock>> bySymbol = stocks.findBySymbolIn(symbols).stream()
                    .collect(Collectors.groupingBy(Stock::getSymbol));
            Map<String, StockIndustry> byIndustry = classifications.findAll().stream()
                    .collect(Collectors.toMap(c -> industryKey(c.getIndustry()), c -> c));
            List<Assignment> assignments = new ArrayList<>();
            List<StockIndustry> created = new ArrayList<>();
            List<UploadResult.Issue> issues = new ArrayList<>();
            int missing = 0, unmatched = 0, ambiguous = 0, unchanged = 0;
            for (Input input : inputs) {
                if (input.industry().isBlank() || Set.of("-", "--").contains(input.industry())) {
                    missing++;
                    addIssue(issues, input, "MISSING_INDUSTRY");
                    continue;
                }
                List<Stock> matches = bySymbol.getOrDefault(input.symbol(), List.of());
                if (matches.isEmpty()) { unmatched++; addIssue(issues, input, "STOCK_NOT_FOUND"); continue; }
                if (matches.size() != 1) {
                    ambiguous++; addIssue(issues, input, "AMBIGUOUS_SYMBOL"); continue;
                }
                StockIndustry classification = byIndustry.get(industryKey(input.industry()));
                if (classification == null) {
                    classification = converter.fromCsv(businessCodeGenerator.nextCode("I"), input.industry());
                    created.add(classification);
                    byIndustry.put(industryKey(input.industry()), classification);
                }
                Stock stock = matches.get(0);
                assignments.add(new Assignment(stock, classification));
            }
            // 先保存分類字典，然後依 FIGI 更新產業觀察歷史；不再寫入 stock.industry_code。
            Map<String, StockIndustry> managed = created.isEmpty() ? Map.of() : classifications.saveAllAndFlush(created).stream()
                    .collect(Collectors.toMap(StockIndustry::getCode, c -> c));
            Map<String, StockIndustry> updates = new LinkedHashMap<>();
            for (Assignment assignment : assignments) {
                StockIndustry classification = assignment.classification();
                StockIndustry persisted = managed.getOrDefault(classification.getCode(), classification);
                updates.put(assignment.stock().getFigi(), persisted);
                if (assignment.stock().getClassification() == null || !Objects.equals(assignment.stock().getClassification().getCode(), persisted.getCode()))
                    assignment.stock().setClassification(persisted);
            }
            int updated = industryHistory.assignIndustries(updates);
            unchanged = assignments.size() - updated;
            return new UploadResult(inputs.size(), updated, unchanged, created.size(), missing, unmatched, ambiguous, List.copyOf(issues));
        });
    }

    private record Input(int row, String symbol, String industry) {}
    private record Assignment(Stock stock, StockIndustry classification) {}

    private static void addIssue(List<UploadResult.Issue> issues, Input input, String reason) {
        if (issues.size() < 100) issues.add(new UploadResult.Issue(input.row(), input.symbol(), reason));
    }

    private static String industryKey(String industry) { return industry.trim().toUpperCase(Locale.ROOT); }

    /** UTF-8 CSV：支援 BOM、引號內逗號／換行及雙引號跳脫；拒絕未閉合或位置錯誤的引號。 */
    static List<List<String>> parseCsv(String text) {
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        List<List<String>> records = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false, closed = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') { value.append('"'); i++; }
                    else { quoted = false; closed = true; }
                } else value.append(c);
            } else if (c == ',' || c == '\n' || c == '\r') {
                row.add(value.toString()); value.setLength(0); closed = false;
                if (c != ',') {
                    if (row.stream().anyMatch(v -> !v.isBlank())) records.add(row);
                    row = new ArrayList<>();
                    if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                }
            } else if (c == '"' && value.isEmpty() && !closed) quoted = true;
            else {
                if (c == '"' || closed) throw new IllegalArgumentException("CSV_INVALID_QUOTE");
                value.append(c);
            }
        }
        if (quoted) throw new IllegalArgumentException("CSV_UNCLOSED_QUOTE");
        if (!row.isEmpty() || !value.isEmpty() || closed) {
            row.add(value.toString());
            if (row.stream().anyMatch(v -> !v.isBlank())) records.add(row);
        }
        return records;
    }
}
