package com.ykm.stock.analytics.service;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.*;
import com.ykm.stock.analytics.service.analysis.calculator.ZoneOverlapCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ZoneOverlapCalculatorTests {
    // 例子：A 90 至 100、B 95 至 105、C 101 至 110 鏈狀重疊，不得併成一個三區群組。
    @Test
    void chainOverlapWithoutCommonIntersectionDoesNotBecomeOneThreeZoneGroup() {
        ZoneOverlapCalculator c = new ZoneOverlapCalculator();
        List<ZoneOverlapGroupDto> groups = c.calculate(List.of(zone("A","90","100"),zone("B","95","105"),zone("C","101","110")));
        assertThat(groups).hasSize(1);
        assertThat(groups.getFirst().zoneIds()).containsExactly("A", "B");
        assertThat(groups.getFirst().lower()).isEqualByComparingTo("95");
        assertThat(groups.getFirst().upper()).isEqualByComparingTo("100");
    }
    private LevelZoneDto zone(String id,String lo,String hi){
        return new LevelZoneDto(id,AnalysisTimeframe.DAILY,new BigDecimal(lo),new BigDecimal(hi),ZoneRole.SUPPORT,BigDecimal.TEN,null,0,null,ZoneState.ACTIVE,new BigDecimal("100"),BigDecimal.ONE,1,ZoneStrengthLabel.LOW,BigDecimal.TEN,1,null,null,List.of(),List.of("dep"),0,0,0,null,null,RetestState.NONE,false,List.of());
    }
}
