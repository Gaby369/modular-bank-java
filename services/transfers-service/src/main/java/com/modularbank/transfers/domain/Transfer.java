package com.modularbank.transfers.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    schema = "transfers",
    name = "transfers"
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Transfer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(
        name = "source_account_id",
        nullable = false
    )
    private UUID sourceAccountId;

    @Column(
        name = "target_account_id",
        nullable = false
    )
    private UUID targetAccountId;

    @Column(
        nullable = false,
        precision = 19,
        scale = 4
    )
    private BigDecimal amount;

    @Column(length = 255)
    private String reference;

    @Column(
        nullable = false,
        length = 20
    )
    @Builder.Default
    private String status = "PENDING";

    @CreationTimestamp
    @Column(
        name = "created_at",
        nullable = false,
        updatable = false
    )
    private Instant createdAt;
}
