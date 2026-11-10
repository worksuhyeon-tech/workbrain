// 화자 이름(DEC-76)·받는 사람(DEC-77) 지시문 시험: 화자가 있는 실제 회의로 할 일을 다시 뽑아 지금(v2) 후보와 비교한다.
//   npx -y deno@2.1.4 run --allow-read --allow-write --allow-net eval/llm/run-speakers.ts
// 입력 원문은 data-private/eval/(git 제외)에 CLI 읽기 전용 조회로 받아 둔다. 결과도 같은 폴더 runs/에 둔다(원문을 저장소에 올리지 않음).
// 새 교정 단계는 발언 줄을 그대로 지키므로(DEC-76), 시험 입력은 화자가 있는 원문 발언 줄 + 용어집(표기 기준)으로 대신한다.
import { buildExtractInput, cleanRecipients, normName, resolveOwner } from "../../supabase/functions/process-record/extract.ts";
import { OUTPUT_SCHEMA, PROMPT_VERSION, SYSTEM_PROMPT } from "../../supabase/functions/process-record/prompt.ts";

const ROOT = new URL("../../", import.meta.url);
const PRIVATE = new URL("data-private/eval/", ROOT);
const PRICE = { input: 0.10, output: 0.50 };   // USD / 1M 토큰(처리 함수와 같음)
const KRW = 1400;

const env = Object.fromEntries(Deno.readTextFileSync(new URL(".env", ROOT)).split(/\r?\n/)
  .map((l) => l.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*)\s*$/)).filter((m): m is RegExpMatchArray => !!m)
  .map((m) => [m[1], m[2].replace(/^["']|["']$/g, "")]));
if (!env.OPENAI_API_KEY) throw new Error("OPENAI_API_KEY가 .env에 없어요");

const parse = (v: unknown) => (typeof v === "string" ? JSON.parse(v) : v);
// 첫 인자로 다른 기록 파일(같은 폴더)을 줄 수 있다: ... run-speakers.ts speaker-records-1110.raw.json
const records = JSON.parse(Deno.readTextFileSync(new URL(Deno.args[0] ?? "speaker-records.raw.json", PRIVATE))).rows;
const ctx = JSON.parse(Deno.readTextFileSync(new URL("speaker-context.raw.json", PRIVATE))).rows[0];
const myNames = new Set<string>((parse(ctx.my_names) ?? []).map(normName).filter(Boolean));
const places = [
  ...(parse(ctx.projects) ?? []).map((p: { id: string; name: string; purpose?: string }) => ({ line: `- ${p.id}: ${p.name} (프로젝트)${p.purpose ? " — " + p.purpose : ""}` })),
  ...(parse(ctx.areas) ?? []).map((a: { id: string; name: string }) => ({ line: `- ${a.id}: ${a.name} (반복실행)` })),
];
const vocab: string[] = parse(ctx.vocab) ?? [];
const v2 = (parse(ctx.v2_proposals) ?? []) as { record: string; after: Record<string, unknown>; target: string | null }[];

const results = [];
let usd = 0;
for (const r of records) {
  const segments = (parse(r.original) as { text: string; speaker?: string }[]).map((s, idx) => ({ idx, text: s.text, speaker: s.speaker }));
  const input = buildExtractInput({
    recordKind: r.record_kind, occurredAt: new Date(r.occurred_at ?? r.ingested_at), places,
    attendees: parse(r.attendees) ?? [], vocab, segments, myNames,
  });
  const res = await fetch("https://api.openai.com/v1/responses", {
    method: "POST",
    headers: { "content-type": "application/json", Authorization: `Bearer ${env.OPENAI_API_KEY}` },
    body: JSON.stringify({
      model: "gpt-6-luna", instructions: SYSTEM_PROMPT, input,
      text: { format: { type: "json_schema", name: "record_extraction", schema: OUTPUT_SCHEMA, strict: true } },
    }),
  });
  const d = await res.json();
  if (!res.ok) throw new Error(`${r.title}: ${d?.error?.message ?? res.status}`);
  const cost = ((d.usage?.input_tokens ?? 0) * PRICE.input + (d.usage?.output_tokens ?? 0) * PRICE.output) / 1e6;
  usd += cost;
  const raw = d.output_text ?? d.output?.flatMap((o: { content?: unknown[] }) => o.content ?? [])
    .find((c: { type?: string }) => c.type === "output_text")?.text;
  const out = JSON.parse(raw);
  // 처리 함수와 같은 서버 검증을 거친 결과
  const actions = (out.actions ?? []).map((a: Record<string, unknown>) => {
    const segs = ((a.segments ?? []) as number[]).filter((i) => Number.isInteger(i) && i >= 0 && i < segments.length);
    const owner = resolveOwner(a, r.record_kind, segs.map((n) => segments[n]), myNames);
    return {
      title: a.title, ai_owner: `${a.owner_kind}:${a.assignee ?? ""}`, ...owner,
      recipients: cleanRecipients(a.recipients, myNames), segments: segs,
      evidence: segs.map((n) => `${segments[n].speaker ?? "?"}: ${segments[n].text}`),
    };
  });
  const mine = v2.filter((p) => p.record === r.id && !p.target);
  const count = (xs: { owner_kind?: unknown }[]) => ({
    me: xs.filter((x) => x.owner_kind === "me").length,
    other: xs.filter((x) => x.owner_kind === "other").length,
    unknown: xs.filter((x) => x.owner_kind === "unknown").length,
  });
  results.push({ id: r.id, title: r.title, v2: count(mine.map((p) => p.after)), v4: count(actions), actions });
  const v4 = count(actions);
  console.log(`${String(r.title).slice(0, 16)} | v2 후보 ${mine.length} (나 ${count(mine.map((p) => p.after)).me}·남 ${count(mine.map((p) => p.after)).other}·확인 필요 ${count(mine.map((p) => p.after)).unknown})` +
    ` → v4 ${actions.length} (나 ${v4.me}·남 ${v4.other}·확인 필요 ${v4.unknown}, 말한 사람 기준 ${actions.filter((a: { bySpeaker: boolean }) => a.bySpeaker).length}, 받는 사람 있음 ${actions.filter((a: { recipients: string[] }) => a.recipients.length).length}) | ${Math.round(cost * KRW * 10) / 10}원`);
}
Deno.mkdirSync(new URL("runs/", PRIVATE), { recursive: true });
const file = new URL(`runs/speakers-${PROMPT_VERSION}-${new Date().toISOString().replace(/[:.]/g, "-")}.json`, PRIVATE);
Deno.writeTextFileSync(file, JSON.stringify({ prompt: PROMPT_VERSION, usd, results }, null, 1));
console.log(`합계 ${Math.round(usd * KRW * 10) / 10}원 · 결과 ${decodeURIComponent(file.pathname)}`);
