// 업무계획 □ 줄 지시문(plan-head-v1, FR-33·DEC-88) 시험: 가상 계획 plan-head.json을 처리 함수와 같은 입력·검증으로 돌려 기대와 맞춰 본다.
//   npx -y deno run --allow-read --allow-write --allow-net eval/llm/run-plan-head.ts
// 기대(곳마다): kept(서버 검증을 통과해 AI 줄이 쓰이는지), startsWith(이름으로 시작), include·includeAny·exclude(있어야·없어야 할 말)
import { buildHeadInput, cleanHeads, PLAN_HEAD_PROMPT, PLAN_HEAD_SCHEMA, PLAN_HEAD_VERSION } from "../../supabase/functions/process-record/plan-head.ts";
import type { HeadInput } from "../../supabase/functions/_shared/work-plan.ts";

const ROOT = new URL("../../", import.meta.url);
const PRICE = { input: 0.10, output: 0.50 };   // USD / 1M 토큰(처리 함수와 같음)
const KRW = 1400;

const env = Object.fromEntries(Deno.readTextFileSync(new URL(".env", ROOT)).split(/\r?\n/)
  .map((l) => l.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*)\s*$/)).filter((m): m is RegExpMatchArray => !!m)
  .map((m) => [m[1], m[2].replace(/^["']|["']$/g, "")]));
if (!env.OPENAI_API_KEY) throw new Error("OPENAI_API_KEY가 .env에 없어요");

type Expect = { kept?: boolean; startsWith?: string; include?: string[]; includeAny?: string[]; exclude?: string[] };
type Case = { id: string; note: string; period: string; heads: (HeadInput & { expect: Expect })[] };
const cases: Case[] = JSON.parse(Deno.readTextFileSync(new URL("eval/llm/plan-head.json", ROOT)));

let usd = 0, pass = 0, total = 0;
const results = [];
for (const c of cases) {
  const inputs: HeadInput[] = c.heads.map(({ id, name, state, lines }) => ({ id, name, state, lines }));
  const res = await fetch("https://api.openai.com/v1/responses", {
    method: "POST",
    headers: { "content-type": "application/json", Authorization: `Bearer ${env.OPENAI_API_KEY}` },
    body: JSON.stringify({
      model: "gpt-6-luna", instructions: PLAN_HEAD_PROMPT, input: buildHeadInput(c.period, inputs),
      text: { format: { type: "json_schema", name: "plan_heads", schema: PLAN_HEAD_SCHEMA, strict: true } },
    }),
  });
  const d = await res.json();
  if (!res.ok) throw new Error(`${c.id}: ${d?.error?.message ?? res.status}`);
  usd += ((d.usage?.input_tokens ?? 0) * PRICE.input + (d.usage?.output_tokens ?? 0) * PRICE.output) / 1e6;
  const raw = d.output_text ?? d.output?.flatMap((o: { content?: unknown[] }) => o.content ?? [])
    .find((x: { type?: string }) => x.type === "output_text")?.text;
  const parsed = JSON.parse(raw);
  const clean = cleanHeads(parsed, inputs);
  const rawById = new Map(((parsed.heads ?? []) as { id: string; text: string }[]).map((h) => [h.id, h.text]));

  for (const h of c.heads) {
    total++;
    const text = clean.get(h.id);
    const e = h.expect, fails: string[] = [];
    if (e.kept !== undefined && (text !== undefined) !== e.kept) fails.push(text === undefined ? `검증에서 버려짐(AI: ${rawById.get(h.id) ?? "없음"})` : "버려져야 했음");
    if (text !== undefined) {
      if (e.startsWith && !text.replace(/\s/g, "").startsWith(e.startsWith.replace(/\s/g, ""))) fails.push(`'${e.startsWith}'로 시작 안 함`);
      for (const w of e.include ?? []) if (!text.includes(w)) fails.push(`'${w}' 없음`);
      if (e.includeAny && !e.includeAny.some((w) => text.includes(w))) fails.push(`${e.includeAny.join("·")} 중 아무것도 없음`);
      for (const w of e.exclude ?? []) if (text.includes(w)) fails.push(`'${w}' 있음`);
    }
    if (!fails.length) pass++;
    results.push({ case: c.id, id: h.id, name: h.name, text: text ?? null, raw: rawById.get(h.id) ?? null, fails });
    console.log(`${fails.length ? "FAIL" : "PASS"} ${c.id}/${h.id} ${h.name} → ${text ?? "(규칙 줄)"}${fails.length ? " | " + fails.join(", ") : ""}`);
  }
}
Deno.mkdirSync(new URL("eval/llm/runs/", ROOT), { recursive: true });
const stamp = new Date().toISOString().replace(/[:.]/g, "-");
Deno.writeTextFileSync(new URL(`eval/llm/runs/plan-head-${stamp}.json`, ROOT), JSON.stringify({ version: PLAN_HEAD_VERSION, results }, null, 2));
console.log(`\n${PLAN_HEAD_VERSION}: ${pass}/${total} 통과 · 약 ${(usd * KRW).toFixed(2)}원`);
