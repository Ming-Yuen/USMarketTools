package com.ykm.stock.analytics.service.analysis.calculator;

import com.ykm.stock.analytics.model.entity.StockQuote;
import java.math.BigDecimal;
import java.util.Objects;

// 用途：負責「行情正常化」相關的業務協調與處理，集中管理此領域的服務邏輯。
public final class QuoteNormalizer {
    private static final BigDecimal MIN_PERSISTED_PRICE = new BigDecimal("0.00000001");
    private static final BigDecimal MAX_PERSISTED_PRICE = new BigDecimal("99999999999.99999999");
    private static final BigDecimal MAX_PERSISTED_VOLUME = new BigDecimal("99999999999999999999.99999999");

    // 用途：處理行情正常化，作為「行情正常化」流程中的處理步驟。
    private QuoteNormalizer() {}

    // 用途：驗證 OHLCV 的基本金融語義，包括正數價格、高低價關係與非負成交量。
    public static boolean valid(StockQuote q) {
        if (q == null) return false;
        BigDecimal o=q.getOpenPrice(), h=q.getHighPrice(), l=q.getLowPrice(), c=q.getClosePrice();
        if (o==null||h==null||l==null||c==null||o.signum()<=0||h.signum()<=0||l.signum()<=0||c.signum()<=0) return false;
        if (h.compareTo(o.max(c))<0 || l.compareTo(o.min(c))>0 || h.compareTo(l)<0) return false;
        return q.getVolume()==null || q.getVolume().signum()>=0;
    }

    // 價格 NUMERIC(19,8)、成交量 NUMERIC(28,8) 必須可精確保存，不能由 DB 靜默取整或溢位。
    public static boolean validForStorage(StockQuote q) {
        if (!valid(q)) return false;
        return persistable(q.getOpenPrice()) && persistable(q.getHighPrice())
                && persistable(q.getLowPrice()) && persistable(q.getClosePrice())
                && (q.getVolume() == null || (q.getVolume().compareTo(MAX_PERSISTED_VOLUME) <= 0 && q.getVolume().stripTrailingZeros().scale() <= 8));
    }

    /** 比較同一來源的原始 OHLCV；忽略 BigDecimal 的顯示 scale。 */
    public static boolean sameRawQuote(StockQuote stored, StockQuote incoming) {
        return Objects.equals(stored.getDataSource(), incoming.getDataSource())
                && sameNumber(stored.getOpenPrice(), incoming.getOpenPrice()) && sameNumber(stored.getHighPrice(), incoming.getHighPrice())
                && sameNumber(stored.getLowPrice(), incoming.getLowPrice()) && sameNumber(stored.getClosePrice(), incoming.getClosePrice())
                && sameNumber(stored.getVolume(), incoming.getVolume());
    }

    private static boolean sameNumber(BigDecimal first, BigDecimal second) {
        return first != null && second != null && first.compareTo(second) == 0;
    }

    private static boolean persistable(BigDecimal value) {
        return value.compareTo(MIN_PERSISTED_PRICE) >= 0
                && value.compareTo(MAX_PERSISTED_PRICE) <= 0
                && value.stripTrailingZeros().scale() <= 8;
    }
}