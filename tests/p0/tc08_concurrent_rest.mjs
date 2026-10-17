// P0 TC-08 동시 승인 — 실제 REST(PostgREST RPC) 경로, 두 기기 = 같은 계정의 두 세션
//   node tests/p0/tc08_concurrent_rest.mjs
// 준비: tests/p0/tc08_concurrent_seed.sql로 임시 계정·제안을 만든 뒤 실행(끝나면 계정 삭제). .env의 SUPABASE_URL·SUPABASE_PUBLISHABLE_KEY·P0_TEST_EMAIL·P0_TEST_PASSWORD 사용.
// 확인: (1) 같은 새 할 일 제안 20건을 두 세션이 동시에 승인 → 할 일 20건·승인 기록 20건(효과 1회)
//       (2) 같은 기준 버전의 상태 변경 제안 10건을 동시에 승인 → 1건만 반영, 9건 충돌
//       (3) 로그인하지 않은 요청(공개 키만) → 테이블·RPC 모두 거부
import { readFileSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
const env = Object.fromEntries(readFileSync(join(ROOT, ".env"), "utf8").split(/\r?\n/)
  .map((l) => l.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*)\s*$/)).filter(Boolean).map((m) => [m[1], m[2]]));
const URL_ = env.SUPABASE_URL, KEY = env.SUPABASE_PUBLISHABLE_KEY;
if (!URL_ || !KEY || !env.P0_TEST_EMAIL || !env.P0_TEST_PASSWORD) { console.error(".env에 시험 설정이 없어요"); process.exit(2); }

async function signIn() {
  const r = await fetch(`${URL_}/auth/v1/token?grant_type=password`, {
    method: "POST", headers: { apikey: KEY, "content-type": "application/json" },
    body: JSON.stringify({ email: env.P0_TEST_EMAIL, password: env.P0_TEST_PASSWORD }),
  });
  const d = await r.json();
  if (!r.ok) throw new Error("로그인 실패: " + JSON.stringify(d));
  return d.access_token;
}
const headers = (token) => ({ apikey: KEY, "content-type": "application/json", ...(token ? { authorization: `Bearer ${token}` } : {}) });
async function rest(token, path) {
  const r = await fetch(`${URL_}/rest/v1/${path}`, { headers: { ...headers(token), prefer: "count=exact" } });
  return { status: r.status, body: await r.json(), count: Number((r.headers.get("content-range") ?? "/0").split("/")[1]) };
}
async function approve(token, id, rev, context) {
  const r = await fetch(`${URL_}/rest/v1/rpc/approve_proposal`, {
    method: "POST", headers: headers(token),
    body: JSON.stringify({ p_proposal_id: id, p_seen_revision: rev, p_context: context }),
  });
  const d = await r.json();
  return r.ok ? d.result : `HTTP ${r.status} ${d.message ?? ""}`;
}

const results = [];
const check = (name, pass, detail) => { results.push(pass); console.log(`${pass ? "PASS" : "FAIL"} ${name} — ${detail}`); };

// 두 기기
const [deviceA, deviceB] = await Promise.all([signIn(), signIn()]);
check("같은 계정 두 세션 로그인", deviceA !== deviceB, "토큰 2개");

const list = await rest(deviceA, "change_proposal?select=id,revision,dedup_key&status=eq.pending&dedup_key=in.(race-task,race-state)");
const tasks = list.body.filter((p) => p.dedup_key === "race-task");
const states = list.body.filter((p) => p.dedup_key === "race-state");
check("시드 제안 확인", tasks.length === 20 && states.length === 10, `새 할 일 ${tasks.length} · 상태 변경 ${states.length}`);

// (1) 같은 제안을 두 기기가 동시에: 40개 요청을 한꺼번에 보낸다
const t0 = Date.now();
const pairs = await Promise.all(tasks.map((p) => Promise.all([
  approve(deviceA, p.id, p.revision, "inbox"), approve(deviceB, p.id, p.revision, "record"),
])));
const ms = Date.now() - t0;
const okPairs = pairs.filter(([a, b]) => [a, b].sort().join() === ["already-approved", "approved"].sort().join()).length;
check("동시 승인 20쌍 → 쌍마다 approved 1 + already-approved 1", okPairs === 20, `${okPairs}/20쌍, ${ms}ms, 예: ${JSON.stringify(pairs[0])}`);
const made = await rest(deviceA, "task?select=id&title=like.*%EB%8F%99%EC%8B%9C%20%EC%8A%B9%EC%9D%B8*");
check("만들어진 할 일 = 20(중복 0)", made.count === 20, `${made.count}건`);
const ids = tasks.map((p) => p.id).join(",");
const events = await rest(deviceA, `approval_event?select=id&proposal_id=in.(${ids})`);
check("승인 기록 = 20(제안당 1)", events.count === 20, `${events.count}건`);

// (2) 같은 기준 버전의 상태 변경 10건을 동시에
const stateResults = await Promise.all(states.map((p, i) => approve(i % 2 ? deviceA : deviceB, p.id, p.revision, "inbox")));
const tally = stateResults.reduce((m, r) => ({ ...m, [r]: (m[r] ?? 0) + 1 }), {});
check("동시 상태 변경 10건 → 1 approved · 9 conflict", tally.approved === 1 && tally.conflict === 9, JSON.stringify(tally));
const versions = await rest(deviceA, "project_state_version?select=version");
check("상태 버전 1개만 생성", versions.count === 1, `${versions.count}개`);

// (3) 로그인 없이 공개 키만
const anonTable = await rest(null, "task?select=id");
check("공개 키만으로 테이블 읽기 → 거부", anonTable.status === 401 || anonTable.status === 403, `HTTP ${anonTable.status}`);
const anonRpc = await approve(null, tasks[0].id, 0, "inbox");
check("공개 키만으로 승인 RPC → 거부", anonRpc.startsWith("HTTP 4"), anonRpc);

const failed = results.filter((x) => !x).length;
console.log(`\n${results.length - failed}/${results.length} 통과`);
process.exit(failed ? 1 : 0);
