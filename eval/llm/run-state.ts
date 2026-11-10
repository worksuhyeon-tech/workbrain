// 현재 상태 제안 지시문(state-v1, FR-09·DEC-86) 시험: 가상 기록 state.json을 처리 함수와 같은 입력·검증으로 돌려 기대와 맞춰 본다.
//   npx -y deno@2.1.4 run --allow-read --allow-write --allow-net eval/llm/run-state.ts
// 기대: changed(제안을 만드는지), status(검증 뒤 상태), include/exclude(요약에 있어야·없어야 할 말),
// changed_or_status(제안을 만들면 그 상태여야 함 — 과거 회의가 새 상태를 덮지 않는지)
import { buildStateInput, cleanState, STATE_PROMPT, STATE_PROMPT_VERSION, STATE_SCHEMA, type StateNow } from "../../supabase/functions/process-record/state.ts";

const ROOT = new URL("../../", import.meta.url);
const PRICE = { input: 0.10, output: 0.50 };   // USD / 1M 토큰(처리 함수와 같음)
const KRW = 1400;

const env = Object.fromEntries(Deno.readTextFileSync(new URL(".env", ROOT)).split(/\r?\n/)
  .map((l) => l.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*)\s*$/)).filter((m): m is RegExpMatchArray => !!m)
  .map((m) => [m[1], m[2].replace(/^["']|["']$/g, "")]));
if (!env.OPENAI_API_KEY) throw new Error("OPENAI_API_KEY가 .env에 없어요");

type Case = {
  id: string; note: string; project: { name: string; purpose?: string };
  current: StateNow | null; pending: StateNow | null; past: boolean;
  record: { title: string; at: string; kind: string }; segments: string[];
  expect: { changed?: boolean; status?: string; include?: string[]; exclude?: string[]; changed_or_status?: string };
};
const cases: Case[] = JSON.parse(Deno.readTextFileSync(new URL("eval/llm/state.json", ROOT)));

let usd = 0, pass = 0;
const results = [];
for (const c of cases) {
  // 운영과 같은 줄 모양: '(이름) 문장'에서 화자를 떼어 [번호] (이름) 문장으로
  const segments = c.segments.map((t, idx) => {
    const m = t.match(/^\((.+?)\)\s*(.*)$/);
    return { idx, text: m ? m[2] : t, speaker: m ? m[1] : null };
  });
  const input = buildStateInput({ project: c.project, current: c.current, pending: c.pending, record: c.record, past: c.past, segments });
  const res = await fetch("https://api.openai.com/v1/responses", {
    method: "POST",
    headers: { "content-type": "application/json", Authorization: `Bearer ${env.OPENAI_API_KEY}` },
    body: JSON.stringify({
      model: "gpt-6-luna", instructions: STATE_PROMPT, input,
      text: { format: { type: "json_schema", name: "project_state", schema: STATE_SCHEMA, strict: true } },
    }),
  });
  const d = await res.json();
  if (!res.ok) throw new Error(`${c.id}: ${d?.error?.message ?? res.status}`);
  usd += ((d.usage?.input_tokens ?? 0) * PRICE.input + (d.usage?.output_tokens ?? 0) * PRICE.output) / 1e6;
  const raw = d.output_text ?? d.output?.flatMap((o: { content?: unknown[] }) => o.content ?? [])
    .find((x: { type?: string }) => x.type === "output_text")?.text;
  const parsed = JSON.parse(raw);
  const clean = cleanState(parsed, segments.length, c.pending ?? c.current, c.past);

  const fails: string[] = [];
  const e = c.expect;
  if (e.changed !== undefined && (clean !== null) !== e.changed) fails.push(`제안 ${clean ? "만듦" : "안 만듦"}(기대 ${e.changed ? "만듦" : "안 만듦"})`);
  if (clean && e.status && clean.status !== e.status) fails.push(`상태 ${clean.status}(기대 ${e.status})`);
  if (clean && e.changed_or_status && clean.status !== e.changed_or_status) fails.push(`과거 회의가 상태를 ${clean.status}로 바꿈`);
  for (const w of e.include ?? []) if (clean && !clean.summary.includes(w)) fails.push(`요약에 '${w}' 없음`);
  for (const w of e.exclude ?? []) if (clean && clean.summary.includes(w)) fails.push(`요약에 '${w}' 있음`);
  if (!fails.length) pass++;
  results.push({ id: c.id, note: c.note, parsed, clean, fails });
  console.log(`${fails.length ? "FAIL" : "PASS"} ${c.id} ${c.note} | ${clean ? `${clean.status} · ${clean.summary} · 근거 ${clean.segments}` : "제안 없음"}${fails.length ? " | " + fails.join(", ") : ""}`);
}
Deno.mkdirSync(new URL("eval/llm/runs/", ROOT), { recursive: true });
const file = new URL(`eval/llm/runs/state-${STATE_PROMPT_VERSION}-${new Date().toISOString().replace(/[:.]/g, "-")}.json`, ROOT);
Deno.writeTextFileSync(file, JSON.stringify({ prompt: STATE_PROMPT_VERSION, usd, results }, null, 1));
console.log(`${pass}/${cases.length} 통과 · 약 ${Math.round(usd * KRW * 10) / 10}원`);
