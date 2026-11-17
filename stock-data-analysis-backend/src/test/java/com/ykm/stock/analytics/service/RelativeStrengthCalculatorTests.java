package com.ykm.stock.analytics.service;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.*; import com.ykm.stock.analytics.service.analysis.calculator.RelativeStrengthCalculator; import org.junit.jupiter.api.Test;
import java.math.*; import java.time.*; import java.util.*; import static org.assertj.core.api.Assertions.assertThat;
class RelativeStrengthCalculatorTests {
 private final RelativeStrengthCalculator calculator=new RelativeStrengthCalculator();
 // 例子：STOCK 收 95、SPY 收 85 時，20 日超額報酬為正 10 個百分點。
 @Test void minusFiveVersusMinusFifteenIsPlusTenPercentagePoints(){List<NormalizedBarDto>s=bars(121,"STOCK"),b=bars(121,"SPY");s.set(120,withClose(s.get(120),new BigDecimal("95")));b.set(120,withClose(b.get(120),new BigDecimal("85")));var r=calculator.calculate(s,b,"SPY");assertThat(r.windows().get(20).value().stockReturn()).isEqualByComparingTo("-0.05");assertThat(r.windows().get(20).value().benchmarkReturn()).isEqualByComparingTo("-0.15");assertThat(r.excessReturn20().value()).isEqualByComparingTo("0.10");assertThat(r.primarySignal().value()).isEqualByComparingTo("1");}
 // 例子：SPY 缺少第 100 根預期時段時，60 日窗口不得縮小。
 @Test void missingExpectedBenchmarkSessionDoesNotShrinkWindow(){List<NormalizedBarDto>s=bars(121,"S"),b=new ArrayList<>(bars(121,"B"));b.remove(100);var r=calculator.calculate(s,b,"SPY");assertThat(r.excessReturn60().status()).isEqualTo(MetricStatus.INSUFFICIENT_DATA);assertThat(r.excessReturn60().reasons()).extracting(AnalysisReasonDto::code).contains("BENCHMARK_SESSION_MISSING");}
 private List<NormalizedBarDto> bars(int n,String prefix){List<NormalizedBarDto>x=new ArrayList<>();for(int i=0;i<n;i++){LocalDate d=LocalDate.of(2025,1,1).plusDays(i);Instant t=d.atStartOfDay(ZoneOffset.UTC).toInstant();BigDecimal c=new BigDecimal("100");x.add(new NormalizedBarDto(d,c,c,c,c,MetricResult.available(BigDecimal.ONE),t,t.plusSeconds(1),t.plusSeconds(2),t.plusSeconds(2),prefix+i,"B"));}return x;}
 private NormalizedBarDto withClose(NormalizedBarDto b,BigDecimal c){return new NormalizedBarDto(b.sessionDate(),c,c,c,c,b.volume(),b.sessionOpenAt(),b.sessionCloseAt(),b.availableAt(),b.observedAt(),b.versionRef(),b.basisId());}
}
