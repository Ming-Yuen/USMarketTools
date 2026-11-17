package com.ykm.stock.analytics.model.projection;

import java.math.BigDecimal;

/** DB 一次分組返回同日產業分位數，避免逐股查詢同業。 */
public interface IndustryMarketDistribution {
    String getCode();
    String getMetric();
    BigDecimal getQ25();
    BigDecimal getMedian();
    BigDecimal getQ75();
    int getCompanyCount();
    int getSampleCount();
}
