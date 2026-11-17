package com.ykm.stock.analytics.service.futu;

import com.futu.openapi.pb.QotCommon;
import com.futu.openapi.pb.QotRequestRehab;
import com.futu.openapi.pb.QotGetOwnerPlate;
import com.futu.openapi.pb.QotGetStaticInfo;
import com.futu.openapi.pb.QotGetPlateSet;
import com.futu.openapi.pb.QotGetSecuritySnapshot;
import com.ykm.stock.analytics.model.dto.classification.StockClassificationSnapshot;
import com.ykm.stock.analytics.converter.futu.FutuQuoteConverter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.mapstruct.factory.Mappers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class FutuServiceTests {
    @Test
    void unsupportedOtcSymbolDoesNotDiscardOtherStocksInSnapshotBatch() {
        QotGetSecuritySnapshot.Snapshot msft = QotGetSecuritySnapshot.Snapshot.newBuilder()
                .setBasic(QotGetSecuritySnapshot.SnapshotBasicData.newBuilder().setSecurity(security(US, "MSFT")).buildPartial())
                .setEquityExData(QotGetSecuritySnapshot.EquitySnapshotExData.newBuilder().setIssuedShares(100).buildPartial()).buildPartial();
        when(client.getSecuritySnapshots(List.of("NXXT", "MSFT"))).thenReturn(QotGetSecuritySnapshot.Response.newBuilder()
                .setRetType(-1).setRetMsg("US OTC market quote is not available for NXXT.").build());
        when(client.getSecuritySnapshots(List.of("MSFT"))).thenReturn(QotGetSecuritySnapshot.Response.newBuilder().setRetType(0)
                .setS2C(QotGetSecuritySnapshot.S2C.newBuilder().addSnapshotList(msft).buildPartial()).buildPartial());
        try (FutuService.ObservationSession session = service.openObservationSession()) {
            assertThat(session.shareCounts(List.of("NXXT", "MSFT"))).containsExactlyEntriesOf(Map.of("MSFT", new FutuService.ShareSnapshot(100, null, null, null)));
        }
        verify(client).getSecuritySnapshots(List.of("MSFT"));
    }

    @Test
    void shareSnapshotPreservesOutstandingSharesMarketCapAndNativeMultiples() {
        QotGetSecuritySnapshot.Snapshot valid = QotGetSecuritySnapshot.Snapshot.newBuilder()
                .setBasic(QotGetSecuritySnapshot.SnapshotBasicData.newBuilder().setSecurity(security(US, "AAPL")).setUpdateTime("2026-11-12 16:00:00").setCurPrice(100).buildPartial())
                .setEquityExData(QotGetSecuritySnapshot.EquitySnapshotExData.newBuilder()
                        .setIssuedShares(100).setOutstandingShares(60)
                        .setIssuedMarketVal(10000).setOutstandingMarketVal(6000)
                        .setPeTTMRate(28.5).setPbRate(35.2).buildPartial()).buildPartial();
        QotGetSecuritySnapshot.Snapshot empty = QotGetSecuritySnapshot.Snapshot.newBuilder()
                .setBasic(QotGetSecuritySnapshot.SnapshotBasicData.newBuilder().setSecurity(security(US, "JPM")).buildPartial()).buildPartial();
        when(client.getSecuritySnapshots(List.of("AAPL", "JPM"))).thenReturn(QotGetSecuritySnapshot.Response.newBuilder().setRetType(0)
                .setS2C(QotGetSecuritySnapshot.S2C.newBuilder().addSnapshotList(valid).addSnapshotList(empty).buildPartial()).buildPartial());
        try (FutuService.ObservationSession session = service.openObservationSession()) {
            FutuService.ShareSnapshot expected = new FutuService.ShareSnapshot(100L, 60L,
                    new java.math.BigDecimal("100.0"), new java.math.BigDecimal("10000.0"),
                    new java.math.BigDecimal("6000.0"), new java.math.BigDecimal("28.5"),
                    new java.math.BigDecimal("35.2"), java.time.Instant.parse("2026-11-12T21:00:00Z"));
            Map<String, FutuService.ShareSnapshot> actual = session.shareCounts(List.of("AAPL", "JPM"));
            assertThat(actual).containsExactlyEntriesOf(Map.of("AAPL", expected));
            assertThat(actual.get("AAPL").effectiveShares()).isEqualTo(60L);
            assertThat(actual.get("AAPL").effectiveMarketCap()).isEqualByComparingTo("6000.0");
            assertThatThrownBy(() -> session.shareCounts(java.util.Collections.nCopies(401, "AAPL"))).hasMessage("FUTU_SNAPSHOT_SCOPE_INVALID");
        }
        verify(client).close();
    }

    @Test
    void shareSnapshotProviderFailureOrMissingPayloadIsNotAnEmptySuccess() {
        when(client.getSecuritySnapshots(anyList())).thenReturn(QotGetSecuritySnapshot.Response.newBuilder().setRetType(-1).setRetMsg("denied").build());
        try (FutuService.ObservationSession session = service.openObservationSession()) {
            assertThatThrownBy(() -> session.shareCounts(List.of("AAPL"))).hasMessageContaining("FUTU_SHARE_SNAPSHOT_FAILED");
            when(client.getSecuritySnapshots(anyList())).thenReturn(QotGetSecuritySnapshot.Response.newBuilder().setRetType(0).build());
            assertThatThrownBy(() -> session.shareCounts(List.of("AAPL"))).hasMessage("FUTU_SHARE_SNAPSHOT_DATA_MISSING");
        }
    }
    @Test
    void rehabEmptySuccessIsDifferentFromMissingPayloadOrProviderFailure() {
        when(client.getRehab("AAPL")).thenReturn(QotRequestRehab.Response.newBuilder().setRetType(0)
                .setS2C(QotRequestRehab.S2C.newBuilder()).build());
        try (FutuService.ObservationSession session = service.openObservationSession()) {
            assertThat(session.rehab("AAPL")).isEmpty();
            when(client.getRehab("AAPL")).thenReturn(QotRequestRehab.Response.newBuilder().setRetType(0).build());
            assertThatThrownBy(() -> session.rehab("AAPL")).hasMessage("FUTU_REHAB_DATA_MISSING");
            when(client.getRehab("AAPL")).thenReturn(QotRequestRehab.Response.newBuilder().setRetType(-1).setRetMsg("Unknown stock").build());
            assertThatThrownBy(() -> session.rehab("AAPL")).hasMessageContaining("Unknown stock");
        }
        verify(client).close();
    }

    private static final int US = QotCommon.QotMarket.QotMarket_US_Security_VALUE;
    private static final int STOCK = QotCommon.SecurityType.SecurityType_Eqty_VALUE;
    private static final int TRUST = QotCommon.SecurityType.SecurityType_Trust_VALUE;
    private static final int INDUSTRY = QotCommon.PlateSetType.PlateSetType_Industry_VALUE;
    private static final int CONCEPT = QotCommon.PlateSetType.PlateSetType_Concept_VALUE;
    private final FutuQuoteClient client = mock(FutuQuoteClient.class);
    private final Runnable permits = mock(Runnable.class);
    private final FutuService service = new FutuService(() -> client, permits, Mappers.getMapper(FutuQuoteConverter.class));

    @BeforeEach
    void canonicalDictionaries() {
        when(client.getPlateSet(INDUSTRY)).thenReturn(dictionaryResponse(List.of(
                plate("LIST2430", "鈾", 0), plate("SECOND", "另一產業", 0))));
        when(client.getPlateSet(CONCEPT)).thenReturn(dictionaryResponse(List.of(
                plate("LIST2583", "核電", 0), plate("OTHER_CONCEPT", "另一概念", 0))));
    }

    @Test
    void financialReportSyncIncludesTrustClassifiedReitsButClassificationStaysStockOnly() {
        when(client.getStockBasicInfo()).thenReturn(staticInfo(List.of("AAPL")));
        when(client.getTrustBasicInfo()).thenReturn(staticInfo(TRUST, List.of("EQIX", "AGNC")));

        try (FutuService.ObservationSession session = service.openObservationSession()) {
            assertThat(session.supportedUsFinancialReportStocks()).containsExactlyInAnyOrder("AAPL", "EQIX", "AGNC");
        }
        try (FutuService.ClassificationSession session = service.openSession()) {
            assertThat(session.supportsStock("AAPL")).isTrue();
            assertThat(session.supportsStock("EQIX")).isFalse();
        }
        verify(client).getTrustBasicInfo();
    }

    @Test
    void trustUniverseFailureDoesNotSilentlySkipFinancialReports() {
        when(client.getStockBasicInfo()).thenReturn(staticInfo(List.of("AAPL")));
        when(client.getTrustBasicInfo()).thenReturn(QotGetStaticInfo.Response.newBuilder()
                .setRetType(-1).setRetMsg("Trust universe unavailable").build());

        try (FutuService.ObservationSession session = service.openObservationSession()) {
            assertThatThrownBy(session::supportedUsFinancialReportStocks)
                    .hasMessage("FUTU_GET_STATIC_INFO_FAILED:-1:Trust universe unavailable");
        }
    }

    @Test
    void canonicalDictionariesClassifyOwnerRelationsEvenWhenRawTypesSayOther() {
        when(client.getStockBasicInfo()).thenReturn(staticInfo(List.of("LEU", "AAPL")));
        when(client.getPlateSet(INDUSTRY)).thenReturn(dictionaryResponse(List.of(
                plate("LIST2430", "鈾", 0), plate("LIST2075", "消費電子產品", 0))));
        when(client.getPlateSet(CONCEPT)).thenReturn(dictionaryResponse(List.of(plate("LIST2999", "巴菲特持倉", 0))));
        QotGetOwnerPlate.SecurityOwnerPlate leu = QotGetOwnerPlate.SecurityOwnerPlate.newBuilder()
                .setSecurity(security(US, "LEU")).setName("Centrus Energy")
                .addPlateInfoList(plate("LIST2430", "owner名稱非權威", 4)).build();
        QotGetOwnerPlate.SecurityOwnerPlate aapl = QotGetOwnerPlate.SecurityOwnerPlate.newBuilder()
                .setSecurity(security(US, "AAPL")).setName("蘋果")
                .addPlateInfoList(plate("LIST2075", "另一個owner名稱", 1))
                .addPlateInfoList(plate("LIST2999", "owner名稱也非權威", 4)).build();
        when(client.getOwnerPlate(anyList())).thenReturn(ownerResponse(List.of(leu, aapl)));

        Map<String, StockClassificationSnapshot> actual = download(List.of("LEU", "AAPL"));

        assertThat(actual.get("LEU").industries()).extracting("code").containsExactly("US.LIST2430");
        assertThat(actual.get("LEU").industries()).extracting("name").containsExactly("鈾");
        assertThat(actual.get("AAPL").industries()).extracting("name").containsExactly("消費電子產品");
        assertThat(actual.get("AAPL").concepts()).extracting("name").containsExactly("巴菲特持倉");
    }

    @Test
    void emptyOrInvalidBatchDoesNotDispatchAnOwnerPlateQuery() {
        when(client.getStockBasicInfo()).thenReturn(staticInfo(List.of("LEU")));
        try (FutuService.ClassificationSession session = service.openSession()) {
            assertThat(session.getStockClassifications(List.of())).isEmpty();
            assertThatThrownBy(() -> session.getStockClassifications(List.of(" LEU")))
                    .hasMessage("FUTU_SYMBOL_INVALID");
        }
        verify(client, never()).getOwnerPlate(anyList());
        verifyNoInteractions(permits);
    }

    @Test
    void unsupportedCandidatesNeedNoClassificationDictionaries() {
        when(client.getStockBasicInfo()).thenReturn(staticInfo(List.of("LEU")));

        assertThat(download(List.of("UNKNOWN"))).isEmpty();

        verify(client, never()).getPlateSet(anyInt());
        verify(client, never()).getOwnerPlate(anyList());
    }

    @Test
    void stockUniverseMatchingKeepsExactCaseAndShareClassPunctuation() {
        when(client.getStockBasicInfo()).thenReturn(staticInfo(List.of("LEU", "BRK.B", "BCPC", "BCpC")));
        when(client.getOwnerPlate(anyList())).thenReturn(ownerResponse(List.of()));

        download(List.of("LEU", "BRK.B", "BCPC", "BCpC", "BRK-B", "UNKNOWN", "LEU"));

        verify(client).getOwnerPlate(List.of("LEU", "BRK.B", "BCPC", "BCpC"));
        verify(client).close();
    }

    @Test
    void batchesAtMostTwoHundredStocksAndUsesOnePermitPerRequest() {
        List<String> symbols = new ArrayList<>();
        for (int index = 0; index < 401; index++) symbols.add("STOCK" + index);
        when(client.getStockBasicInfo()).thenReturn(staticInfo(symbols));
        when(client.getOwnerPlate(anyList())).thenReturn(ownerResponse(List.of()));

        try (FutuService.ClassificationSession session = service.openSession()) {
            for (int offset = 0; offset < symbols.size(); offset += 200)
                session.getStockClassifications(symbols.subList(offset, Math.min(offset + 200, symbols.size())));
        }

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> batches = ArgumentCaptor.forClass(List.class);
        verify(client, times(3)).getOwnerPlate(batches.capture());
        assertThat(batches.getAllValues()).extracting(List::size).containsExactly(200, 200, 1);
        assertThat(batches.getAllValues().stream().flatMap(List::stream).toList()).containsExactlyElementsOf(symbols);
        verify(permits, times(3)).run();
        verify(client, times(1)).getStockBasicInfo();
        verify(client, times(1)).getPlateSet(INDUSTRY);
        verify(client, times(1)).getPlateSet(CONCEPT);
        verify(client).close();
    }

    @Test
    void aSessionRejectsAnOversizedBatchBeforeDispatching() {
        when(client.getStockBasicInfo()).thenReturn(staticInfo(List.of("LEU")));
        try (FutuService.ClassificationSession session = service.openSession()) {
            assertThatThrownBy(() -> session.getStockClassifications(java.util.Collections.nCopies(201, "LEU")))
                    .hasMessage("FUTU_CLASSIFICATION_BATCH_TOO_LARGE");
        }
        verify(client, never()).getOwnerPlate(anyList());
        verifyNoInteractions(permits);
    }

    @Test
    void preservesAllIndustriesAndConceptsWhileIgnoringOtherPlates() {
        when(client.getStockBasicInfo()).thenReturn(staticInfo(List.of("LEU")));
        QotGetOwnerPlate.SecurityOwnerPlate owner = QotGetOwnerPlate.SecurityOwnerPlate.newBuilder()
                .setSecurity(security(US, "LEU")).setName("Centrus Energy")
                .addPlateInfoList(plate("LIST2430", "鈾", INDUSTRY))
                .addPlateInfoList(plate("SECOND", "另一產業", INDUSTRY))
                .addPlateInfoList(plate("LIST2583", "核電", CONCEPT))
                .addPlateInfoList(plate("OTHER_CONCEPT", "另一概念", CONCEPT))
                .addPlateInfoList(plate("IGNORE", "不作分類", QotCommon.PlateSetType.PlateSetType_Other_VALUE)).build();
        when(client.getOwnerPlate(List.of("LEU"))).thenReturn(ownerResponse(List.of(owner)));

        StockClassificationSnapshot result = download(List.of("LEU")).get("LEU");

        assertThat(result.name()).isEqualTo("Centrus Energy");
        assertThat(result.industries()).extracting("code").containsExactly("US.LIST2430", "US.SECOND");
        assertThat(result.concepts()).extracting("name").containsExactly("核電", "另一概念");
    }

    @Test
    void returnedStockWithNoPlatesIsRetainedForExplicitMissingClassificationHandling() {
        when(client.getStockBasicInfo()).thenReturn(staticInfo(List.of("LEU")));
        when(client.getOwnerPlate(anyList())).thenReturn(ownerResponse(List.of(QotGetOwnerPlate.SecurityOwnerPlate.newBuilder()
                .setSecurity(security(US, "LEU")).setName("Centrus Energy").build())));

        StockClassificationSnapshot result = download(List.of("LEU")).get("LEU");

        assertThat(result.industries()).isEmpty();
        assertThat(result.concepts()).isEmpty();
    }

    @Test
    void rejectsUnexpectedStockAndWrongMarketReplies() {
        when(client.getStockBasicInfo()).thenReturn(staticInfo(List.of("LEU")));
        for (QotCommon.Security identity : List.of(security(US, "UNKNOWN"), security(1, "LEU"))) {
            when(client.getOwnerPlate(anyList())).thenReturn(ownerResponse(List.of(QotGetOwnerPlate.SecurityOwnerPlate.newBuilder()
                    .setSecurity(identity).setName("Unexpected").build())));
            assertThatThrownBy(() -> download(List.of("LEU")))
                    .isInstanceOf(IllegalStateException.class).hasMessageStartingWith("FUTU_OWNER_PLATE_IDENTITY_MISMATCH:");
        }
        verify(client, times(2)).close();
    }

    @Test
    void duplicateStockRepliesCannotSilentlyOverwriteOneAnother() {
        when(client.getStockBasicInfo()).thenReturn(staticInfo(List.of("LEU")));
        QotGetOwnerPlate.SecurityOwnerPlate owner = QotGetOwnerPlate.SecurityOwnerPlate.newBuilder()
                .setSecurity(security(US, "LEU")).setName("Centrus Energy").build();
        when(client.getOwnerPlate(anyList())).thenReturn(ownerResponse(List.of(owner, owner)));

        assertThatThrownBy(() -> download(List.of("LEU")))
                .hasMessage("FUTU_OWNER_PLATE_DUPLICATE_SYMBOL:LEU");
        verify(client).close();
    }

    @Test
    void providerFailureKeepsOriginalErrorAndClosesWithoutRetry() {
        when(client.getStockBasicInfo()).thenReturn(staticInfo(List.of("LEU")));
        when(client.getOwnerPlate(anyList())).thenReturn(QotGetOwnerPlate.Response.newBuilder()
                .setRetType(-1).setRetMsg("Original provider error").build());

        assertThatThrownBy(() -> download(List.of("LEU")))
                .hasMessage("FUTU_GET_OWNER_PLATE_FAILED:-1:Original provider error");
        verify(client, times(1)).getOwnerPlate(List.of("LEU"));
        verify(client).close();
    }

    @Test
    void staticInfoFailureDoesNotDispatchAnOwnerPlateQuery() {
        when(client.getStockBasicInfo()).thenReturn(QotGetStaticInfo.Response.newBuilder()
                .setRetType(-1).setRetMsg("Unavailable universe").build());

        assertThatThrownBy(() -> download(List.of("LEU")))
                .hasMessage("FUTU_GET_STATIC_INFO_FAILED:-1:Unavailable universe");
        verify(client, never()).getOwnerPlate(anyList());
        verify(client).close();
    }

    @Test
    void overlappingIndustryAndConceptDictionariesFailBeforeAnyOwnerRequest() {
        when(client.getStockBasicInfo()).thenReturn(staticInfo(List.of("LEU")));
        when(client.getPlateSet(CONCEPT)).thenReturn(dictionaryResponse(List.of(plate("LIST2430", "鈾", 0))));

        assertThatThrownBy(() -> download(List.of("LEU"))).hasMessage("FUTU_PLATE_DICTIONARY_OVERLAP:US.LIST2430");
        verify(client, never()).getOwnerPlate(anyList());
        verify(client).close();
    }

    @Test
    void duplicateDictionaryCodeFailsInsteadOfChoosingAnArbitraryLabel() {
        when(client.getStockBasicInfo()).thenReturn(staticInfo(List.of("LEU")));
        when(client.getPlateSet(INDUSTRY)).thenReturn(dictionaryResponse(List.of(
                plate("LIST2430", "鈾", 0), plate("LIST2430", "另一名稱", 0))));

        assertThatThrownBy(() -> download(List.of("LEU"))).hasMessage("FUTU_PLATE_DICTIONARY_DUPLICATE:US.LIST2430");
        verify(client, never()).getOwnerPlate(anyList());
        verify(client).close();
    }

    @Test
    void missingOrEmptyDictionaryCannotClearPreviouslyKnownConcepts() {
        when(client.getStockBasicInfo()).thenReturn(staticInfo(List.of("LEU")));
        when(client.getPlateSet(CONCEPT)).thenReturn(QotGetPlateSet.Response.newBuilder().setRetType(0).build());
        assertThatThrownBy(() -> download(List.of("LEU"))).hasMessage("FUTU_PLATE_DICTIONARY_DATA_MISSING:" + CONCEPT);

        when(client.getPlateSet(CONCEPT)).thenReturn(dictionaryResponse(List.of()));
        assertThatThrownBy(() -> download(List.of("LEU"))).hasMessage("FUTU_PLATE_DICTIONARY_EMPTY:" + CONCEPT);
        verify(client, never()).getOwnerPlate(anyList());
        verify(client, times(2)).close();
    }

    @Test
    void dictionaryFailurePreservesProviderErrorWithoutRetry() {
        when(client.getStockBasicInfo()).thenReturn(staticInfo(List.of("LEU")));
        when(client.getPlateSet(INDUSTRY)).thenReturn(QotGetPlateSet.Response.newBuilder()
                .setRetType(-1).setRetMsg("Dictionary permission denied").build());

        assertThatThrownBy(() -> download(List.of("LEU"))).hasMessage("FUTU_GET_PLATE_SET_FAILED:" + INDUSTRY + ":-1:Dictionary permission denied");
        verify(client, times(1)).getPlateSet(INDUSTRY);
        verify(client, never()).getOwnerPlate(anyList());
        verify(client).close();
    }

    private Map<String, StockClassificationSnapshot> download(List<String> symbols) {
        try (FutuService.ClassificationSession session = service.openSession()) {
            return session.getStockClassifications(symbols);
        }
    }

    private static QotCommon.Security security(int market, String code) {
        return QotCommon.Security.newBuilder().setMarket(market).setCode(code).build();
    }

    private static QotCommon.PlateInfo plate(String code, String name, int type) {
        return QotCommon.PlateInfo.newBuilder().setPlate(security(US, code)).setName(name).setPlateType(type).build();
    }

    private static QotGetStaticInfo.Response staticInfo(List<String> symbols) {
        return staticInfo(STOCK, symbols);
    }

    private static QotGetStaticInfo.Response staticInfo(int securityType, List<String> symbols) {
        QotGetStaticInfo.S2C.Builder result = QotGetStaticInfo.S2C.newBuilder();
        for (String symbol : symbols) result.addStaticInfoList(QotCommon.SecurityStaticInfo.newBuilder().setBasic(
                QotCommon.SecurityStaticBasic.newBuilder().setSecurity(security(US, symbol)).setId(1).setLotSize(1)
                        .setSecType(securityType).setName(symbol).setListTime("2000-01-01").setDelisting(false)));
        return QotGetStaticInfo.Response.newBuilder().setRetType(0).setS2C(result).build();
    }

    private static QotGetOwnerPlate.Response ownerResponse(List<QotGetOwnerPlate.SecurityOwnerPlate> owners) {
        return QotGetOwnerPlate.Response.newBuilder().setRetType(0)
                .setS2C(QotGetOwnerPlate.S2C.newBuilder().addAllOwnerPlateList(owners)).build();
    }

    private static QotGetPlateSet.Response dictionaryResponse(List<QotCommon.PlateInfo> plates) {
        return QotGetPlateSet.Response.newBuilder().setRetType(0)
                .setS2C(QotGetPlateSet.S2C.newBuilder().addAllPlateInfoList(plates)).build();
    }
}
