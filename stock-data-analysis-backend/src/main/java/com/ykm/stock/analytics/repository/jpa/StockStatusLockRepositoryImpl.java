package com.ykm.stock.analytics.repository.jpa;

import com.ykm.stock.analytics.model.entity.StockStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

@RequiredArgsConstructor
public class StockStatusLockRepositoryImpl implements StockStatusLockRepository {
    private final EntityManager entityManager;

    /** 先按 FIGI 固定順序取鎖，再批次重讀，避免手動請求／OSIV 預讀的舊實體覆蓋回退。 */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<StockStatus> findAllByIdForUpdate(Collection<String> figis) {
        if (figis.isEmpty()) return List.of();
        jakarta.persistence.TypedQuery<StockStatus> query = entityManager.createQuery(
                        "select s from StockStatus s where s.figi in :figis order by s.figi", StockStatus.class)
                .setParameter("figis", figis).setLockMode(LockModeType.PESSIMISTIC_WRITE);
        // detach 不釋放資料庫交易鎖；只移除這批狀態的舊快取，以兩次查詢避免逐股 refresh 的 N+1。
        for (StockStatus status : query.getResultList()) entityManager.detach(status);
        return query.getResultList();
    }
}
