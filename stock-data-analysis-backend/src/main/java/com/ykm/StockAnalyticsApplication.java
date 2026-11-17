package com.ykm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.retry.annotation.EnableRetry;

@SpringBootApplication
@EnableJpaRepositories(basePackages = {"com.ykm.stock.analytics.repository.jpa", "com.ykm.orm.repository"})
@EnableRetry
public class StockAnalyticsApplication {

	public static void main(String[] args) {
		SpringApplication.run(StockAnalyticsApplication.class, args);
	}
}
