package com.ykm.stock.analytics.converter.futu;

import com.futu.openapi.pb.QotCommon;
import com.futu.openapi.pb.QotGetFinancialsStatements;
import com.futu.openapi.pb.QotGetOwnerPlate;
import com.ykm.stock.analytics.model.dto.classification.ClassificationPlate;
import com.ykm.stock.analytics.model.dto.classification.StockClassificationSnapshot;
import com.ykm.stock.analytics.model.entity.FinancialReport;
import com.ykm.stock.analytics.model.entity.StockSplit;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Context;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Mapper(componentModel = "spring")
public interface FutuQuoteConverter {
    /** 價格復權因子亦含現金股息；拆股只讀股份數欄位，不能直接使用 factorA。 */
    default StockSplit toStockSplit(QotCommon.Rehab input, String figi, LocalDate from, LocalDate through, Instant observedAt) {
        LocalDate date = usMarketDate(input.hasTime() ? input.getTime() : null, null);
        if (date == null) throw new IllegalStateException("SPLIT_SOURCE_DATE_MISSING");
        if (date.isBefore(from) || date.isAfter(through)) return null;
        long flags = input.getCompanyActFlag();
        boolean split = (flags & QotCommon.CompanyAct.CompanyAct_Split_VALUE) != 0 || input.hasSplitBase() || input.hasSplitErt();
        boolean join = (flags & QotCommon.CompanyAct.CompanyAct_Join_VALUE) != 0 || input.hasJoinBase() || input.hasJoinErt();
        boolean bonus = (flags & QotCommon.CompanyAct.CompanyAct_Bonus_VALUE) != 0 || input.hasBonusBase() || input.hasBonusErt();
        boolean transfer = (flags & QotCommon.CompanyAct.CompanyAct_Transfer_VALUE) != 0 || input.hasTransferBase() || input.hasTransferErt();
        int actions = (split ? 1 : 0) + (join ? 1 : 0) + (bonus ? 1 : 0) + (transfer ? 1 : 0);
        if (actions == 0) return null;
        // 同日多種股份行動的前後基準未經確認，不猜測相乘或相加。
        if (actions > 1) throw new IllegalStateException("SPLIT_MULTIPLE_SHARE_ACTIONS:" + date);
        int base;
        int entitlement;
        if (split) {
            base = input.getSplitBase();
            entitlement = input.getSplitErt();
        } else if (join) {
            base = input.getJoinBase();
            entitlement = input.getJoinErt();
        } else if (bonus) {
            base = input.getBonusBase();
            entitlement = input.getBonusErt();
        } else {
            base = input.getTransferBase();
            entitlement = input.getTransferErt();
        }
        if (base <= 0 || entitlement <= 0) throw new IllegalStateException("SPLIT_SOURCE_VALUE_INVALID:" + date);
        BigDecimal before = BigDecimal.valueOf(base);
        BigDecimal after = BigDecimal.valueOf(entitlement);
        if (bonus || transfer) after = before.add(after); // 每 10 股送 3 股即 10 → 13。
        if (before.compareTo(after) == 0) return null;
        StockSplit row = new StockSplit();
        row.setSourceEventId("FUTU:" + figi + ":" + date);
        row.setFigi(figi);
        row.setExecutionDate(date);
        row.setSplitFrom(before);
        row.setSplitTo(after);
        row.setObservedAt(observedAt);
        return row;
    }

    @Mapping(target = "code", expression = "java(\"US.\" + plate.getPlate().getCode())")
    @Mapping(target = "name", source = "plate.name")
    ClassificationPlate toPlate(QotCommon.PlateInfo plate);

    @Mapping(target = "symbol", source = "owner.security.code")
    @Mapping(target = "name", source = "owner.name")
    @Mapping(target = "industries", source = "industries")
    @Mapping(target = "concepts", source = "concepts")
    StockClassificationSnapshot toClassification(QotGetOwnerPlate.SecurityOwnerPlate owner,
                                            List<ClassificationPlate> industries, List<ClassificationPlate> concepts);

    @Mapping(target = "figi", source = "figi")
    @Mapping(target = "periodEnd", source = "periodEnd")
    @Mapping(target = "observedAt", source = "observedAt")
    @Mapping(target = "statementType", expression = "java(statement.getNumber())")
    @Mapping(target = "periodType", expression = "java(financialPeriodType(report))")
    @Mapping(target = "fiscalYear", expression = "java(report.hasFiscalYear() && report.getFiscalYear() != 0 ? report.getFiscalYear() : null)")
    @Mapping(target = "period", source = "report.periodText")
    @Mapping(target = "currency", expression = "java(report.hasCurrencyCode() && !report.getCurrencyCode().isBlank() ? report.getCurrencyCode() : null)")
    @Mapping(target = "accountingStandards", source = "report.accountingStandards")
    @Mapping(target = "auditorReport", source = "report.auditorReport")
    @Mapping(target = "items", expression = "java(financialItems(report, names, statement))")
    @Mapping(target = "stock", ignore = true)
    FinancialReport toFinancialReport(QotGetFinancialsStatements.FinancialReport report, String figi,
                                      QotCommon.FinancialStatementsType statement, LocalDate periodEnd, Instant observedAt,
                                      @Context Map<Long, String> names);

    default int financialPeriodType(QotGetFinancialsStatements.FinancialReport report) {
        int periodType = report.getFinancialType().getNumber();
        if (periodType < 1 || (periodType > 4 && periodType != 7))
            throw new IllegalStateException("FUTU_FINANCIAL_REPORT_PERIOD_TYPE_INVALID:" + periodType);
        return periodType;
    }

    default List<FinancialReport.Item> financialItems(QotGetFinancialsStatements.FinancialReport report, Map<Long, String> names,
                                                    QotCommon.FinancialStatementsType statement) {
        return report.getItemListList().stream().filter(QotGetFinancialsStatements.FinancialItem::hasFieldId)
                .map(item -> new FinancialReport.Item(item.getFieldId(), financialItemName(item.getFieldId(), names, statement),
                        item.hasData() ? finite(item.getData()) : null, item.hasYoy() ? finite(item.getYoy()) : null,
                        item.hasQoq() ? finite(item.getQoq()) : null)).toList();
    }

    default String financialItemName(long fieldId, Map<Long, String> names, QotCommon.FinancialStatementsType statement) {
        String name = names.get(fieldId);
        if (name != null && !name.isBlank()) return name;
        // Futu 2026/Q3 結構漏名；同 ID 歷史名稱及 JPM/LII/SSNC 同期申報均確認為庫藏股金額。
        // 僅修補這兩個資產負債表 ID 的缺名；來源改名或其他未知項目仍交由映射審核。
        if (statement == QotCommon.FinancialStatementsType.FinancialStatementsType_BalanceSheet && (fieldId == 8092 || fieldId == 9077))
            return "Less: Treasury Stock";
        return name;
    }

    static LocalDate usMarketDate(String date, Long timestamp) {
        if (date != null && !date.isBlank()) return LocalDate.parse(date);
        return timestamp == null ? null : Instant.ofEpochSecond(timestamp).atZone(MarketStatusService.NY_ZONE).toLocalDate();
    }

    /** 財報日是日期；實測 Futu 以香港零時編碼，不能採其美東前一天的顯示字串。 */
    static LocalDate financialPeriodEnd(QotGetFinancialsStatements.FinancialReport report) {
        if (report.hasDateTime()) {
            OffsetDateTime encoded = Instant.ofEpochSecond(report.getDateTime()).atOffset(ZoneOffset.ofHours(8));
            if (encoded.toLocalTime().equals(LocalTime.MIDNIGHT)) return encoded.toLocalDate();
        }
        return usMarketDate(report.hasDateTimeStr() ? report.getDateTimeStr() : null, report.hasDateTime() ? report.getDateTime() : null);
    }

    static BigDecimal finite(double value) {
        if (!Double.isFinite(value)) throw new IllegalStateException("FUTU_OBSERVATION_VALUE_INVALID");
        return BigDecimal.valueOf(value);
    }
}
