package com.bank.idempotency;

public interface IdempotencyStore {

    /**
     * 멱등성 키를 선점하거나 기존 기록을 조회한다.
     * - Fresh: 최초 요청, 호출자가 처리 진행
     * - InProgress: 다른 요청이 처리 중 → 409
     * - Replay: 완료된 응답 존재 → 저장된 응답 재생
     * request body 해시가 기존 기록과 다르면 IdempotencyHashMismatchException을 던진다.
     */
    GetOrCreateResult getOrCreate(String key, String requestHash);

    /**
     * 선점된 키에 실제 응답을 채워 넣는다.
     * requestHash는 Redis 구현체가 응답 JSON에 포함시켜 Replay 시 해시 검증에 사용한다.
     * DB 구현체는 이미 INSERT 시점에 저장했으므로 무시한다.
     */
    void saveResponse(String key, String requestHash, int httpStatus, String responseBody);
}
