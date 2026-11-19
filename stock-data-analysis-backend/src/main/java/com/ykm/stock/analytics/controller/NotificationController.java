package com.ykm.stock.analytics.controller;

import com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.DailyClosingBriefingDto;
import com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.InAppNotificationDto;
import com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.UnreadCountDto;
import com.ykm.stock.analytics.service.portfolio.PortfolioClosingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("notifications")
@RequiredArgsConstructor
public class NotificationController {
    private final PortfolioClosingService closingService;

    @GetMapping
    public List<InAppNotificationDto> list(
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(required = false) Integer limit) {
        return closingService.listNotifications(severity, category, unreadOnly, limit);
    }

    @GetMapping("/unread-count")
    public UnreadCountDto unreadCount() {
        return new UnreadCountDto(closingService.countUnread());
    }

    @PostMapping("/{id}/read")
    public Map<String, Object> markRead(@PathVariable Long id) {
        boolean updated = closingService.markRead(id);
        return Map.of("id", id, "updated", updated, "unreadCount", closingService.countUnread());
    }

    @PostMapping("/read-all")
    public Map<String, Object> markAllRead() {
        int updated = closingService.markAllRead();
        return Map.of("updatedCount", updated, "unreadCount", closingService.countUnread());
    }

    @GetMapping("/briefing/latest")
    public ResponseEntity<DailyClosingBriefingDto> latestBriefing() {
        return closingService.getLatestBriefing()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
