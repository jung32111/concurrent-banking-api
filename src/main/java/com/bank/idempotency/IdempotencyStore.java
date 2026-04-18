package com.bank.idempotency;

public interface IdempotencyStore {

    /**
     * 멱등성 키를 선점하거나 기존 기록을 조회한다.
     * - Fresh: 최초 요청, 호출자가 처리 진행
     * - InProgress: 다른 요청이 처리 중 (response_body가 아직 NULL) → 409
     * - Replay: 완료된 응답 존재 → 저장된 응답 재생
     * request body 해시가 기존 기록과 다르면 IdempotencyHashMismatchException을 던진다.
     */
    GetOrCreateResult getOrCreate(String key, String requestHash);

    /**
     * 선점된 키에 실제 응답을 채워 넣는다.
     */
    void saveResponse(String key, int httpStatus, String responseBody);
}
