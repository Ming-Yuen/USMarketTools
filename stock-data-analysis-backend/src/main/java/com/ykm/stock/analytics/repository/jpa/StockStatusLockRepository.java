package com.ykm.stock.analytics.repository.jpa;

import com.ykm.stock.analytics.model.entity.StockStatus;

import java.util.Collection;
import java.util.List;

/** 來源同步與估值共用的狀態列鎖；呼叫端必須先開始交易，再讀寫水位。 */
public interface StockStatusLockRepository {
    void acquireValuationPublishLock();

    List<StockStatus> findAllByIdForUpdate(Collection<String> figis);
}
