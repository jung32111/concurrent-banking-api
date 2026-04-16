// 02-transfer-contention.js
//
// 목적: 소수 계좌에 트래픽을 몰아 "락 경합"을 의도적으로 유발하고
//       - Redisson 분산락 + DB 비관적락  (현재 main)
//       - DB 비관적락 단독                (비교 브랜치)
//       두 구성의 TPS / P99 / 409 비율을 수치로 비교한다.
//
// 실행:
//   BASE_URL=http://localhost:8080 k6 run --out json=results/contention.json 02-transfer-contention.js
//
// 전제: 서버가 기동되어 있고, signup/login/accounts/transactions 엔드포인트 사용 가능.
// setup 단계에서 테스트 유저/계좌를 직접 생성하므로 사전 시딩 불필요.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Counter, Trend } from 'k6/metrics';
import { randomIntBetween, uuidv4 } from 'https://jslib.k6.io/k6-utils/1.2.0/index.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const ACCOUNT_POOL_SIZE = Number(__ENV.ACCOUNT_POOL || 4);   // 경합 강도: 작을수록 충돌 많음
const SEED_BALANCE = 10_000_000;                             // 각 계좌 초기 잔액
const TRANSFER_AMOUNT = 1000;

// --- 커스텀 메트릭 ---
const lockConflictRate = new Rate('lock_conflict_rate');     // 409 비율
const successCounter   = new Counter('transfer_success');
const conflictCounter  = new Counter('transfer_conflict_409');
const otherErrCounter  = new Counter('transfer_other_error');
const transferLatency  = new Trend('transfer_latency', true);

export const options = {
  scenarios: {
    contention: {
      executor: 'ramping-vus',
      startVUs: 10,
      stages: [
        { duration: '30s', target: 50 },
        { duration: '1m',  target: 100 },
        { duration: '2m',  target: 150 },   // 최고 부하 유지
        { duration: '30s', target: 0 },
      ],
      gracefulRampDown: '10s',
    },
  },
  thresholds: {
    // 참고용 상한선 — 실패로 찍혀도 분석이 목적이므로 abortOnFail 미사용
    http_req_failed:   ['rate<0.30'],
    transfer_latency:  ['p(95)<2000', 'p(99)<5000'],
  },
};

function headers(token) {
  return {
    'Content-Type': 'application/json',
    'Authorization': `Bearer ${token}`,
  };
}

function idempotentHeaders(token) {
  return { ...headers(token), 'Idempotency-Key': uuidv4() };
}

function signupAndLogin(email, password, name) {
  // 멱등 가입: 이미 존재하면 무시하고 로그인 진행
  http.post(`${BASE_URL}/auth/signup`,
    JSON.stringify({ email, password, name }),
    { headers: { 'Content-Type': 'application/json' } });

  const res = http.post(`${BASE_URL}/auth/login`,
    JSON.stringify({ email, password }),
    { headers: { 'Content-Type': 'application/json' } });

  check(res, { 'login 200': (r) => r.status === 200 });
  const body = res.json();
  // ApiResponse<TokenResponse> 가정: body.data.accessToken
  return body.data.accessToken;
}

function createAccount(token) {
  const res = http.post(`${BASE_URL}/accounts`,
    JSON.stringify({ ownerName: 'loadtest' }),
    { headers: headers(token) });
  check(res, { 'account created': (r) => r.status === 200 || r.status === 201 });
  return res.json().data.accountNumber;
}

function deposit(token, accountNumber, amount) {
  const res = http.post(`${BASE_URL}/transactions`,
    JSON.stringify({ accountNumber, amount, type: 'DEPOSIT', description: 'seed' }),
    { headers: idempotentHeaders(token) });
  check(res, { 'deposit ok': (r) => r.status === 200 || r.status === 201 });
}

// setup: 1회 실행. 테스트 유저 1명 + ACCOUNT_POOL_SIZE개의 계좌 생성 + 시드 잔액 입금.
export function setup() {
  const email = `loadtest+${Date.now()}@example.com`;
  const password = 'Loadtest1!';
  const token = signupAndLogin(email, password, 'loadtest');

  const accounts = [];
  for (let i = 0; i < ACCOUNT_POOL_SIZE; i++) {
    const no = createAccount(token);
    deposit(token, no, SEED_BALANCE);
    accounts.push(no);
  }
  console.log(`[setup] pool=${ACCOUNT_POOL_SIZE}, accounts=${accounts.join(',')}`);
  return { token, accounts };
}

export default function (data) {
  const { token, accounts } = data;

  // 같은 풀에서 서로 다른 from/to 2개 선택 → 경합 최대화
  const i = randomIntBetween(0, accounts.length - 1);
  let j = randomIntBetween(0, accounts.length - 1);
  if (j === i) j = (j + 1) % accounts.length;

  const payload = JSON.stringify({
    fromAccountNumber: accounts[i],
    toAccountNumber:   accounts[j],
    amount:            TRANSFER_AMOUNT,
  });

  const res = http.post(`${BASE_URL}/transfers`, payload, {
    headers: idempotentHeaders(token),
    tags:    { endpoint: 'transfer' },
  });

  transferLatency.add(res.timings.duration);

  const is409 = res.status === 409;
  lockConflictRate.add(is409);

  if (res.status === 200) {
    successCounter.add(1);
  } else if (is409) {
    conflictCounter.add(1);
  } else {
    otherErrCounter.add(1);
  }

  check(res, {
    'status is 200 or 409': (r) => r.status === 200 || r.status === 409,
  });

  sleep(0.1);
}

export function handleSummary(data) {
  const m = data.metrics;
  const pick = (name, key) => (m[name] && m[name].values && m[name].values[key] !== undefined)
    ? m[name].values[key] : 'n/a';

  const summary = {
    scenario: 'transfer-contention',
    account_pool: ACCOUNT_POOL_SIZE,
    http_reqs: pick('http_reqs', 'count'),
    tps:       pick('http_reqs', 'rate'),
    latency_ms: {
      avg: pick('transfer_latency', 'avg'),
      p95: pick('transfer_latency', 'p(95)'),
      p99: pick('transfer_latency', 'p(99)'),
      max: pick('transfer_latency', 'max'),
    },
    success:     pick('transfer_success', 'count'),
    conflict409: pick('transfer_conflict_409', 'count'),
    other_error: pick('transfer_other_error', 'count'),
    conflict_rate: pick('lock_conflict_rate', 'rate'),
  };

  return {
    'stdout': '\n=== Contention Test Summary ===\n' + JSON.stringify(summary, null, 2) + '\n',
    'results/contention-summary.json': JSON.stringify(summary, null, 2),
  };
}
