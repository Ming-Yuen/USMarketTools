package com.ykm.common.common_lib.generator;

import com.ykm.common.common_lib.service.DateTimeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

@Component
@RequiredArgsConstructor
public class IdGenerator {
    private final AtomicLong sequence = new AtomicLong(0);
    private volatile String lastTimestampStr = "";
    private final Object lock = new Object();

    private final DateTimeService dateTimeService;

    // 预定义 Formatter 提高性能，避免反复创建
    private static final DateTimeFormatter YYYYMMDDHHMMSS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter HHMMSS = DateTimeFormatter.ofPattern("HHmmss");

    /**
     * 生成 19 位左右的 Long ID
     * 格式：yyyyMMddHHmmss + 5位序号 (例如 2026041610501200001)
     */
    public Long generateId() {
        return generateFormattedId(YYYYMMDDHHMMSS, 5);
    }

    /**
     * 为 IB TWS API 生成 ID
     * 格式：HHmmss + 4位序号 (例如 1050120001)
     * 这样生成的 ID 是 10 位数，小于 2,147,483,647，可以安全转为 int
     */
    public int genIbRequestId() {
        // 使用较短的时间前缀，确保结果不超出 Integer.MAX_VALUE
        return generateFormattedId(HHMMSS, 4).intValue();
    }

    /**
     * 核心生成逻辑
     * @param formatter 时间格式
     * @param seqPadding 序号补齐位数 (5 表示 00000-99999)
     */
    private Long generateFormattedId(DateTimeFormatter formatter, int seqPadding) {
        String datetime = dateTimeService.nowLocalDateTime().format(formatter);
        synchronized (lock) {
            if (!datetime.equals(lastTimestampStr)) {
                lastTimestampStr = datetime;
                sequence.set(0);
            }
            long seq = sequence.getAndIncrement();

            // 关键：使用补位，确保 ID 长度恒定，不会因为 seq 是 1 还是 100 导致总位数改变
            // %05d 表示如果数字不足 5 位，前面补 0
            String formatStr = "%s%0" + seqPadding + "d";
            String combined = String.format(formatStr, datetime, seq);

            return Long.parseLong(combined);
        }
    }

}
