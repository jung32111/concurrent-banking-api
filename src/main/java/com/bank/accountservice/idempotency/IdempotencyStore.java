package com.bank.accountservice.idempotency;

import java.util.Optional;

public interface IdempotencyStore {

    /**
     * 멱등성 키에 해당하는 저장된 응답(JSON)을 반환한다.
     * - Optional.empty() : 최초 요청, 정상 처리 진행
     * - Optional.of(json) : 이미 처리된 응답 존재, 재사용
     * Redis 구현에서는 내부적으로 IN_PROGRESS 선점을 통해 race condition을 방지한다.
     */
    Optional<String> getResponse(String key);

    /**
     * 처리 완료된 응답(JSON)을 멱등성 키와 함께 저장한다.
     * TTL은 각 구현체 내부에서 관리한다.
     */
    void save(String key, String response);

    /**
     * 처리 실패 시 선점한 키를 즉시 해제한다.
     * Redis 구현체는 IN_PROGRESS 키를 즉시 삭제하고,
     * DB 구현체처럼 선점 개념이 없는 구현체는 no-op으로 둔다.
     */
    default void delete(String key) {} //default , 필요한 구현체만 오버라이드
}
