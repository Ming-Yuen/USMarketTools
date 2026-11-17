package com.ykm;

import com.ykm.stock.analytics.init.DataInitializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

// 預設不啟動完整 context；只有確認 DEV／隔離測試目標後才明確啟用。
@EnabledIfSystemProperty(named = "stock.integration.enabled", matches = "true")
@SpringBootTest(properties = {
		"spring.jpa.hibernate.ddl-auto=none",
		"spring.quartz.auto-startup=false",
		"app.quartz.schema-bootstrap.enabled=false"
})
class StockAnalyticsApplicationTests {

	@MockitoBean
	private DataInitializer dataInitializer;

    @MockitoBean private com.ykm.stock.analytics.config.QuartzConfig quartzConfig;

	// 例子：關閉資料庫結構自動更新與 Quartz 自動啟動後，應用上下文仍能成功載入。
	@Test
	void contextLoads() {
	}

}
