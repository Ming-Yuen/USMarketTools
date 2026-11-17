package com.ykm.stock.analytics.service;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.*; import com.ykm.stock.analytics.service.analysis.calculator.MarketRegimeCalculator; import org.junit.jupiter.api.Test;
import java.math.*; import java.time.*; import java.util.*; import static org.assertj.core.api.Assertions.assertThat;
class MarketRegimeCalculatorTests {
 private final MarketRegimeCalculator calculator=new MarketRegimeCalculator();
 // 例子：SPY 與 QQQ 同向上升且無 VIX 時為 RISK_ON。
 @Test void risingSpyAndQqqAreRiskOn(){assertThat(calculator.classify(trend(false),trend(false),noVix()).value()).isEqualTo(MarketRegime.RISK_ON);}
 // 例子：SPY 與 QQQ 同向下跌且無 VIX 時為 BEAR。
 @Test void fallingSpyAndQqqAreBear(){assertThat(calculator.classify(trend(true),trend(true),noVix()).value()).isEqualTo(MarketRegime.BEAR);}
 // 例子：單一指數跌至前收 0.86 倍但無實際 VIX 時只是 CORRECTION。
 @Test void oneIndexCorrectionWithoutActualVixCannotBeCalledPanic(){List<NormalizedBarDto>s=trend(false),q=trend(false);s.set(271,withClose(s.get(271),s.get(270).close().multiply(new BigDecimal("0.86"))));assertThat(calculator.classify(s,q,noVix()).value()).isEqualTo(MarketRegime.CORRECTION);}
 // 例子：指數跌至 0.86 倍且 VIX 為 40 時可判定 PANIC。
 @Test void actualVixPlusDrawdownCanDeclarePanic(){List<NormalizedBarDto>s=trend(false),q=trend(false);s.set(271,withClose(s.get(271),s.get(270).close().multiply(new BigDecimal("0.86"))));assertThat(calculator.classify(s,q,MetricResult.available(new BigDecimal("40"))).value()).isEqualTo(MarketRegime.PANIC);}
 // 例子：指數曾跌至 0.82 倍後回升，在回到 RISK_ON 前先標 RECOVERY。
 @Test void recoveredIndexesWithRecentStressAreRecoveryBeforeRiskOn(){List<NormalizedBarDto>s=trend(false),q=trend(false);s.set(260,withClose(s.get(260),s.get(259).close().multiply(new BigDecimal("0.82"))));q.set(260,withClose(q.get(260),q.get(259).close().multiply(new BigDecimal("0.82"))));assertThat(calculator.classify(s,q,noVix()).value()).isEqualTo(MarketRegime.RECOVERY);}
 private MetricResult<BigDecimal> noVix(){return MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA,AnalysisReasonDto.of("NO_VIX","MARKET","none"));}
 private List<NormalizedBarDto> trend(boolean down){List<NormalizedBarDto>x=new ArrayList<>();for(int i=0;i<272;i++){BigDecimal c=down?BigDecimal.valueOf(300).subtract(BigDecimal.valueOf(i).multiply(new BigDecimal("0.5"))):BigDecimal.valueOf(100).add(BigDecimal.valueOf(i).multiply(new BigDecimal("0.5")));LocalDate d=LocalDate.of(2025,1,1).plusDays(i);Instant t=d.atStartOfDay(ZoneOffset.UTC).toInstant();x.add(new NormalizedBarDto(d,c,c,c,c,MetricResult.available(BigDecimal.ONE),t,t.plusSeconds(1),t.plusSeconds(2),t.plusSeconds(2),"Q"+i,"B"));}return x;}
 private NormalizedBarDto withClose(NormalizedBarDto b,BigDecimal c){return new NormalizedBarDto(b.sessionDate(),c,c,c,c,b.volume(),b.sessionOpenAt(),b.sessionCloseAt(),b.availableAt(),b.observedAt(),b.versionRef(),b.basisId());}
}
