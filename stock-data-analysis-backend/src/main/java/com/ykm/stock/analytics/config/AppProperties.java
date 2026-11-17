package com.ykm.stock.analytics.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;

@Getter
@Setter
@Component
@Validated
@ConfigurationProperties(prefix = "app")
public class AppProperties {
    private Python python = new Python();
    @Valid
    private Overview overview = new Overview();

    @Getter
    @Setter
    public static class Python {
        private String domain;
    }

    @Getter
    @Setter
    public static class Overview {
        @Valid
        private Analysis analysis = new Analysis();
    }

    @Getter
    @Setter
    public static class Analysis {
        @NotNull
        private BigDecimal extremeGreed;
        @NotNull
        private BigDecimal extremeFear;
        @NotNull
        private BigDecimal rsiOverbought;
        @NotNull
        private BigDecimal rsiOversold;
        @NotNull
        private BigDecimal ma20ExtendedPercent;
        @NotNull
        private BigDecimal highVolumeRatio;
        @NotNull
        private BigDecimal lowVolumeRatio;
    }
}
