package com.ykm.orm.config;

import io.micrometer.common.util.StringUtils;
import org.springframework.data.domain.AuditorAware;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component("auditorProvider")
public class AuditingConfig implements AuditorAware<String> {

    private static final ThreadLocal<String> operationSourceHolder = new ThreadLocal<>();
    private static final ThreadLocal<String> taskSourceHolder = new ThreadLocal<>();

    /** 設定此執行緒的 Quartz TaskCode，不影響 getCurrentUser() 的使用者身份。 */
    public static void setOperationSource(String source) {
        taskSourceHolder.set(source);
    }

    /** 執行結束必須清理，避免執行緒重用時沿用上一個任務的來源。 */
    public static void clearOperationSource() {
        taskSourceHolder.remove();
    }

    @Override
    public Optional<String> getCurrentAuditor() {
        String taskCode = taskSourceHolder.get();
        String auditor = StringUtils.isBlank(taskCode) ? operationSourceHolder.get() : taskCode;
        return Optional.of(StringUtils.isBlank(auditor) ? "SYSTEM" : auditor);
    }

    public String getCurrentUser() {
        String user = operationSourceHolder.get();
        return StringUtils.isBlank(user) ? "System" : user;
    }
}

