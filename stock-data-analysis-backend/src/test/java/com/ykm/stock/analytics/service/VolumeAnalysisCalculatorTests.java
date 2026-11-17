package com.ykm.stock.analytics.service;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.*; import com.ykm.stock.analytics.service.analysis.calculator.VolumeAnalysisCalculator; import org.junit.jupiter.api.Test;
import java.math.*; import java.time.*; import java.util.*; import static org.assertj.core.api.Assertions.assertThat;
class VolumeAnalysisCalculatorTests {
 private final VolumeAnalysisCalculator calculator=new VolumeAnalysisCalculator();
 // 例子：第 40 根量 200、前 20 根均量較低時，RVOL20 為 2，且不含當根本身。
 @Test void rvolUsesPriorTwentyAndExcludesCurrentBar(){List<NormalizedBarDto>b=bars(40);b.set(39,bar(39,new BigDecimal("200"),BigDecimal.valueOf(139),BigDecimal.valueOf(138),BigDecimal.valueOf(140)));var r=calculator.calculate(b,null);assertThat(r.rvol20().value()).isEqualByComparingTo("2");}
 // 例子：252 根量加總 25200 全部分配進剖面，並標成日 OHLCV 近似。
 @Test void profileDistributesAllVolumeAndKeepsApproximationLabel(){List<NormalizedBarDto>b=bars(252);var r=calculator.calculate(b,null);assertThat(r.profile252().status()).isEqualTo(MetricStatus.AVAILABLE);BigDecimal sum=r.profile252().value().bins().stream().map(VolumeBinDto::volume).reduce(BigDecimal.ZERO,BigDecimal::add);assertThat(sum.subtract(new BigDecimal("25200")).abs()).isLessThan(new BigDecimal("0.00000000000000000001"));assertThat(r.profile252().value().approximationLabel()).isEqualTo("DAILY_OHLCV_APPROXIMATION");}
 // 例子：第 31 根缺成交量時失敗關閉，不得用 0 代替。
 @Test void missingVolumeFailsClosedRatherThanUsingZero(){List<NormalizedBarDto>b=bars(40);NormalizedBarDto old=b.get(30);b.set(30,new NormalizedBarDto(old.sessionDate(),old.open(),old.high(),old.low(),old.close(),MetricResult.unavailable(MetricStatus.UNVERIFIED,AnalysisReasonDto.of("NO_VOLUME","VOLUME","missing")),old.sessionOpenAt(),old.sessionCloseAt(),old.availableAt(),old.observedAt(),old.versionRef(),old.basisId()));assertThat(calculator.calculate(b,null).rvol20().status()).isEqualTo(MetricStatus.UNVERIFIED);}
 // 例子：放量突破高點時訊號為 1，屬多頭。
 @Test void highBreakoutWithVolumeIsBullish() {
  assertThat(breakoutSignal("STRUCTURE_HIGH_BREACH")).isEqualByComparingTo("1");
 }
 // 例子：放量跌破低點時訊號為 -1，不是買入證據。
 @Test void lowBreakoutWithVolumeIsBearishNotBuyEvidence() {
  assertThat(breakoutSignal("STRUCTURE_LOW_BREACH")).isEqualByComparingTo("-1");
 }
 // 例子：同日放量同時突破高低點時，訊號中性而非偏多。
 @Test void highAndLowBreakoutConflictProducesNeutralSignal() {
  List<NormalizedBarDto> b=bars(40);
  b.set(39,bar(39,new BigDecimal("200"),BigDecimal.valueOf(139),BigDecimal.valueOf(138),BigDecimal.valueOf(140)));
  LocalDate d=b.get(39).sessionDate();
  TimeframeStructureDto tf=new TimeframeStructureDto(AnalysisTimeframe.DAILY,List.of(),null,List.of(),List.of(),
   null,null,null,null,List.of(new StructureEventDto("STRUCTURE_HIGH_BREACH",PivotKind.HIGH,d,null,null,null,null,null),
    new StructureEventDto("STRUCTURE_LOW_BREACH",PivotKind.LOW,d,null,null,null,null,null)),List.of());
  assertThat(calculator.calculate(b,new PriceStructureDto("FIGI","hash",d,List.of(tf),List.of(),List.of()))
   .primarySignal().value()).isEqualByComparingTo("0");
 }
 private BigDecimal breakoutSignal(String eventCode) {
  List<NormalizedBarDto> b=bars(40);
  b.set(39,bar(39,new BigDecimal("200"),BigDecimal.valueOf(139),BigDecimal.valueOf(138),BigDecimal.valueOf(140)));
  LocalDate d=b.get(39).sessionDate();
  TimeframeStructureDto tf=new TimeframeStructureDto(AnalysisTimeframe.DAILY,List.of(),null,List.of(),List.of(),
   null,null,null,null,List.of(new StructureEventDto(eventCode,PivotKind.HIGH,d,null,null,null,null,null)),List.of());
  return calculator.calculate(b,new PriceStructureDto("FIGI","hash",d,List.of(tf),List.of(),List.of())).primarySignal().value();
 }
 private List<NormalizedBarDto> bars(int n){List<NormalizedBarDto>x=new ArrayList<>();for(int i=0;i<n;i++){BigDecimal c=BigDecimal.valueOf(100+i%40);x.add(bar(i,new BigDecimal("100"),c,c.subtract(BigDecimal.ONE),c.add(BigDecimal.ONE)));}return x;}
 private NormalizedBarDto bar(int i,BigDecimal volume,BigDecimal close,BigDecimal low,BigDecimal high){LocalDate d=LocalDate.of(2025,1,1).plusDays(i);Instant t=d.atStartOfDay(ZoneOffset.UTC).toInstant();return new NormalizedBarDto(d,close,high,low,close,MetricResult.available(volume),t,t.plusSeconds(1),t.plusSeconds(2),t.plusSeconds(2),"Q:"+i,"B");}
}
