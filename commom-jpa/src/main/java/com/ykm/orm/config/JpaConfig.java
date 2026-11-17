package com.ykm.orm.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorProvider")
//@EnableJpaRepositories(
//        repositoryBaseClass = BaseRepositoryImpl.class,
//        basePackages = "com.stockinsight.repository"
//)
public class JpaConfig {
}
