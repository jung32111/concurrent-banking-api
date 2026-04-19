package com.bank.entity;

import com.bank.domain.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;

@Entity
@Getter
@DynamicUpdate
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "idempotency_keys",
        indexes = {
                @Index(name = "idx_idempotency_key", columnList = "idempotency_key"),
                @Index(name = "idx_created_at", columnList = "created_at")
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

    @Column(name = "response_body", columnDefinition = "MEDIUMTEXT")
    private String responseBody;

    @Column(name = "http_status")
    private Integer httpStatus;

    private IdempotencyKey(String idempotencyKey, String requestHash) {
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
    }

    public static IdempotencyKey forNewRequest(String idempotencyKey, String requestHash) {
        return new IdempotencyKey(idempotencyKey, requestHash);
    }

    public void fillResponse(int httpStatus, String responseBody) {
        this.httpStatus = httpStatus;
        this.responseBody = responseBody;
    }

    public boolean isCompleted() {
        return responseBody != null;
    }
}
