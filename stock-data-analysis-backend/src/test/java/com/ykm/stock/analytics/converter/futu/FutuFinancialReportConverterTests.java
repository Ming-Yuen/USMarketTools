package com.ykm.stock.analytics.converter.futu;

import com.futu.openapi.pb.QotCommon.F10Type;
import com.futu.openapi.pb.QotCommon.FinancialStatementsType;
import com.futu.openapi.pb.QotGetFinancialsStatements;
import com.ykm.stock.analytics.model.entity.FinancialReport;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FutuFinancialReportConverterTests {
    private final FutuQuoteConverter converter = Mappers.getMapper(FutuQuoteConverter.class);
    private static final Instant OBSERVED_AT = Instant.parse("2026-11-13T02:35:57Z");

    @Test
    void missingTreasuryNamesUseVerifiedBalanceSheetIdsWithoutChangingAmountsOrObservationTime() {
        // 同期原始申報：LII 2026/Q3 -5,236.8m；JPM 2026/Q3 -179,437m，均為已扣於權益合計的庫藏股。
        FinancialReport row = convert(FinancialStatementsType.FinancialStatementsType_BalanceSheet,
                Map.of(), item(8092, -5236800000d), item(9077, -179437000000d));

        assertThat(row.getItems()).extracting(FinancialReport.Item::name).containsExactly("Less: Treasury Stock", "Less: Treasury Stock");
        assertThat(row.getItems().getFirst().value()).isEqualByComparingTo("-5236800000");
        assertThat(row.getItems().getLast().value()).isEqualByComparingTo("-179437000000");
        assertThat(row.getObservedAt()).isEqualTo(OBSERVED_AT);
        assertThat(row.getPeriodEnd()).isEqualTo(LocalDate.of(2026, 9, 29));
    }

    @Test
    void sourceNamesTakePrecedenceAndOtherCompositeIncomeKeepsItsOriginalName() {
        FinancialReport row = convert(FinancialStatementsType.FinancialStatementsType_BalanceSheet,
                Map.of(8092L, "Source revised label", 8093L, "Other Composite Income"), item(8092, -1d), item(8093, -71400000d));

        assertThat(row.getItems()).extracting(FinancialReport.Item::name).containsExactly("Source revised label", "Other Composite Income");
    }

    @Test
    void unknownIdsAndOtherStatementsRemainUnresolvedAndMissingAmountsRemainNull() {
        FinancialReport balance = convert(FinancialStatementsType.FinancialStatementsType_BalanceSheet,
                Map.of(8092L, " "), QotGetFinancialsStatements.FinancialItem.newBuilder().setFieldId(8092).build(), item(999999, 12d));
        FinancialReport income = convert(FinancialStatementsType.FinancialStatementsType_Income, Map.of(), item(8092, -1d));

        assertThat(balance.getItems().getFirst().name()).isEqualTo("Less: Treasury Stock");
        assertThat(balance.getItems().getFirst().value()).isNull();
        assertThat(balance.getItems().getLast().name()).isNull();
        assertThat(balance.getItems().getLast().value()).isEqualByComparingTo("12");
        assertThat(income.getItems().getFirst().name()).isNull();
    }

    private FinancialReport convert(FinancialStatementsType type, Map<Long, String> names,
                                    QotGetFinancialsStatements.FinancialItem... items) {
        QotGetFinancialsStatements.FinancialReport raw = QotGetFinancialsStatements.FinancialReport.newBuilder()
                .setDateTimeStr("2026-09-29").setFinancialType(F10Type.F10Type_Q3).setFiscalYear(2026).setCurrencyCode("USD")
                .addAllItemList(List.of(items)).build();
        return converter.toFinancialReport(raw, "FIGI", type, LocalDate.of(2026, 9, 29), OBSERVED_AT, names);
    }

    private QotGetFinancialsStatements.FinancialItem item(long id, double value) {
        return QotGetFinancialsStatements.FinancialItem.newBuilder().setFieldId(id).setData(value).build();
    }
}
