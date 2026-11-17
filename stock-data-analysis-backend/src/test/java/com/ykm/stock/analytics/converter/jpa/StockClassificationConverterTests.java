package com.ykm.stock.analytics.converter.jpa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ykm.stock.analytics.model.dto.massive.response.MassiveTickerDetails;
import com.ykm.stock.analytics.model.dto.stockInsight.StockClassificationResponse;
import com.ykm.stock.analytics.model.dto.stockInsight.StockConceptDto;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockConcept;
import com.ykm.stock.analytics.model.entity.StockIndustry;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class StockClassificationConverterTests {
    private final StockClassificationConverter converter = Mappers.getMapper(StockClassificationConverter.class);

    /** Scenario：CSV 分類只建立代碼及名稱，不順帶建立估值模型或市場倍數。 */
    @Test
    void csvCreatesOnlyClassificationAndDoesNotInventClassificationPlate() {
        StockIndustry value = converter.fromCsv("I43", "Semiconductors");

        assertThat(value.getCode()).isEqualTo("I43");
        assertThat(value.getIndustry()).isEqualTo("Semiconductors");
        assertThat(value.getSourcePlateCode()).isNull();
        JsonNode json = new ObjectMapper().valueToTree(converter.toClassificationResponse(List.of(value))[0]);
        assertThat(json.size()).isEqualTo(3);
        assertThat(json.has("valuationModelCode")).isFalse();
        assertThat(json.has("marketPeMedian")).isFalse();
        assertThat(json.has("fairPeLow")).isFalse();
    }

    @Test
    void dictionaryResponseKeepsClassificationPlateAndConceptsIndependent() {
        StockIndustry industry = converter.fromCsv("I43", "Semiconductors");
        industry.setSourcePlateCode("US.BK2004");
        StockConcept concept = new StockConcept();
        concept.setCode("C1");
        concept.setName("人工智能");

        StockClassificationResponse.Classification[] rows = converter.toClassificationResponse(List.of(industry));

        assertThat(rows).containsExactly(new StockClassificationResponse.Classification("I43", "Semiconductors", "US.BK2004"));
        assertThat(converter.toConceptResponse(List.of(concept))).containsExactly(new StockConceptDto("C1", "人工智能"));
        assertThat(converter.toClassificationResponse(List.of())).isEmpty();
    }

    /** Scenario：主檔更新不覆蓋來源以外的分類、概念或既有上市日期。 */
    @Test
    void massiveRefreshPreservesClassificationConceptsAndExistingListingDate() {
        StockIndustry classification = converter.fromCsv("I43", "Semiconductors");
        StockConcept concept = new StockConcept();
        concept.setCode("C1");
        concept.setName("人工智能");
        Stock stock = new Stock();
        stock.setFigi("FIGI1");
        stock.setClassification(classification);
        stock.getConcepts().add(concept);
        stock.setListDate(LocalDate.of(2014, 9, 30));
        MassiveTickerDetails reference = new MassiveTickerDetails();
        reference.setTicker("LEU");
        reference.setName("Centrus Energy Corp.");

        Mappers.getMapper(StockConverter.class).updateFromMassive(reference, stock);

        assertThat(stock.getClassification()).isSameAs(classification);
        assertThat(stock.getConcepts()).containsExactly(concept);
        assertThat(stock.getSymbol()).isEqualTo("LEU");
        assertThat(stock.getListDate()).isEqualTo(LocalDate.of(2014, 9, 30));
    }
}
