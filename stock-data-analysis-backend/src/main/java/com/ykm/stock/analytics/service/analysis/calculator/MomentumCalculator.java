package com.ykm.stock.analytics.service.analysis.calculator;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.*;
import java.util.*;

@Component
@RequiredArgsConstructor
/** 負責由歷史行情計算 RSI、MACD、ROC、隨機指標、波動率與布林帶等動能證據。 */
public class MomentumCalculator {
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final int RSI_PUBLIC_BARS = 115;
    private static final int MACD_PUBLIC_BARS = 135;
    private final WilderAtrCalculator atrCalculator;
    private final WilderRsiCalculator rsiCalculator = new WilderRsiCalculator();

    // 用途：根據輸入資料計算相關資料，產生可供分析或交易決策使用的標準結果。
    public MomentumAnalysisDto calculate(List<NormalizedBarDto> bars) {
        return calculate(bars, true);
    }

    /** 批次雷達與排行報表無需產出全量圖表序列點，可透過 includeIndicatorSeries=false 略過逐日布林與指標序列建構。 */
    public MomentumAnalysisDto calculate(List<NormalizedBarDto> bars, boolean includeIndicatorSeries) {
        Map<String, MetricResult<BigDecimal>> out = new LinkedHashMap<>();
        if (!validBars(bars)) {
            MetricResult<BigDecimal> bad = unavailable(MetricStatus.UNVERIFIED, "MOMENTUM_BAR_SEQUENCE_INVALID", "K線必須按 session 升序且 OHLC 完整。 ");
            for (String code : metricCodes()) out.put(code, bad);
            return new MomentumAnalysisDto(out, bad, bad.reasons(), interpretations(out, List.of()));
        }
        List<BigDecimal> closes = bars.stream().map(NormalizedBarDto::close).toList();
        List<BigDecimal> rsiSeries = rsiCalculator.series(closes, 14);
        MetricResult<BigDecimal> rsi14 = bars.size() >= RSI_PUBLIC_BARS && !rsiSeries.isEmpty()
                ? MetricResult.available(rsiSeries.get(rsiSeries.size() - 1)) : insufficient("RSI_WARMUP_REQUIRED");
        out.put("RSI14", rsi14);
        MetricResult<BigDecimal> signal = rsi14.status() == MetricStatus.AVAILABLE ? MetricResult.available(momentumSignal(rsiSeries)) : insufficient("RSI_WARMUP_REQUIRED");

        for (int n : List.of(20,50,100,150,200)) out.put("SMA" + n, sma(closes, n));
        out.put("EMA20", emaPublished(closes,20));
        out.put("EMA21", emaPublished(closes,21));
        out.put("EMA50", emaPublished(closes,50));
        Macd macd = macd(closes);
        out.put("MACD", macd.macd()); out.put("MACD_SIGNAL", macd.signal()); out.put("MACD_HISTOGRAM", macd.histogram());
        out.put("ROC20", roc(closes,20));
        out.put("MOMENTUM20", difference(closes,20));
        Stochastic stochastic = stochastic(bars);
        out.put("STOCH_K14", stochastic.k()); out.put("STOCH_D3", stochastic.d());
        out.put("HV20", historicalVolatility(closes));
        Bollinger bb = bollinger(closes);
        out.put("BOLLINGER_LOWER20", bb.lower()); out.put("BOLLINGER_MID20", bb.mid()); out.put("BOLLINGER_UPPER20", bb.upper());
        out.put("BOLLINGER_BANDWIDTH20", bb.bandwidth()); out.put("BOLLINGER_POSITION20", bb.position());

        MetricResult<BigDecimal> sma20 = out.get("SMA20"), sma50 = out.get("SMA50"), sma100 = out.get("SMA100");
        if (sma20.status()==MetricStatus.AVAILABLE) out.put("DISTANCE_TO_MA20", percentRatio(closes.get(closes.size()-1), sma20.value()));
        else out.put("DISTANCE_TO_MA20", insufficient("MA20_REQUIRED"));
        out.put("SLOPE5_MA20", slope(closes,20));
        out.put("SLOPE5_MA50", slope(closes,50));
        out.put("SLOPE5_MA200", slope(closes,200));
        out.put("MA20_50_CROSS", crossover(closes,20,50));
        if (sma20.status()==MetricStatus.AVAILABLE && sma50.status()==MetricStatus.AVAILABLE && sma100.status()==MetricStatus.AVAILABLE) {
            BigDecimal max=sma20.value().max(sma50.value()).max(sma100.value());
            BigDecimal min=sma20.value().min(sma50.value()).min(sma100.value());
            BigDecimal close=closes.get(closes.size()-1);
            out.put("MA_COMPRESSION", close.signum()==0 ? notApplicable("CLOSE_ZERO") : MetricResult.available(max.subtract(min,MC).divide(close,MC)));
        } else out.put("MA_COMPRESSION", insufficient("MA_COMPRESSION_INPUT_REQUIRED"));
        List<AtrPointDto> atr = atrCalculator.calculate(bars,14);
        if (!atr.isEmpty() && sma20.status()==MetricStatus.AVAILABLE && atr.get(atr.size()-1).atr()!=null && atr.get(atr.size()-1).atr().signum()>0) {
            out.put("MA_EXTENSION_ATR", MetricResult.available(closes.get(closes.size()-1).subtract(sma20.value(),MC).divide(atr.get(atr.size()-1).atr(),MC)));
            out.put("ATR14", MetricResult.available(atr.get(atr.size()-1).atr()));
        } else {
            out.put("MA_EXTENSION_ATR", insufficient("ATR_MA_INPUT_REQUIRED")); out.put("ATR14", insufficient("ATR14_REQUIRED"));
        }
        List<IndicatorSeriesPointDto> series = includeIndicatorSeries
                ? buildIndicatorSeries(bars, closes, rsiSeries)
                : List.of();
        return new MomentumAnalysisDto(out, signal, List.of(), interpretations(out, bars), series);
    }

    private List<IndicatorSeriesPointDto> buildIndicatorSeries(
            List<NormalizedBarDto> bars, List<BigDecimal> closes, List<BigDecimal> rsiSeries) {
        int size = bars.size();
        List<BigDecimal> e12 = closes.size() >= 12 ? emaSeries(closes, 12) : List.of();
        List<BigDecimal> e26 = closes.size() >= 26 ? emaSeries(closes, 26) : List.of();
        List<BigDecimal> macdValues = new ArrayList<>();
        for (int i = 25; i < size; i++) {
            macdValues.add(e12.get(i - 11).subtract(e26.get(i - 25), MC));
        }
        List<BigDecimal> macdSig = macdValues.size() >= 9 ? emaSeries(macdValues, 9) : List.of();

        BigDecimal sum20 = BigDecimal.ZERO;
        BigDecimal sum50 = BigDecimal.ZERO;
        BigDecimal sum200 = BigDecimal.ZERO;
        List<IndicatorSeriesPointDto> points = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            BigDecimal close = closes.get(i);
            sum20 = sum20.add(close, MC);
            sum50 = sum50.add(close, MC);
            sum200 = sum200.add(close, MC);
            if (i >= 20) sum20 = sum20.subtract(closes.get(i - 20), MC);
            if (i >= 50) sum50 = sum50.subtract(closes.get(i - 50), MC);
            if (i >= 200) sum200 = sum200.subtract(closes.get(i - 200), MC);

            BigDecimal rawSma20 = i >= 19 ? sum20.divide(BigDecimal.valueOf(20), MC) : null;
            BigDecimal sma20Val = scale4(rawSma20);
            BigDecimal sma50Val = i >= 49 ? scale4(sum50.divide(BigDecimal.valueOf(50), MC)) : null;
            BigDecimal sma200Val = i >= 199 ? scale4(sum200.divide(BigDecimal.valueOf(200), MC)) : null;

            BigDecimal bbUpper = null;
            BigDecimal bbLower = null;
            if (rawSma20 != null) {
                BigDecimal sqSum = BigDecimal.ZERO;
                for (int j = i - 19; j <= i; j++) {
                    BigDecimal diff = closes.get(j).subtract(rawSma20, MC);
                    sqSum = sqSum.add(diff.multiply(diff, MC), MC);
                }
                BigDecimal twoStd = BigDecimal.valueOf(Math.sqrt(sqSum.divide(BigDecimal.valueOf(20), MC).doubleValue()))
                        .multiply(BigDecimal.valueOf(2), MC);
                bbUpper = scale4(rawSma20.add(twoStd, MC));
                bbLower = scale4(rawSma20.subtract(twoStd, MC));
            }

            BigDecimal rsiVal = i >= RSI_PUBLIC_BARS - 1 && (i - 14) < rsiSeries.size()
                    ? scale4(rsiSeries.get(i - 14)) : null;
            BigDecimal macdVal = null;
            BigDecimal sigVal = null;
            BigDecimal histVal = null;
            if (i >= MACD_PUBLIC_BARS - 1 && (i - 25) < macdValues.size() && (i - 33) < macdSig.size()) {
                BigDecimal m = macdValues.get(i - 25);
                BigDecimal s = macdSig.get(i - 33);
                macdVal = scale4(m);
                sigVal = scale4(s);
                histVal = scale4(m.subtract(s, MC));
            }

            points.add(new IndicatorSeriesPointDto(
                    bars.get(i).sessionDate(), sma20Val, sma50Val, sma200Val,
                    bbUpper, bbLower, rsiVal, macdVal, sigVal, histVal));
        }
        return List.copyOf(points);
    }

    private BigDecimal scale4(BigDecimal value) {
        return value == null ? null : value.setScale(4, RoundingMode.HALF_UP);
    }

    /** 判讀只使用上面已發布的數值；不以單期 MACD 相對位置宣稱交叉，也不把波動率當作方向。 */
    private Map<String, IndicatorInterpretationDto> interpretations(Map<String, MetricResult<BigDecimal>> values,
                                                                  List<NormalizedBarDto> bars) {
        Map<String, IndicatorInterpretationDto> result = new LinkedHashMap<>();
        NormalizedBarDto last = bars.isEmpty() ? null : bars.get(bars.size() - 1);
        for (Map.Entry<String, MetricResult<BigDecimal>> entry : values.entrySet()) {
            String metric = entry.getKey();
            MetricResult<BigDecimal> value = entry.getValue();
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("metric", metric);
            params.put("timeframe", "DAILY");
            params.put("unit", unit(metric));
            params.put("availableBars", bars.size());
            params.put("requiredBars", requiredBars(metric));
            addPeriods(metric, params);
            if (last != null) {
                params.put("asOf", last.sessionDate().toString());
                params.put("close", last.close());
            }
            if (metric.startsWith("MACD")) {
                if (values.get("MACD").value() != null) params.put("macd", values.get("MACD").value());
                if (values.get("MACD_SIGNAL").value() != null) params.put("signal", values.get("MACD_SIGNAL").value());
            }
            if (value.status() != MetricStatus.AVAILABLE || value.value() == null) {
                result.put(metric, new IndicatorInterpretationDto("INDICATOR_UNAVAILABLE", params, value.status(), value.reasons()));
                continue;
            }
            params.put("value", value.value());
            String code = interpretationCode(metric, value.value(), values, last.close());
            if (metric.equals("RSI14")) {
                params.put("period", 14);
                params.put("lowThreshold", 30);
                params.put("highThreshold", 70);
            }
            result.put(metric, new IndicatorInterpretationDto(code, params, value.status(), value.reasons()));
        }
        return result;
    }

    private String interpretationCode(String metric, BigDecimal value, Map<String, MetricResult<BigDecimal>> values,
                                      BigDecimal close) {
        if (metric.equals("RSI14")) {
            if (value.compareTo(BigDecimal.valueOf(70)) >= 0) return "RSI_HIGH";
            if (value.compareTo(BigDecimal.valueOf(30)) <= 0) return "RSI_LOW";
            return "RSI_MID";
        }
        if (metric.startsWith("SMA") || metric.startsWith("EMA")) {
            int comparison = close.compareTo(value);
            if (comparison > 0) return "MA_PRICE_ABOVE";
            if (comparison < 0) return "MA_PRICE_BELOW";
            return "MA_PRICE_AT";
        }
        if (metric.startsWith("MACD")) {
            BigDecimal difference = values.get("MACD_HISTOGRAM").value();
            if (difference.signum() > 0) return "MACD_ABOVE_SIGNAL";
            if (difference.signum() < 0) return "MACD_BELOW_SIGNAL";
            return "MACD_AT_SIGNAL";
        }
        if (metric.equals("MA20_50_CROSS")) {
            if (value.signum() > 0) return "MA_CROSS_UP";
            if (value.signum() < 0) return "MA_CROSS_DOWN";
            return "MA_CROSS_NONE";
        }
        if (Set.of("HV20", "ATR14").contains(metric)) return "VOLATILITY_MEASURE";
        if (Set.of("STOCH_K14", "STOCH_D3", "BOLLINGER_POSITION20").contains(metric)) return "RANGE_POSITION";
        if (Set.of("BOLLINGER_LOWER20", "BOLLINGER_MID20", "BOLLINGER_UPPER20").contains(metric)) {
            if (close.compareTo(values.get("BOLLINGER_UPPER20").value()) > 0) return "BOLLINGER_ABOVE";
            if (close.compareTo(values.get("BOLLINGER_LOWER20").value()) < 0) return "BOLLINGER_BELOW";
            return "BOLLINGER_WITHIN";
        }
        if (Set.of("BOLLINGER_BANDWIDTH20", "MA_COMPRESSION").contains(metric)) return "MEASURE_ONLY";
        if (value.signum() > 0) return "VALUE_POSITIVE";
        if (value.signum() < 0) return "VALUE_NEGATIVE";
        return "VALUE_ZERO";
    }

    private void addPeriods(String metric, Map<String, Object> params) {
        if (metric.startsWith("SMA") || metric.startsWith("EMA")) params.put("period", Integer.parseInt(metric.substring(3)));
        else if (metric.startsWith("MACD")) {
            params.put("fastPeriod", 12);
            params.put("slowPeriod", 26);
            params.put("signalPeriod", 9);
        } else if (metric.equals("MA20_50_CROSS")) {
            params.put("fastPeriod", 20);
            params.put("slowPeriod", 50);
        } else if (metric.startsWith("SLOPE5_MA")) {
            params.put("period", Integer.parseInt(metric.substring(9)));
            params.put("slopeSessions", 5);
        } else if (metric.equals("MA_COMPRESSION")) params.put("periods", List.of(20, 50, 100));
        else if (metric.equals("MA_EXTENSION_ATR")) {
            params.put("period", 14);
            params.put("maPeriod", 20);
        } else if (metric.equals("STOCH_D3")) {
            params.put("period", 3);
            params.put("rangePeriod", 14);
        } else params.put("period", Set.of("RSI14", "ATR14", "STOCH_K14").contains(metric) ? 14 : 20);
    }

    private String unit(String metric) {
        if (metric.startsWith("SMA") || metric.startsWith("EMA") || metric.startsWith("MACD")
                || Set.of("ATR14", "BOLLINGER_LOWER20", "BOLLINGER_MID20", "BOLLINGER_UPPER20").contains(metric)) return "PRICE";
        if (Set.of("ROC20", "DISTANCE_TO_MA20").contains(metric)) return "PERCENT";
        if (metric.startsWith("SLOPE")) return "PERCENT_PER_SESSION";
        if (Set.of("HV20", "BOLLINGER_BANDWIDTH20", "BOLLINGER_POSITION20", "MA_COMPRESSION").contains(metric)) return "FRACTION";
        if (metric.equals("MA_EXTENSION_ATR")) return "ATR_MULTIPLE";
        if (metric.equals("MOMENTUM20")) return "PRICE_CHANGE";
        if (metric.equals("MA20_50_CROSS")) return "CROSS";
        return "INDEX";
    }

    private int requiredBars(String metric) {
        if (metric.equals("RSI14")) return RSI_PUBLIC_BARS;
        if (metric.startsWith("MACD")) return MACD_PUBLIC_BARS;
        if (metric.startsWith("SMA")) return Integer.parseInt(metric.substring(3));
        if (metric.startsWith("EMA")) return Integer.parseInt(metric.substring(3)) + 100;
        if (metric.startsWith("SLOPE5_MA")) return Integer.parseInt(metric.substring(9)) + 5;
        if (Set.of("ROC20", "MOMENTUM20", "HV20").contains(metric)) return 21;
        if (metric.startsWith("STOCH")) return 16;
        if (metric.equals("MA_COMPRESSION")) return 100;
        if (metric.equals("MA20_50_CROSS")) return 51;
        return 20;
    }

    // 用途：判斷valid 行情 bar是否符合指定條件，供上層流程決定是否繼續使用該資料。
    private boolean validBars(List<NormalizedBarDto> bars) {
        if (bars == null || bars.isEmpty()) return false;
        for (int i=0;i<bars.size();i++) {
            NormalizedBarDto b=bars.get(i);
            if (b==null||b.sessionDate()==null||b.open()==null||b.high()==null||b.low()==null||b.close()==null||b.close().signum()<=0) return false;
            if (i>0 && !bars.get(i-1).sessionDate().isBefore(b.sessionDate())) return false;
        }
        return true;
    }

    // 用途：取得或整理指標 codes的內部表示，供本服務其他分析步驟重用。
    private Set<String> metricCodes() { return Set.of("RSI14","SMA20","SMA50","SMA100","SMA150","SMA200","EMA20","EMA21","EMA50","MACD","MACD_SIGNAL","MACD_HISTOGRAM","ROC20","MOMENTUM20","STOCH_K14","STOCH_D3","HV20","BOLLINGER_LOWER20","BOLLINGER_MID20","BOLLINGER_UPPER20","BOLLINGER_BANDWIDTH20","BOLLINGER_POSITION20","DISTANCE_TO_MA20","SLOPE5_MA20","SLOPE5_MA50","SLOPE5_MA200","MA20_50_CROSS","MA_COMPRESSION","MA_EXTENSION_ATR","ATR14"); }

    // 用途：處理動能 signal的內部流程，統一輸入、輸出與資料品質規則供後續步驟使用。
    private BigDecimal momentumSignal(List<BigDecimal> rsi) {
        if (rsi.size()<2) return BigDecimal.ZERO;
        BigDecimal now=rsi.get(rsi.size()-1), prev=rsi.get(rsi.size()-2);
        if (prev.compareTo(BigDecimal.valueOf(30))<=0 && now.compareTo(BigDecimal.valueOf(30))>0) return BigDecimal.ONE;
        if (prev.compareTo(BigDecimal.valueOf(70))>=0 && now.compareTo(BigDecimal.valueOf(70))<0) return BigDecimal.ONE.negate();
        if (rsi.size()>=3) {
            BigDecimal a=rsi.get(rsi.size()-3), b=rsi.get(rsi.size()-2);
            if (now.compareTo(BigDecimal.valueOf(30))<0 && a.compareTo(b)<0 && b.compareTo(now)<0) return new BigDecimal("0.5");
            if (now.compareTo(BigDecimal.valueOf(70))>0 && a.compareTo(b)>0 && b.compareTo(now)>0) return new BigDecimal("-0.5");
        }
        return BigDecimal.ZERO;
    }

    // 用途：處理SMA的內部流程，統一輸入、輸出與資料品質規則供後續步驟使用。
    private MetricResult<BigDecimal> sma(List<BigDecimal> closes,int n) {
        if (closes.size()<n) return insufficient("MA"+n+"_WARMUP_REQUIRED");
        BigDecimal sum=BigDecimal.ZERO; for(int i=closes.size()-n;i<closes.size();i++)sum=sum.add(closes.get(i),MC);
        return MetricResult.available(sum.divide(BigDecimal.valueOf(n),MC));
    }
    // 用途：處理SMA 數值的內部流程，統一輸入、輸出與資料品質規則供後續步驟使用。
    private BigDecimal smaValue(List<BigDecimal> x,int endInclusive,int n) {
        if(endInclusive+1<n)return null; BigDecimal sum=BigDecimal.ZERO; for(int i=endInclusive-n+1;i<=endInclusive;i++)sum=sum.add(x.get(i),MC); return sum.divide(BigDecimal.valueOf(n),MC);
    }
    // 用途：處理EMA series的內部流程，統一輸入、輸出與資料品質規則供後續步驟使用。
    private List<BigDecimal> emaSeries(List<BigDecimal> values,int n) {
        if(values.size()<n)return List.of(); BigDecimal seed=BigDecimal.ZERO; for(int i=0;i<n;i++)seed=seed.add(values.get(i),MC); seed=seed.divide(BigDecimal.valueOf(n),MC);
        BigDecimal a=BigDecimal.valueOf(2).divide(BigDecimal.valueOf(n+1L),MC), one=BigDecimal.ONE.subtract(a,MC), e=seed; List<BigDecimal> out=new ArrayList<>(); out.add(e);
        for(int i=n;i<values.size();i++){e=values.get(i).multiply(a,MC).add(e.multiply(one,MC),MC);out.add(e);} return List.copyOf(out);
    }
    // 用途：處理EMA published的內部流程，統一輸入、輸出與資料品質規則供後續步驟使用。
    private MetricResult<BigDecimal> emaPublished(List<BigDecimal> x,int n) { if(x.size()<n+100)return insufficient("EMA"+n+"_WARMUP_REQUIRED"); List<BigDecimal> e=emaSeries(x,n); return MetricResult.available(e.get(e.size()-1)); }
    // 用途：處理MACD的內部流程，統一輸入、輸出與資料品質規則供後續步驟使用。
    private Macd macd(List<BigDecimal> closes) {
        if(closes.size()<MACD_PUBLIC_BARS)return new Macd(insufficient("MACD_WARMUP_REQUIRED"),insufficient("MACD_WARMUP_REQUIRED"),insufficient("MACD_WARMUP_REQUIRED"));
        List<BigDecimal> e12=emaSeries(closes,12),e26=emaSeries(closes,26), values=new ArrayList<>();
        for(int i=25;i<closes.size();i++) values.add(e12.get(i-11).subtract(e26.get(i-25),MC));
        List<BigDecimal> sig=emaSeries(values,9); BigDecimal m=values.get(values.size()-1), s=sig.get(sig.size()-1);
        return new Macd(MetricResult.available(m),MetricResult.available(s),MetricResult.available(m.subtract(s,MC)));
    }
    // 用途：處理ROC的內部流程，統一輸入、輸出與資料品質規則供後續步驟使用。
    private MetricResult<BigDecimal> roc(List<BigDecimal>x,int n){if(x.size()<=n)return insufficient("ROC"+n+"_WARMUP_REQUIRED");BigDecimal old=x.get(x.size()-1-n);return old.signum()==0?notApplicable("ROC_BASE_ZERO"):MetricResult.available(x.get(x.size()-1).divide(old,MC).subtract(BigDecimal.ONE,MC).multiply(BigDecimal.valueOf(100),MC));}
    // 用途：比較或計算difference之間的關係，供評分、風險或排序規則使用。
    private MetricResult<BigDecimal> difference(List<BigDecimal>x,int n){return x.size()<=n?insufficient("MOMENTUM"+n+"_WARMUP_REQUIRED"):MetricResult.available(x.get(x.size()-1).subtract(x.get(x.size()-1-n),MC));}
    // 用途：處理隨機指標的內部流程，統一輸入、輸出與資料品質規則供後續步驟使用。
    private Stochastic stochastic(List<NormalizedBarDto>b){
        if(b.size()<16)return new Stochastic(insufficient("STOCHASTIC_WARMUP_REQUIRED"),insufficient("STOCHASTIC_WARMUP_REQUIRED"));
        List<BigDecimal> ks=new ArrayList<>(); for(int end=b.size()-3;end<b.size();end++){BigDecimal lo=null,hi=null;for(int i=end-13;i<=end;i++){lo=lo==null?b.get(i).low():lo.min(b.get(i).low());hi=hi==null?b.get(i).high():hi.max(b.get(i).high());}BigDecimal range=hi.subtract(lo,MC);ks.add(range.signum()==0?BigDecimal.valueOf(50):b.get(end).close().subtract(lo,MC).divide(range,MC).multiply(BigDecimal.valueOf(100),MC));}
        BigDecimal d=ks.stream().reduce(BigDecimal.ZERO,(a,v)->a.add(v,MC)).divide(BigDecimal.valueOf(3),MC);return new Stochastic(MetricResult.available(ks.get(2)),MetricResult.available(d));
    }
    // 用途：處理歷史 volatility的內部流程，統一輸入、輸出與資料品質規則供後續步驟使用。
    private MetricResult<BigDecimal> historicalVolatility(List<BigDecimal>x){if(x.size()<21)return insufficient("HV20_WARMUP_REQUIRED");List<Double> r=new ArrayList<>();for(int i=x.size()-20;i<x.size();i++)r.add(Math.log(x.get(i).doubleValue()/x.get(i-1).doubleValue()));double mean=r.stream().mapToDouble(v->v).average().orElse(0);double ss=0;for(double v:r)ss+=(v-mean)*(v-mean);double sd=Math.sqrt(ss/(r.size()-1));return MetricResult.available(BigDecimal.valueOf(sd*Math.sqrt(252)));}
    // 用途：處理布林帶的內部流程，統一輸入、輸出與資料品質規則供後續步驟使用。
    private Bollinger bollinger(List<BigDecimal>x){if(x.size()<20){MetricResult<BigDecimal> m=insufficient("BOLLINGER20_WARMUP_REQUIRED");return new Bollinger(m,m,m,m,m);}BigDecimal mean=sma(x,20).value();BigDecimal sum=BigDecimal.ZERO;for(int i=x.size()-20;i<x.size();i++){BigDecimal d=x.get(i).subtract(mean,MC);sum=sum.add(d.multiply(d,MC),MC);}BigDecimal variance=sum.divide(BigDecimal.valueOf(20),MC);BigDecimal std=BigDecimal.valueOf(Math.sqrt(variance.doubleValue()));BigDecimal two=std.multiply(BigDecimal.valueOf(2),MC),lo=mean.subtract(two,MC),hi=mean.add(two,MC),close=x.get(x.size()-1);MetricResult<BigDecimal> pos=hi.compareTo(lo)==0?notApplicable("BOLLINGER_RANGE_ZERO"):MetricResult.available(close.subtract(lo,MC).divide(hi.subtract(lo,MC),MC));MetricResult<BigDecimal> bw=mean.signum()==0?notApplicable("BOLLINGER_MA_ZERO"):MetricResult.available(hi.subtract(lo,MC).divide(mean,MC));return new Bollinger(MetricResult.available(lo),MetricResult.available(mean),MetricResult.available(hi),bw,pos);}
    // 用途：處理percent ratio的內部流程，統一輸入、輸出與資料品質規則供後續步驟使用。
    private MetricResult<BigDecimal> percentRatio(BigDecimal a,BigDecimal b){return b==null||b.signum()==0?notApplicable("DIVISOR_ZERO"):MetricResult.available(a.divide(b,MC).subtract(BigDecimal.ONE,MC).multiply(BigDecimal.valueOf(100),MC));}
    // 用途：處理slope的內部流程，統一輸入、輸出與資料品質規則供後續步驟使用。
    private MetricResult<BigDecimal> slope(List<BigDecimal>x,int n){if(x.size()<n+5)return insufficient("MA"+n+"_SLOPE_WARMUP_REQUIRED");BigDecimal now=smaValue(x,x.size()-1,n),old=smaValue(x,x.size()-6,n);return old==null||old.signum()==0?notApplicable("MA_SLOPE_BASE_ZERO"):MetricResult.available(now.divide(old,MC).subtract(BigDecimal.ONE,MC).multiply(BigDecimal.valueOf(100),MC).divide(BigDecimal.valueOf(5),MC));}
    // 用途：處理crossover的內部流程，統一輸入、輸出與資料品質規則供後續步驟使用。
    private MetricResult<BigDecimal> crossover(List<BigDecimal>x,int fast,int slow){if(x.size()<slow+1)return insufficient("MA_CROSS_WARMUP_REQUIRED");BigDecimal fn=smaValue(x,x.size()-1,fast),sn=smaValue(x,x.size()-1,slow),fp=smaValue(x,x.size()-2,fast),sp=smaValue(x,x.size()-2,slow);if(fp.compareTo(sp)<=0&&fn.compareTo(sn)>0)return MetricResult.available(BigDecimal.ONE);if(fp.compareTo(sp)>=0&&fn.compareTo(sn)<0)return MetricResult.available(BigDecimal.ONE.negate());return MetricResult.available(BigDecimal.ZERO);}
    // 用途：建立相關資料不可用時的標準回傳結果，讓上層流程保留明確資料品質狀態。
    private MetricResult<BigDecimal> insufficient(String code){return unavailable(MetricStatus.INSUFFICIENT_DATA,code,"指標窗口不足。 ");}
    // 用途：處理not applicable的內部流程，統一輸入、輸出與資料品質規則供後續步驟使用。
    private MetricResult<BigDecimal> notApplicable(String code){return unavailable(MetricStatus.NOT_APPLICABLE,code,"指標分母或條件不適用。 ");}
    // 用途：建立相關資料不可用時的標準回傳結果，讓上層流程保留明確資料品質狀態。
    private MetricResult<BigDecimal> unavailable(MetricStatus s,String code,String message){return MetricResult.unavailable(s,AnalysisReasonDto.of(code,"MOMENTUM",message));}
    private record Macd(MetricResult<BigDecimal> macd,MetricResult<BigDecimal> signal,MetricResult<BigDecimal> histogram){}
    private record Stochastic(MetricResult<BigDecimal> k,MetricResult<BigDecimal> d){}
    private record Bollinger(MetricResult<BigDecimal> lower,MetricResult<BigDecimal> mid,MetricResult<BigDecimal> upper,MetricResult<BigDecimal> bandwidth,MetricResult<BigDecimal> position){}
}
