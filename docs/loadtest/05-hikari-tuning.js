// 05-hikari-tuning.js
//
// 목적: HikariCP 커넥션 풀 튜닝 전/후 차이를 수치로 검증한다.
//       기존 02-transfer-contention.js(4분 시나리오)와 동일한 이체 경합 경로를 쓰되,
//       short scenario(~2분)로 짧게 3회 반복 측정이 가능하도록 만든다.
//
// 실행:
//   RUN_LABEL=hikari-before-1 \
//   BASE_URL=http://localhost:8080 \
//     k6 run --out json=results/hikari-before-1-raw.json docs/loadtest/05-hikari-tuning.js
//
// 환경변수:
//   RUN_LABEL       — 결과 파일 prefix (예: hikari-before-1, hikari-after-2)
//   BASE_URL        — 서버 URL (default: http://localhost:8080)
//   ACCOUNT_POOL    — 경합 강도 (default: 4, 작을수록 경합 ↑)

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Counter, Trend } from 'k6/metrics';
import { randomIntBetween, uuidv4 } from 'https://jslib.k6.io/k6-utils/1.2.0/index.js';

const BASE_URL          = __ENV.BASE_URL   || 'http://localhost:8080';
const ACCOUNT_POOL_SIZE = Number(__ENV.ACCOUNT_POOL || 4);
const RUN_LABEL         = __ENV.RUN_LABEL  || 'hikari-run';
const SEED_BALANCE   = 10_000_000;
const TRANSFER_AMOUNT = 1000;

const lockConflictRate = new Rate('lock_conflict_rate');
const successCounter   = new Counter('transfer_success');
const conflictCounter  = new Counter('transfer_conflict_409');
const otherErrCounter  = new Counter('transfer_other_error');
const serverErrCounter = new Counter('transfer_500');
const transferLatency  = new Trend('transfer_latency', true);

// 짧은 시나리오 — 총 2분 (ramp 20s → peak 60s → ramp-down 20s + setup/teardown 여유)
export const options = {
  scenarios: {
    contention: {
      executor: 'ramping-vus',
      startVUs: 10,
      stages: [
        { duration: '20s', target: 80 },
        { duration: '60s', target: 150 },   // peak 유지 1분
        { duration: '20s', target: 0 },
      ],
      gracefulRampDown: '10s',
    },
  },
  // 실패는 분석 대상이므로 abort 안 함
  thresholds: {},
};

function headers(token) {
  return { 'Content-Type': 'application/json', 'Authorization': `Bearer ${token}` };
}
function idempotentHeaders(token) {
  return { ...headers(token), 'Idempotency-Key': uuidv4() };
}

function signupAndLogin(email, password, name) {
  http.post(`${BASE_URL}/auth/signup`,
    JSON.stringify({ email, password, name }),
    { headers: { 'Content-Type': 'application/json' } });

  const res = http.post(`${BASE_URL}/auth/login`,
    JSON.stringify({ email, password }),
    { headers: { 'Content-Type': 'application/json' } });

  check(res, { 'login 200': (r) => r.status === 200 });
  return res.json().data.accessToken;
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
  console.log(`[setup] label=${RUN_LABEL} pool=${ACCOUNT_POOL_SIZE} accounts=${accounts.join(',')}`);
  return { token, accounts };
}

export default function (data) {
  const { token, accounts } = data;

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
    tags: { endpoint: 'transfer' },
  });

  transferLatency.add(res.timings.duration);
  const is409 = res.status === 409;
  lockConflictRate.add(is409);

  if (res.status === 200) successCounter.add(1);
  else if (is409)         conflictCounter.add(1);
  else if (res.status >= 500) { otherErrCounter.add(1); serverErrCounter.add(1); }
  else                    otherErrCounter.add(1);

  check(res, { 'status is 200 or 409': (r) => r.status === 200 || r.status === 409 });

  sleep(0.1);
}

export function handleSummary(data) {
  const m = data.metrics;
  const pick = (name, key) => (m[name] && m[name].values && m[name].values[key] !== undefined)
    ? m[name].values[key] : 'n/a';

  const summary = {
    label: RUN_LABEL,
    scenario: 'hikari-tuning-short',
    account_pool: ACCOUNT_POOL_SIZE,
    http_reqs:  pick('http_reqs', 'count'),
    tps:        pick('http_reqs', 'rate'),
    latency_ms: {
      avg: pick('transfer_latency', 'avg'),
      p95: pick('transfer_latency', 'p(95)'),
      p99: pick('transfer_latency', 'p(99)'),
      max: pick('transfer_latency', 'max'),
    },
    success:       pick('transfer_success', 'count'),
    conflict409:   pick('transfer_conflict_409', 'count'),
    server_500:    pick('transfer_500', 'count'),
    other_error:   pick('transfer_other_error', 'count'),
    conflict_rate: pick('lock_conflict_rate', 'rate'),
  };

  const outFile = `results/${RUN_LABEL}-summary.json`;
  const stdout  = '\n=== Hikari Tuning Summary (' + RUN_LABEL + ') ===\n'
                + JSON.stringify(summary, null, 2) + '\n';

  return {
    'stdout': stdout,
    [outFile]: JSON.stringify(summary, null, 2),
  };
}
