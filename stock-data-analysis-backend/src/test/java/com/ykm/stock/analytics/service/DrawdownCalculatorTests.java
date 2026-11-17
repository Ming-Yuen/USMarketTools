package com.ykm.stock.analytics.service;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.*; import com.ykm.stock.analytics.service.analysis.calculator.DrawdownCalculator; import org.junit.jupiter.api.Test;
import java.math.*; import java.time.*; import java.util.*; import static org.assertj.core.api.Assertions.assertThat;
class DrawdownCalculatorTests {
 private final DrawdownCalculator calculator=new DrawdownCalculator();
 // 例子：收盤 100、120、90 再回到 120 時，最大回撤為 -25%，且只在收復後結束。
 @Test void maximumDrawdownUsesRunningPeakAndCompletedEpisodeEndsOnlyAfterRecovery(){List<BigDecimal>c=List.of(bd("100"),bd("120"),bd("110"),bd("90"),bd("100"),bd("120"),bd("125"));var r=calculator.calculate(bars(c),null);assertThat(r.maximumCloseDrawdown().value()).isEqualByComparingTo("-0.25");assertThat(r.completedEpisodes()).hasSize(1);assertThat(r.completedEpisodes().get(0).troughDrawdown()).isEqualByComparingTo("-0.25");assertThat(r.currentEpisode()).isNull();}
 // 例子：100、120、100、90 的未完成修正不算已完成中位數。
 @Test void openCorrectionIsNotCountedAsCompletedMedian(){var r=calculator.calculate(bars(List.of(bd("100"),bd("120"),bd("100"),bd("90"))),null);assertThat(r.completedEpisodes()).isEmpty();assertThat(r.currentEpisode()).isNotNull();assertThat(r.completedCorrectionMedian().status()).isEqualTo(MetricStatus.INSUFFICIENT_DATA);}
 // 例子：只有 100 與 90 兩日，不得假裝有 252 日回撤。
 @Test void shortHistoryCannotPretendToHave252DayDrawdown(){assertThat(calculator.calculate(bars(List.of(bd("100"),bd("90"))),null).drawdown252().status()).isEqualTo(MetricStatus.INSUFFICIENT_DATA);}
 private List<NormalizedBarDto> bars(List<BigDecimal> closes){List<NormalizedBarDto>x=new ArrayList<>();for(int i=0;i<closes.size();i++){LocalDate d=LocalDate.of(2026,1,1).plusDays(i);Instant t=d.atStartOfDay(ZoneOffset.UTC).toInstant();BigDecimal c=closes.get(i);x.add(new NormalizedBarDto(d,c,c,c,c,MetricResult.available(BigDecimal.ONE),t,t.plusSeconds(1),t.plusSeconds(2),t.plusSeconds(2),"Q"+i,"B"));}return x;} private BigDecimal bd(String s){return new BigDecimal(s);}
}
