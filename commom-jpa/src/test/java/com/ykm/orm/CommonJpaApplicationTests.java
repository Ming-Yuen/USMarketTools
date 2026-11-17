package com.ykm.orm;

import com.ykm.orm.repository.BusinessCodeSequenceRepository;
import com.ykm.orm.service.BusinessCodeGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CommonJpaApplicationTests {
    @Test
    void libraryComponentsWireWithProvidedJdbcAndTransactionInfrastructure() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq("I"))).thenReturn(1L);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        new ApplicationContextRunner()
                .withUserConfiguration(BusinessCodeGenerator.class, BusinessCodeSequenceRepository.class)
                .withBean(JdbcTemplate.class, () -> jdbc)
                .withBean(PlatformTransactionManager.class, () -> transactions)
                .run(context -> {
                    assertThat(context).hasNotFailed()
                            .hasSingleBean(BusinessCodeGenerator.class)
                            .hasSingleBean(BusinessCodeSequenceRepository.class)
                            .doesNotHaveBean(javax.sql.DataSource.class);
                    assertThat(context.getBean(BusinessCodeGenerator.class).nextCode("I")).isEqualTo("I1");
                });

        verify(jdbc).queryForObject(anyString(), eq(Long.class), eq("I"));
        verify(transactions).getTransaction(argThat(definition -> definition.getPropagationBehavior()
                == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        verify(transactions).commit(any());
    }
}
