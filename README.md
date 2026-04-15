# Account Service

계좌 관리 및 이체를 처리하는 Spring Boot 기반 뱅킹 백엔드 API.
금융권 포트폴리오 목적으로 **동시성 제어 · 멱등성 · 감사 로그 · JWT 인증** 을 중점적으로 구현하였습니다.

---

## 📌 핵심 특징

| 영역 | 구현 |
|---|---|
| 인증 | JWT Access Token + Refresh Token Rotation (RTR) |
| 동시성 | `PESSIMISTIC_WRITE` 락 + 계좌번호 정렬 락 획득 순서 (데드락 방지) |
| 멱등성 | `Idempotency-Key` 헤더 기반 필터 (Redis / DB 이중 백엔드) |
| 보안 | Rate Limiting (Bucket4j), PII 마스킹, Stateless 세션 |
| 감사 | 모든 금융 거래 · 인증 이벤트 AuditLog 독립 트랜잭션 기록 (`REQUIRES_NEW`) |
| 문서 | SpringDoc OpenAPI 3 (Swagger UI) |

---

## 🛠 기술 스택

- **Language / Runtime**: Java 21, Spring Boot 3.4.3
- **Persistence**: Spring Data JPA, MySQL 8
- **Cache / Idempotency**: Redis
- **Security**: Spring Security, JJWT 0.11.5
- **API Docs**: SpringDoc OpenAPI 2.8.5
- **Rate Limiting**: Bucket4j 8.10.1
- **Build / Test**: Gradle, JUnit 5, Mockito, H2 (테스트)

---

## 🏗 아키텍처

```
Client ──HTTP──► [TraceIdFilter → RateLimitFilter → JwtAuthFilter → IdempotencyFilter]
                                           │
                                           ▼
                          Controller ─► Service (@Transactional)
                                           │
                           ┌───────────────┼─────────────────┐
                           ▼               ▼                 ▼
                     JPA Repository    Redis Cache     AuditLogService
                           │                              (REQUIRES_NEW)
                           ▼
                         MySQL
```

### 패키지 구성
```
com.bank.accountservice
├── controller      REST 엔드포인트
├── service         비즈니스 로직 (@Transactional)
├── entity          JPA 엔티티 (User, Account, Transaction, AuditLog, RefreshToken, IdempotencyKey)
├── repository      Spring Data JPA
├── security        JWT 발급 / 검증, SecurityConfig
├── filter          TraceId, RateLimit 필터
├── idempotency     Idempotency 필터 + Store (Redis / DB)
├── exception       커스텀 예외 + GlobalExceptionHandler
├── dto             Request / Response DTO
├── domain          BaseTimeEntity (Auditing)
├── config          Redis, Filter 설정
└── util            LogMaskingUtil (PII 마스킹)
```

---

## 🔑 주요 API

### Auth
| Method | Path | 설명 |
|---|---|---|
| POST | `/auth/signup` | 회원가입 |
| POST | `/auth/login` | 로그인 (AT + RT 발급, Rate Limited 5/min) |
| POST | `/auth/refresh` | 토큰 갱신 (RTR) |
| POST | `/auth/logout` | 로그아웃 (RT 전체 삭제) |

### Account
| Method | Path | 설명 |
|---|---|---|
| POST | `/accounts` | 계좌 개설 |
| GET | `/accounts` | 내 계좌 목록 |
| GET | `/accounts/{accountNumber}` | 계좌 조회 |
| GET | `/accounts/{accountNumber}/balance` | 잔액 조회 |

### Transaction / Transfer
| Method | Path | 설명 |
|---|---|---|
| POST | `/transactions` | 입금 / 출금 (Idempotency-Key 필수) |
| GET | `/transactions/{accountNumber}` | 거래 내역 (페이징) |
| POST | `/transfers` | 계좌 이체 (Idempotency-Key 필수) |

Swagger UI: `http://localhost:8080/swagger-ui.html`

---

## 🔒 핵심 설계 의사결정

### 1. 비관적 락 (PESSIMISTIC_WRITE) 선택 이유
계좌 도메인은 동일 리소스에 대한 **경합 빈도가 높고 실패 시 재시도 비용이 큰** 워크로드입니다.
낙관적 락(`@Version`)은 충돌 시 `OptimisticLockException` → 재시도 루프를 거치는데, 이체처럼 2개 계좌를 원자적으로 다뤄야 하는 경우 재시도가 복잡해집니다.
따라서 DB 수준에서 행을 직렬화하는 비관적 락을 채택하여 **로직 단순성 + 정합성**을 확보했습니다.

### 2. 데드락 방지 - 계좌번호 정렬
두 계좌를 락 걸 때 **항상 계좌번호 사전순**으로 락을 획득합니다.
A→B 이체와 B→A 이체가 동시에 발생해도 락 순서가 동일하므로 데드락이 발생하지 않습니다.
→ `TransferConcurrencyTest` 에서 검증.

### 3. 멱등성 (Idempotency-Key)
네트워크 재시도로 인한 **중복 이체 방지**를 위해 결제 업계 표준 패턴을 구현.
- 같은 Key + 같은 요청 바디 → 캐시된 응답 반환 (`Idempotent-Replay: true` 헤더)
- 같은 Key + 다른 요청 바디 → 409 Conflict
- Redis 우선, 장애 시 DB fallback

### 4. Refresh Token Rotation (RTR)
RT 사용 시마다 새로운 RT 발급 + 기존 RT는 `used=true`.
이미 사용된 RT가 재요청되면 **토큰 탈취로 간주**하여 해당 사용자의 모든 RT를 삭제, 재로그인 강제.

### 5. 감사 로그 독립 트랜잭션
`AuditLogService.record()` 는 `@Transactional(propagation = REQUIRES_NEW)`.
본 트랜잭션이 롤백돼도 감사 기록은 보존됩니다 (컴플라이언스 요구사항).

### 6. PII 마스킹
계좌번호 `100-12345678` → `100-****5678` 로 마스킹 후 로그/감사 출력.

---

## 🚀 실행 방법

### Docker Compose (권장)
```bash
docker-compose up --build
```
MySQL 8, Redis, 애플리케이션이 함께 기동됩니다.

### 로컬 실행
```bash
# 1. MySQL / Redis 기동 필요
# 2. 환경변수 설정 (.env 파일 또는 export)
export DB_URL=jdbc:mysql://localhost:3306/accountservice
export DB_USERNAME=root
export DB_PASSWORD=...
export JWT_SECRET=... # 최소 256-bit
export REDIS_HOST=localhost
export REDIS_PORT=6379

./gradlew bootRun
```

---

## ✅ 테스트

```bash
./gradlew test            # 전체 테스트
./gradlew test jacocoTestReport   # 커버리지 리포트
# 리포트: build/reports/jacoco/test/html/index.html
```

### 테스트 구성
| 파일 | 범위 |
|---|---|
| `AuthServiceTest` | 회원가입/로그인/RTR |
| `AccountServiceTest` | 계좌 개설, 권한 검증 |
| `TransactionServiceTest` | 입출금, 잔액 부족 |
| `TransferServiceTest` | 이체 성공/실패, 자기 계좌 거부 |
| `TransferConcurrencyTest` | 양방향 동시 이체 (데드락 없음) |

---

## 📈 향후 개선 과제

- [ ] Redisson 분산 락 도입 (다중 인스턴스 환경)
- [ ] k6 / JMeter 부하 테스트 + TPS 그래프
- [ ] Spring Boot Actuator + Prometheus / Grafana 모니터링
- [ ] 거래 한도 정책 (1회 / 일일)
- [ ] 계좌 상태 (ACTIVE / DORMANT / FROZEN)
- [ ] Spring Batch 이자 계산 배치

---

## 📄 License

Portfolio project. Not for production use.
