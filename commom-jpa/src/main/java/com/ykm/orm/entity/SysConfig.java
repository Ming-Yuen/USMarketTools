package com.ykm.orm.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
public class SysConfig extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String configKey;

    @Column(nullable = false)
    private String configValue;

    @Column(length = 512)
    private String description;

    @Column(nullable = false)
    private boolean enabled = true;

    private LocalDateTime effectiveFrom;

    private LocalDateTime effectiveTo;
}

