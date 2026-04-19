package com.bank.idempotency;

public sealed interface GetOrCreateResult {

    record Fresh() implements GetOrCreateResult {}

    record InProgress() implements GetOrCreateResult {}

    record Replay(int httpStatus, String responseBody) implements GetOrCreateResult {}
}
