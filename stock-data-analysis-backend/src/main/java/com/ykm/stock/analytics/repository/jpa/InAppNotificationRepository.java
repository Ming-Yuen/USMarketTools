package com.ykm.stock.analytics.repository.jpa;

import com.ykm.stock.analytics.model.entity.InAppNotification;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface InAppNotificationRepository extends JpaRepository<InAppNotification, Long> {
    boolean existsByDedupKey(String dedupKey);

    @Query("select n.dedupKey from InAppNotification n where n.dedupKey in :dedupKeys")
    List<String> findExistingDedupKeys(@Param("dedupKeys") Collection<String> dedupKeys);

    long countByReadFalse();

    @Query("""
            select n from InAppNotification n
            where (:severity is null or n.severity = :severity)
              and (:category is null or n.category = :category)
              and (:unreadOnly = false or n.read = false)
            order by n.createdAt desc, n.id desc
            """)
    List<InAppNotification> findFiltered(@Param("severity") String severity,
                                         @Param("category") String category,
                                         @Param("unreadOnly") boolean unreadOnly,
                                         Pageable pageable);

    @Modifying
    @Query("update InAppNotification n set n.read = true, n.readAt = :readAt where n.id = :id and n.read = false")
    int markReadIfUnread(@Param("id") Long id, @Param("readAt") Instant readAt);

    @Modifying
    @Query("update InAppNotification n set n.read = true, n.readAt = :readAt where n.read = false")
    int markAllRead(@Param("readAt") Instant readAt);
}
