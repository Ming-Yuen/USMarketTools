package com.ykm.stock.analytics.service.stock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ykm.stock.analytics.converter.jpa.StockClassificationConverter;
import com.ykm.stock.analytics.model.dto.stockInsight.StockClassificationRequest;
import com.ykm.stock.analytics.model.dto.stockInsight.StockClassificationResponse;
import com.ykm.stock.analytics.model.dto.stockInsight.StockConceptDto;
import com.ykm.stock.analytics.model.entity.StockConcept;
import com.ykm.stock.analytics.model.entity.StockIndustry;
import com.ykm.stock.analytics.repository.jpa.StockConceptRepository;
import com.ykm.stock.analytics.repository.jpa.StockIndustryRepository;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.repository.jpa.StockStatusRepository;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class StockClassificationApiTests {
    private final StockIndustryRepository industries = mock(StockIndustryRepository.class);
    private final StockConceptRepository concepts = mock(StockConceptRepository.class);
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final StockService service = new StockService(mock(StockRepository.class), mock(StockStatusRepository.class),
            industries, Mappers.getMapper(StockClassificationConverter.class), concepts, jdbc);

    /** Scenario：分類 API 分別回傳產業與概念，不把估值設定或快照塞進分類字典。 */
    @Test
    void returnsIndependentIndustryAndConceptDictionariesWithoutValuationFields() {
        StockIndustry industry = new StockIndustry();
        industry.setCode("I43");
        industry.setIndustry("Semiconductors");
        industry.setSourcePlateCode("US.BK2004");
        StockConcept concept = new StockConcept();
        concept.setCode("C1");
        concept.setName("人工智能");
        when(industries.findAll(any(Specification.class), any(Sort.class))).thenReturn(List.of(industry));
        when(concepts.findAll(any(Sort.class))).thenReturn(List.of(concept));

        StockClassificationResponse response = service.getStockClassification(new StockClassificationRequest());

        assertThat(response.getClassifications()).containsExactly(
                new StockClassificationResponse.Classification("I43", "Semiconductors", "US.BK2004"));
        assertThat(response.getConcepts()).containsExactly(new StockConceptDto("C1", "人工智能"));
        JsonNode json = new ObjectMapper().valueToTree(response);
        assertThat(json.has("stockClassificationTrees")).isFalse();
        assertThat(json.path("classifications").get(0).has("fairPeLow")).isFalse();
        assertThat(json.path("classifications").get(0).has("marketPeMedian")).isFalse();
        verify(industries).findAll(any(Specification.class), eq(Sort.by("industry", "code")));
        verify(concepts).findAll(Sort.by("name", "code"));
        verifyNoInteractions(jdbc);
    }

    @Test
    void missingRequestAndEmptyDictionariesReturnEmptyArrays() {
        when(industries.findAll(any(Specification.class), any(Sort.class))).thenReturn(List.of());
        when(concepts.findAll(any(Sort.class))).thenReturn(List.of());

        StockClassificationResponse response = service.getStockClassification((StockClassificationRequest) null);

        assertThat(response.getClassifications()).isEmpty();
        assertThat(response.getConcepts()).isEmpty();
        verifyNoInteractions(jdbc);
    }
}
