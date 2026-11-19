package com.ykm.stock.analytics.model.dto.stockInsight.analysis;

/** 支撐破位真假診斷分類：區分洗盤假跌破、彈簧收回、待觀察破位與高機率真破位。 */
public enum BreakoutDiagnosis {
    NONE,
    SUSPECTED_SHAKEOUT,
    SPRING_RECLAIMED,
    BREACH_OBSERVING,
    HIGH_RISK_BREAKDOWN
}
