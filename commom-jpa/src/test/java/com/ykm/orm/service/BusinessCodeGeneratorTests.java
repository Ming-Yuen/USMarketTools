package com.ykm.orm.service;

import com.ykm.orm.repository.BusinessCodeSequenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BusinessCodeGeneratorTests {
    private final BusinessCodeSequenceRepository repository = mock(BusinessCodeSequenceRepository.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final BusinessCodeGenerator generator = new BusinessCodeGenerator(repository, transactions);

    @BeforeEach
    void transactionsReturnAStatus() {
        when(transactions.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
    }

    @Test
    void prefixesUseTheirOwnReturnedSequenceAndRepeatedCallsGetNewNumbers() {
        when(repository.nextValue("I")).thenReturn(1L, 2L);
        when(repository.nextValue("P")).thenReturn(1L);

        assertThat(generator.nextCode("I")).isEqualTo("I1");
        assertThat(generator.nextCode("I")).isEqualTo("I2");
        assertThat(generator.nextCode("P")).isEqualTo("P1");
        verify(repository, times(2)).nextValue("I");
        verify(repository).nextValue("P");
    }

    @Test
    void allocationUsesANewShortTransaction() {
        when(repository.nextValue("I")).thenReturn(14L);

        assertThat(generator.nextCode("I")).isEqualTo("I14");

        ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactions).getTransaction(definition.capture());
        assertThat(definition.getValue().getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        verify(transactions).commit(any());
    }

    @Test
    void invalidPrefixesAreRejectedBeforeStartingATransaction() {
        for (String prefix : new String[]{null, "", " ", "i", " I", "I ", "I-", "1I", "I".repeat(33)}) {
            assertThatThrownBy(() -> generator.nextCode(prefix)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("BUSINESS_CODE_PREFIX_INVALID");
        }
        verifyNoInteractions(repository);
        verify(transactions, never()).getTransaction(any());
    }

    @Test
    void maximumLengthPrefixIsAcceptedWithoutNormalization() {
        String prefix = "I" + "A".repeat(31);
        when(repository.nextValue(prefix)).thenReturn(1L);

        assertThat(generator.nextCode(prefix)).isEqualTo(prefix + "1");
        verify(repository).nextValue(prefix);
    }

    @Test
    void storageFailureRollsBackAndPropagatesWithoutRetry() {
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("sequence unavailable");
        when(repository.nextValue("I")).thenThrow(failure);

        assertThatThrownBy(() -> generator.nextCode("I")).isSameAs(failure);

        verify(repository).nextValue("I");
        verify(transactions).rollback(any());
        verify(transactions, never()).commit(any());
    }
}
