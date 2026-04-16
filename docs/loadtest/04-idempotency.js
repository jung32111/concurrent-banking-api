// 04-idempotency.js
//
// 목적: 같은 Idempotency-Key로 N번 이체 요청을 보내도
//       잔액은 "딱 1번만" 변하는지 검증한다.
//
// 시나리오:
//   1) setup: 유저 생성 → 계좌 2개 생성 → 시드 입금
//   2) default: 20 VU가 **동일한 Idempotency-Key**로 동시에 이체 요청
//   3) teardown: 잔액 조회 → 이체가 정확히 1번만 반영됐는지 검증
//
// 실행:
//   BASE_URL=http://localhost:8080 k6 run 04-idempotency.js

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.2.0/index.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const SEED_BALANCE   = 1_000_000;
const TRANSFER_AMOUNT = 50_000;
const SHARED_IDEMPOTENCY_KEY = uuidv4();
const REQUESTS_PER_VU = 5;

const firstProcessed  = new Counter('first_processed_200');
const replayedCount   = new Counter('replayed_duplicate');
const conflictCount   = new Counter('conflict_in_progress');
const otherErrorCount = new Counter('other_error');

export const options = {
  scenarios: {
    idempotency: {
      executor: 'per-vu-iterations',
      vus: 20,
      iterations: REQUESTS_PER_VU,
      maxDuration: '30s',
    },
  },
  thresholds: {
    other_error: ['count==0'],
  },
};

function headers(token) {
  return { 'Content-Type': 'application/json', 'Authorization': `Bearer ${token}` };
}

function idempotentHeaders(token, key) {
  return { ...headers(token), 'Idempotency-Key': key };
}

export function setup() {
  const email = `idem+${Date.now()}@example.com`;
  const password = 'Idem1234!';

  http.post(`${BASE_URL}/auth/signup`,
    JSON.stringify({ email, password, name: 'idempotency-test' }),
    { headers: { 'Content-Type': 'application/json' } });

  const loginRes = http.post(`${BASE_URL}/auth/login`,
    JSON.stringify({ email, password }),
    { headers: { 'Content-Type': 'application/json' } });
  const token = loginRes.json().data.accessToken;

  const a1 = http.post(`${BASE_URL}/accounts`,
    JSON.stringify({ ownerName: 'from' }),
    { headers: headers(token) }).json().data.accountNumber;

  const a2 = http.post(`${BASE_URL}/accounts`,
    JSON.stringify({ ownerName: 'to' }),
    { headers: headers(token) }).json().data.accountNumber;

  http.post(`${BASE_URL}/transactions`,
    JSON.stringify({ accountNumber: a1, amount: SEED_BALANCE, type: 'DEPOSIT', description: 'seed' }),
    { headers: idempotentHeaders(token, uuidv4()) });

  const balanceBefore = http.get(`${BASE_URL}/accounts/${a1}/balance`,
    { headers: headers(token) }).json().data;

  console.log(`[setup] from=${a1} to=${a2} balance=${balanceBefore} idempotencyKey=${SHARED_IDEMPOTENCY_KEY}`);
  return { token, from: a1, to: a2, balanceBefore: Number(balanceBefore), key: SHARED_IDEMPOTENCY_KEY };
}

export default function (data) {
  const { token, from, to, key } = data;

  const res = http.post(`${BASE_URL}/transfers`,
    JSON.stringify({ fromAccountNumber: from, toAccountNumber: to, amount: TRANSFER_AMOUNT }),
    { headers: idempotentHeaders(token, key) });

  const replayed = res.headers['X-Idempotency-Replayed'] === 'true';

  if (res.status === 200 && !replayed) {
    firstProcessed.add(1);
  } else if (res.status === 200 && replayed) {
    replayedCount.add(1);
  } else if (res.status === 409) {
    conflictCount.add(1);
  } else {
    otherErrorCount.add(1);
    console.log(`[ERROR] status=${res.status} body=${res.body}`);
  }

  check(res, {
    'status is 200 or 409': (r) => r.status === 200 || r.status === 409,
  });

  sleep(0.05);
}

export function teardown(data) {
  const { token, from, balanceBefore } = data;

  sleep(1);

  const balanceAfter = Number(http.get(`${BASE_URL}/accounts/${from}/balance`,
    { headers: headers(token) }).json().data);

  const actualDeducted = balanceBefore - balanceAfter;
  const expectedDeduction = TRANSFER_AMOUNT;
  const isCorrect = actualDeducted === expectedDeduction;

  const result = {
    balanceBefore,
    balanceAfter,
    expectedDeduction,
    actualDeducted,
    idempotencyVerified: isCorrect,
  };

  console.log('\n=== Idempotency Test Result ===');
  console.log(JSON.stringify(result, null, 2));

  if (isCorrect) {
    console.log(`PASS: 20 VU x ${REQUESTS_PER_VU}회 = ${20 * REQUESTS_PER_VU}건 요청, 잔액 차감은 정확히 1회(${TRANSFER_AMOUNT}원)`);
  } else {
    console.log(`FAIL: 예상 차감 ${expectedDeduction}, 실제 차감 ${actualDeducted}`);
  }

  return {
    'stdout': '\n' + JSON.stringify(result, null, 2) + '\n',
    'results/idempotency-summary.json': JSON.stringify(result, null, 2),
  };
}
