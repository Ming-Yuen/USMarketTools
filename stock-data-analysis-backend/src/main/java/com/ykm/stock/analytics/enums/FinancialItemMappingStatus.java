package com.ykm.stock.analytics.enums;

/** 財報項目的人工語意審核結果；未確認項目不可當成已跳過。 */
public enum FinancialItemMappingStatus {
    MAPPED,
    SKIPPED,
    PENDING
}
