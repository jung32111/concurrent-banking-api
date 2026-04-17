package com.bank.idempotency;

//상태코드와 응답 반환
public record StoredResponse(int httpStatus, String responseBody) {
}

