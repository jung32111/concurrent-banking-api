package com.bank.accountservice.entity;

import com.bank.accountservice.domain.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "idempotency_keys",
        indexes = {
                @Index(name = "idx_idempotency_key", columnList = "idempotency_key"),
                @Index(name = "idx_expired_at", columnList = "expired_at")
        }
)
public class IdempotencyKey extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 128)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "response_body", columnDefinition = "MEDIUMTEXT", nullable = false)
    private String responseBody;

    @Column(name = "http_status", nullable = false)
    private int httpStatus;

    @Column(name = "expired_at", nullable = false)
    private LocalDateTime expiredAt;

    @Builder
    public IdempotencyKey(
            String idempotencyKey,
            String requestHash,
            String responseBody,
            int httpStatus,
            LocalDateTime expiredAt
    ) {
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.responseBody = responseBody;
        this.httpStatus = httpStatus;
        this.expiredAt = expiredAt;
    }

    //현재 시간 바탕으로 만료여부 확인
    public boolean isExpired(LocalDateTime now) {
        return expiredAt.isBefore(now);
    }
}