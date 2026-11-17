package com.ykm.orm.service;

import com.ykm.orm.repository.BusinessCodeSequenceRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.regex.Pattern;

@Component
public class BusinessCodeGenerator {
    private static final Pattern PREFIX_PATTERN = Pattern.compile("[A-Z][A-Z0-9_]{0,31}");

    private final BusinessCodeSequenceRepository repository;
    private final TransactionTemplate transactions;

    public BusinessCodeGenerator(BusinessCodeSequenceRepository repository, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 配置例如 I1 的業務編號；先提交取號交易，避免在後續長工作期間持有計數器列鎖。 */
    public String nextCode(String prefix) {
        if (prefix == null || !PREFIX_PATTERN.matcher(prefix).matches())
            throw new IllegalArgumentException("BUSINESS_CODE_PREFIX_INVALID");
        Long value = transactions.execute(status -> repository.nextValue(prefix));
        return prefix + Objects.requireNonNull(value, "Business code sequence returned no value");
    }
}
