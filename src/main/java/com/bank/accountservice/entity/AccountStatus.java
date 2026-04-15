package com.bank.accountservice.entity;

/**
 * 계좌 상태.
 *
 * <ul>
 *   <li>{@link #ACTIVE}   - 정상. 모든 거래 허용.</li>
 *   <li>{@link #DORMANT}  - 휴면. 장기 미사용/사용자 요청으로 잠김. 재활성화 전까지 거래 불가.</li>
 *   <li>{@link #FROZEN}   - 동결. 분실신고/법적 조치. 해제 전까지 모든 거래 차단.</li>
 * </ul>
 *
 * 단순성을 위해 ACTIVE 상태에서만 거래를 허용한다.
 * (실무에는 DORMANT 에서도 입금만 허용하는 모델이 있으나 포트폴리오 범위에서는 엄격 차단을 선택)
 */
public enum AccountStatus {
    ACTIVE,
    DORMANT,
    FROZEN
}
