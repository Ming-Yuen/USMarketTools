package com.ykm.stock.analytics.model.dto.treasury;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TreasuryYieldCurveFeed {

    private static final String ATOM_NS = "http://www.w3.org/2005/Atom";

    private static final String M_NS = "http://schemas.microsoft.com/ado/2007/08/dataservices/metadata";

    private static final String D_NS = "http://schemas.microsoft.com/ado/2007/08/dataservices";

    @JacksonXmlProperty(localName = "title", namespace = ATOM_NS)
    private String title;

    @JacksonXmlProperty(localName = "id", namespace = ATOM_NS)
    private String id;

    @JacksonXmlProperty(localName = "updated", namespace = ATOM_NS)
    private String updated;

    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "entry", namespace = ATOM_NS)
    private List<Entry> entries;


    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Entry {

        @JacksonXmlProperty(localName = "id", namespace = ATOM_NS)
        private String id;

        @JacksonXmlProperty(localName = "updated", namespace = ATOM_NS)
        private String updated;

        @JacksonXmlProperty(localName = "content", namespace = ATOM_NS)
        private Content content;
    }


    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Content {

        @JacksonXmlProperty(localName = "properties", namespace = M_NS)
        private Properties properties;
    }


    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Properties {

        @JacksonXmlProperty(localName = "Id", namespace = D_NS)
        private Integer id;

        @JacksonXmlProperty(localName = "NEW_DATE", namespace = D_NS)
        private LocalDateTime date;

        @JacksonXmlProperty(localName = "BC_1MONTH", namespace = D_NS)
        private BigDecimal oneMonth;

        @JacksonXmlProperty(localName = "BC_1_5MONTH", namespace = D_NS)
        private BigDecimal onePointFiveMonth;

        @JacksonXmlProperty(localName = "BC_2MONTH", namespace = D_NS)
        private BigDecimal twoMonth;

        @JacksonXmlProperty(localName = "BC_3MONTH", namespace = D_NS)
        private BigDecimal threeMonth;

        @JacksonXmlProperty(localName = "BC_4MONTH", namespace = D_NS)
        private BigDecimal fourMonth;

        @JacksonXmlProperty(localName = "BC_6MONTH", namespace = D_NS)
        private BigDecimal sixMonth;

        @JacksonXmlProperty(localName = "BC_1YEAR", namespace = D_NS)
        private BigDecimal oneYear;

        @JacksonXmlProperty(localName = "BC_2YEAR", namespace = D_NS)
        private BigDecimal twoYear;

        @JacksonXmlProperty(localName = "BC_3YEAR", namespace = D_NS)
        private BigDecimal threeYear;

        @JacksonXmlProperty(localName = "BC_5YEAR", namespace = D_NS)
        private BigDecimal fiveYear;

        @JacksonXmlProperty(localName = "BC_7YEAR", namespace = D_NS)
        private BigDecimal sevenYear;

        @JacksonXmlProperty(localName = "BC_10YEAR", namespace = D_NS)
        private BigDecimal tenYear;

        @JacksonXmlProperty(localName = "BC_20YEAR", namespace = D_NS)
        private BigDecimal twentyYear;

        @JacksonXmlProperty(localName = "BC_30YEAR", namespace = D_NS)
        private BigDecimal thirtyYear;

        @JacksonXmlProperty(localName = "BC_30YEARDISPLAY", namespace = D_NS)
        private BigDecimal thirtyYearDisplay;
    }
}