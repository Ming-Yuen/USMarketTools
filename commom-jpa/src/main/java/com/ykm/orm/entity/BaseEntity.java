package com.ykm.orm.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Abstract base class for entities, providing common auditing fields and versioning.
 *
 * @author Developer

 */
@Data
@EntityListeners(AuditingEntityListener.class)
@MappedSuperclass
public class BaseEntity implements Serializable {

    /**
     * Username or identifier of the user who created the entity.
     */
    @CreatedBy
    @Column(nullable = false, updatable = false)
    private String createdBy;

    /**
     * Date when the entity was created.
     */
    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Username or identifier of the user who last updated the entity.
     */
    @LastModifiedBy
    @Column(nullable = false)
    private String updatedBy;

    /**
     * Date when the entity was last updated.
     */
    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    /**
     * Version number used for optimistic locking.
     */
    @Version
    private Long version;
    /**
     * Protected no-argument constructor for BaseEntity.
     * <p>
     * Initializes a new instance of BaseEntity with default field values.
     * </p>
     */
    protected BaseEntity() {
    }
}
