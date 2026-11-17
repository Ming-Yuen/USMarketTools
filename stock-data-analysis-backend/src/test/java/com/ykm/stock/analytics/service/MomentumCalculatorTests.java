package com.ykm.stock.analytics.service;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.*;
import com.ykm.stock.analytics.service.analysis.calculator.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal; import java.time.*; import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class MomentumCalculatorTests {
    private final MomentumCalculator calculator=new MomentumCalculator(new WilderAtrCalculator());
    // 例子：收盤都是 100 時，114 根不足 RSI14，115 根才是 50。
    @Test void rsiRequiresFullWarmupAndFlatSeriesIsFifty(){
        assertThat(calculator.calculate(bars(114,i->100)).indicators().get("RSI14").status()).isEqualTo(MetricStatus.INSUFFICIENT_DATA);
        MomentumAnalysisDto result=calculator.calculate(bars(115,i->100));
        assertThat(result.indicators().get("RSI14").value()).isEqualByComparingTo("50.0000");
        assertThat(result.primarySignal().status()).isEqualTo(MetricStatus.AVAILABLE);
    }
    // 例子：收盤由 100 遞增的 150 根可算出 EMA20、EMA50 與 MACD。
    @Test void emaAndMacdUseDeclaredWarmups(){
        MomentumAnalysisDto r=calculator.calculate(bars(150,i->100+i));
        assertThat(r.indicators().get("EMA20").status()).isEqualTo(MetricStatus.AVAILABLE);
        assertThat(r.indicators().get("EMA50").status()).isEqualTo(MetricStatus.AVAILABLE);
        assertThat(r.indicators().get("MACD").status()).isEqualTo(MetricStatus.AVAILABLE);
        // 等差收盤序列的 SMA seed 使 EMA(n) 精確落後 (n-1)/2；MACD=7、signal=7。
        assertThat(r.indicators().get("EMA20").value()).isCloseTo(new BigDecimal("239.5"), org.assertj.core.data.Offset.offset(new BigDecimal("1E-25")));
        assertThat(r.indicators().get("EMA50").value()).isCloseTo(new BigDecimal("224.5"), org.assertj.core.data.Offset.offset(new BigDecimal("1E-25")));
        assertThat(r.indicators().get("MACD").value()).isCloseTo(new BigDecimal("7"), org.assertj.core.data.Offset.offset(new BigDecimal("1E-25")));
        assertThat(r.indicators().get("MACD_SIGNAL").value()).isCloseTo(new BigDecimal("7"), org.assertj.core.data.Offset.offset(new BigDecimal("1E-25")));
        assertThat(r.indicators().get("MACD_HISTOGRAM").value()).isCloseTo(BigDecimal.ZERO, org.assertj.core.data.Offset.offset(new BigDecimal("1E-25")));
    }
    // 例子：150 根收盤都是 100 時，STOCH_K14 為 50。
    @Test void zeroRangeStochasticIsFifty(){assertThat(calculator.calculate(bars(150,i->100)).indicators().get("STOCH_K14").value()).isEqualByComparingTo("50");}
    // 每項指標都有同一批行情的判讀；MACD 只描述目前相對位置，不推測剛發生交叉。
    @Test void interpretationsDescribeObservedValuesAndPreserveUnitsAndDates(){
        MomentumAnalysisDto r=calculator.calculate(bars(260,i->100+i*i));
        assertThat(r.interpretations().keySet()).isEqualTo(r.indicators().keySet());
        assertThat(r.interpretations().get("RSI14").code()).isEqualTo("RSI_HIGH");
        assertThat(r.interpretations().get("SMA200").code()).isEqualTo("MA_PRICE_ABOVE");
        assertThat(r.interpretations().get("MACD").code()).isEqualTo("MACD_ABOVE_SIGNAL");
        assertThat(r.interpretations().get("MACD").params()).containsEntry("fastPeriod",12).containsEntry("slowPeriod",26).containsEntry("signalPeriod",9);
        assertThat(r.interpretations().get("ATR14").code()).isEqualTo("VOLATILITY_MEASURE");
        assertThat(r.interpretations().get("ATR14").params()).containsEntry("unit","PRICE").containsEntry("asOf",LocalDate.of(2025,1,1).plusDays(259).toString());
        assertThat(r.interpretations().get("HV20").params()).containsEntry("unit","FRACTION");
        assertThat(r.interpretations().get("ROC20").params()).containsEntry("unit","PERCENT");
        assertThat(r.interpretations().get("MA20_50_CROSS").code()).isEqualTo("MA_CROSS_NONE");
        assertThat(r.series()).hasSize(260);
        IndicatorSeriesPointDto first = r.series().getFirst();
        assertThat(first.sma20()).isNull();
        assertThat(first.rsi14()).isNull();
        assertThat(first.macd()).isNull();
        IndicatorSeriesPointDto last = r.series().getLast();
        assertThat(last.sessionDate()).isEqualTo(LocalDate.of(2025, 1, 1).plusDays(259));
        assertThat(last.sma200()).isEqualByComparingTo(r.indicators().get("SMA200").value().setScale(4, java.math.RoundingMode.HALF_UP));
        assertThat(last.rsi14()).isEqualByComparingTo(r.indicators().get("RSI14").value().setScale(4, java.math.RoundingMode.HALF_UP));
        assertThat(last.macd()).isEqualByComparingTo(r.indicators().get("MACD").value().setScale(4, java.math.RoundingMode.HALF_UP));
        assertThat(last.macdSignal()).isEqualByComparingTo(r.indicators().get("MACD_SIGNAL").value().setScale(4, java.math.RoundingMode.HALF_UP));
    }
    // 不足 115 根的 RSI 不得附上正常／中性判讀，保留原始不足原因與最少 bar 數。
    @Test void insufficientAndInvalidIndicatorsKeepTheirActualStatusAndReasons(){
        MomentumAnalysisDto shortHistory=calculator.calculate(bars(114,i->100));
        IndicatorInterpretationDto rsi=shortHistory.interpretations().get("RSI14");
        assertThat(rsi.code()).isEqualTo("INDICATOR_UNAVAILABLE");
        assertThat(rsi.status()).isEqualTo(MetricStatus.INSUFFICIENT_DATA);
        assertThat(rsi.reasons()).extracting(AnalysisReasonDto::code).containsExactly("RSI_WARMUP_REQUIRED");
        assertThat(rsi.params()).containsEntry("requiredBars",115).containsEntry("availableBars",114).doesNotContainKey("value");
        MomentumAnalysisDto invalid=calculator.calculate(List.of());
        assertThat(invalid.interpretations().values()).allSatisfy(item->{
            assertThat(item.status()).isEqualTo(MetricStatus.UNVERIFIED);
            assertThat(item.code()).isEqualTo("INDICATOR_UNAVAILABLE");
        });
    }
    // 舊 API fixture／呼叫端仍可用三參數 constructor，不製造不存在的解說。
    @Test void legacyMomentumConstructorKeepsAnEmptyInterpretationMap(){
        MomentumAnalysisDto legacy=new MomentumAnalysisDto(Map.of(),null,List.of());
        assertThat(legacy.interpretations()).isEmpty();
    }
    private List<NormalizedBarDto> bars(int n, java.util.function.IntFunction<Integer> closeFn){List<NormalizedBarDto>x=new ArrayList<>();LocalDate d=LocalDate.of(2025,1,1);for(int i=0;i<n;i++){BigDecimal c=BigDecimal.valueOf(closeFn.apply(i));Instant open=d.plusDays(i).atStartOfDay(ZoneOffset.UTC).toInstant();x.add(new NormalizedBarDto(d.plusDays(i),c,c,c,c,MetricResult.available(BigDecimal.valueOf(100)),open,open.plusSeconds(3600),open.plusSeconds(3700),open.plusSeconds(3700),"Q:"+i,"B"));}return x;}
}
