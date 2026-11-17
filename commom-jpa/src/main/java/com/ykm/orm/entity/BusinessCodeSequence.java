package com.ykm.orm.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.Comment;

@Data
@Entity
@Table(name = "business_code_sequence")
@Comment("共用業務編號計數器；每個 prefix 一列，取號使用獨立短交易，允許跳號。")
public class BusinessCodeSequence {
    @Id
    @Column(nullable = false, length = 32, updatable = false)
    @Comment("業務編號前綴；大寫英文字母開頭，後續只接受大寫英數字或底線。")
    private String prefix;

    @Column(name = "last_value", nullable = false)
    @Comment("此 prefix 最後已配置的序號；首次取號為 1，不因呼叫端業務交易回滾而退回。")
    private Long lastValue;
}
