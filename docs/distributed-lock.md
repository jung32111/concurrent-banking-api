# Redisson 분산 락 도입기

> Spring Boot 계좌 서비스의 이체 동시성 문제를 **DB 비관적 락만으로는 충분하지 않다고 판단한 근거**와, Redisson 분산 락을 도입하며 선택한 설계 결정을 정리한 문서입니다.

---

## 1. 배경 — DB 비관적 락만으로는 왜 부족했나

초기 구현은 `AccountRepository.findByAccountNumberWithLock()`의 `PESSIMISTIC_WRITE` 만으로 이체 동시성을 제어했습니다.

```java
Account from = accountRepository.findByAccountNumberWithLock(fromNo).orElseThrow();
Account to   = accountRepository.findByAccountNumberWithLock(toNo).orElseThrow();
// ... balance 조작 + transaction 기록
```

단일 인스턴스에서는 잘 동작했지만, **다중 인스턴스 수평 확장**을 고려할 때 다음 한계가 보였습니다.

| 문제 | DB 락만 쓸 때 증상 |
|---|---|
| DB 커넥션 포화 | 앱 인스턴스 N대 × 요청이 모두 DB에서 대기 → 커넥션 풀·로우 락 큐가 DB에 집중 → **DB 장애 전파** 위험 |
| 대기 가시성 | 락 대기 중인 요청을 앱이 관여하지 못해 타임아웃·서킷 브레이커 같은 상위 제어가 어려움 |
| 확장성 | DB는 보통 **가장 마지막으로 스케일하는 계층**. 여기에 동시성 병목을 몰아넣으면 비용이 크다 |

> 요약: **"DB 락은 최후의 보루여야지, 첫 번째 방어선이어서는 안 된다."**

---

## 2. 대안 비교

| 방식 | 장점 | 단점 | 채택 |
|---|---|---|---|
| DB `PESSIMISTIC_WRITE` | 추가 인프라 0, 트랜잭션 경계와 일치 | 위의 한계, 여러 계좌 락 시 데드락 리스크 | **최후 방어선으로 유지** |
| 낙관적 락 `@Version` | 동시성 ↑ | 충돌 시 재시도 루프, 이체처럼 2계좌 원자성 확보 시 로직 복잡 | ✗ |
| DB 행락 + 분산 락 (Redis) | 앞단에서 직렬화, DB 부하 완화 | Redis 의존성, TTL 설계 필요 | ✓ **이중 방어** |
| ZooKeeper | 강한 일관성 | 인프라 무거움, 뱅킹 규모엔 과함 | ✗ |

---

## 3. 왜 Redisson인가

| 항목 | Spring Data Redis `SETNX`+TTL 직접 구현 | Redisson `RLock` |
|---|---|---|
| Reentrant 락 | 직접 구현 필요 | 내장 |
| **Watchdog (자동 갱신)** | 직접 스레드 돌려야 함 | `leaseTime=-1`로 활성화 가능 |
| `tryLock(wait, lease)` | 직접 구현 | API 제공 |
| Multi-Lock / RedLock | 직접 구현 | 내장 |
| Pub/Sub 기반 대기 해제 | 직접 구현 | 내장 (spin lock 회피) |

→ **단순 SETNX 구현은 프로덕션 이슈(만료된 락 해제, 타 클라이언트 락 삭제, 재진입 등)를 하나씩 직접 해결해야 함.** Redisson은 이 엣지케이스들을 이미 다루고 있어 **재발명 비용을 아끼고 검증된 구현에 의존**하는 것이 합리적이었습니다.

---

## 4. 설계

### 4.1 계층 구조 — 이중 방어 (Layered Locking)

```
 Client
   │
   ▼
┌──────────────────────────────────────────────────────┐
│ TransferService.transfer()                           │
│                                                      │
│   ① Redisson 분산 락 획득 (key = lock:account:<no>)  │ ← 앱 레벨 직렬화
│     │                                                │
│     ▼                                                │
│   ② @Transactional (TransactionTemplate)             │
│     │                                                │
│     ▼                                                │
│   ③ SELECT ... FOR UPDATE (PESSIMISTIC_WRITE)        │ ← DB 레벨 최후 방어
│     │                                                │
│     ▼                                                │
│   ④ balance 조작 + transaction 기록                   │
│                                                      │
│   ⑤ commit                                           │
│   ⑥ 분산 락 해제                                      │
└──────────────────────────────────────────────────────┘
```

**왜 순서가 "락 → 트랜잭션"인가**
트랜잭션을 먼저 열고 락을 걸면, 락 대기하는 동안 DB 커넥션·언두로그·갭 락이 계속 잡혀 있게 됩니다. 반대로 **락부터 획득 → 성공한 요청만 트랜잭션을 연다**면 DB 자원은 실제 일하는 시간만큼만 점유됩니다.

→ 구현상 `@Transactional`을 메서드에 붙이면 AOP 프록시가 락보다 먼저 동작하므로, **`TransactionTemplate` 로 트랜잭션 경계를 락 안쪽에 명시적으로 배치** 했습니다.

### 4.2 데드락 방지 — 계좌번호 사전순 정렬

두 개의 계좌를 동시에 락 걸어야 하는 이체는 고전적인 데드락 위험이 있습니다.

```
Thread-1: A 이체 B  → lock(A) 성공 → lock(B) 대기
Thread-2: B 이체 A  → lock(B) 성공 → lock(A) 대기  ⇒ DEADLOCK
```

해결:
```java
String first  = key1.compareTo(key2) <= 0 ? key1 : key2;
String second = key1.compareTo(key2) <= 0 ? key2 : key1;
executeWithLock(first, () -> executeWithLock(second, action));
```

**방향이 어떻든 두 스레드가 같은 순서로 락을 요청**하므로 데드락이 발생하지 않습니다. `TransferConcurrencyTest.concurrentBidirectionalTransfer_finishesWithin5Seconds_withoutDeadlock` 에서 A↔B 양방향 이체를 동시에 돌려 검증합니다.

### 4.3 타임아웃 / TTL 파라미터

```java
private static final long LOCK_WAIT_SECONDS  = 3L;   // 락 획득 대기
private static final long LOCK_LEASE_SECONDS = 5L;   // 락 보유(TTL)
```

| 파라미터 | 선택 근거 |
|---|---|
| `waitTime = 3s` | 사용자 체감 지연 상한. 초과 시 409 Conflict("동시 처리 중인 요청이 있습니다") → 클라이언트가 재시도 가능 |
| `leaseTime = 5s` | 이체 트랜잭션의 P99 예상 시간보다 여유 있게. **클라이언트/앱 크래시 시 TTL로 자동 해제** |

`leaseTime` 을 너무 길게 잡으면 장애 복구가 느려지고, 너무 짧으면 **트랜잭션이 락보다 오래 살아남는 위험**. 실무에서는 Watchdog(leaseTime=-1)로 자동 갱신하는 방식도 있으나, 본 프로젝트는 명시적 상한을 두어 **장애 격리** 를 우선했습니다.

---

## 5. 구현 핵심

### 5.1 인터페이스 분리

```java
public interface DistributedLockManager {
    <T> T executeWithLock(String key, long waitSeconds, long leaseSeconds, Callable<T> action);
    <T> T executeWithMultiLock(String key1, String key2, long waitSeconds, long leaseSeconds, Callable<T> action);
}
```

**왜 인터페이스로 뽑았나**
- 서비스 레이어가 Redisson에 직접 의존하지 않음 → 테스트에서 Fake/Mock 치환 용이
- 추후 ZooKeeper·Hazelcast 전환 가능성 확보

### 5.2 Redisson 구현

```java
@Override
public <T> T executeWithLock(String key, long waitSeconds, long leaseSeconds, Callable<T> action) {
    RLock lock = redissonClient.getLock(LOCK_PREFIX + key);
    boolean acquired = false;
    try {
        acquired = lock.tryLock(waitSeconds, leaseSeconds, TimeUnit.SECONDS);
        if (!acquired) {
            throw new LockAcquisitionException("분산 락 획득 실패: " + key);
        }
        return action.call();
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new LockAcquisitionException("분산 락 대기 중 인터럽트: " + key);
    } finally {
        if (acquired && lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
```

**놓치기 쉬운 포인트**
1. `isHeldByCurrentThread()` 체크 후 `unlock()` — TTL 만료 후 다른 스레드가 잡은 락을 실수로 푸는 것을 방지.
2. `InterruptedException` 발생 시 **interrupt 플래그 복구** — 상위 제어 흐름이 인터럽트 정보를 잃지 않도록.
3. 획득 실패는 `LockAcquisitionException` → `GlobalExceptionHandler` 에서 **409 Conflict** 매핑.

---

## 6. 트레이드오프와 한계

| 항목 | 현재 선택 | 한계 | 장래 개선 |
|---|---|---|---|
| Redis 단일 노드 | `useSingleServer()` | Redis 장애 시 전체 이체 불가 | Redis Sentinel / Cluster, RedLock |
| Lease 고정 5s | 명시적 상한 | 장시간 트랜잭션 시 락 만료 리스크 | Watchdog(leaseTime=-1) 전환 |
| 장애 시 동작 | Redis 다운 → `LockAcquisitionException` → 요청 거부 | 가용성 희생 | 서킷브레이커 + DB락만 쓰는 degraded mode |

> **의도한 트레이드오프**: 금융 도메인에서는 "틀린 커밋"보다 "거절"이 낫다고 판단 → **가용성보다 정합성 우선(CP)**.

---

## 7. 검증

| 테스트 | 확인 항목 |
|---|---|
| `TransferServiceTest` | 락/트랜잭션 레이어를 목으로 치환한 비즈니스 로직 단위 검증 |
| `RedissonDistributedLockManagerTest` | 락 획득 성공/실패 → 예외 매핑, 멀티락 사전순 호출, unlock 보장 |
| `TransferConcurrencyTest` | 실 Redis + H2로 A↔B 양방향 이체 동시 실행 → **데드락 없음 + 잔액 정합성 유지** |

부하테스트 결과는 [`docs/loadtest/README.md`](loadtest/README.md)에 정리되어 있습니다.

---

## 8. 회고 체크리스트

- [x] 락 범위를 **자원(계좌) 단위** 로 한정 — 전역 락 남용 없음
- [x] 락 키 네임스페이스 (`lock:account:`) 로 다른 도메인과 충돌 방지
- [x] TTL로 **Liveness** 확보 (무한 대기 방지)
- [x] `isHeldByCurrentThread()` 로 **Safety** 확보 (타인의 락 해제 방지)
- [x] 멀티락 **획득 순서 고정** — 데드락 없음
- [x] DB 비관적 락을 **보조 방어선** 으로 유지
- [x] 부하테스트 기반 TPS Before/After 측정 — **TPS 17.9배 향상, P95 94% 감소** ([결과](loadtest/README.md))
