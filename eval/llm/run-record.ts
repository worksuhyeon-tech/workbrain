// 실제 기록 1건을 지시문 판·모델마다 몇 번씩 돌려 결정·할 일을 나란히 본다(DEC-112 결정 반영 할 일, 윗급 모델 비교 등).
//   npx -y deno@2.1.4 run --allow-read --allow-write --allow-net eval/llm/run-record.ts <기록 파일> <지시문 md> [반복 수] [모델]
//   예: ... run-record.ts decide-1118.raw.json prompt-v6.md 2 gpt-6.1-terra   (모델을 빼면 gpt-6-luna)
// 기록 파일은 data-private/eval/(git 제외)에 CLI 읽기 전용 조회로 받아 둔다(q-decide-1118.sql 모양: record·segments·projects·areas·vocab·my_names).
// 입력은 처리 함수와 같은 buildExtractInput으로 만든다. 결과도 같은 폴더 runs/에 둔다(원문·결과를 저장소에 올리지 않음).
import { buildExtractInput, normName } from "../../supabase/functions/process-record/extract.ts";
import { OUTPUT_SCHEMA } from "../../supabase/functions/process-record/prompt.ts";

const ROOT = new URL("../../", import.meta.url);
const PRIVATE = new URL("data-private/eval/", ROOT);
const KRW = 1400;

const [recordFile, promptFile, repeatArg, modelArg] = Deno.args;
if (!recordFile || !promptFile) throw new Error("사용법: run-record.ts <기록 파일> <지시문 md> [반복 수] [모델]");
const repeat = Number(repeatArg ?? 1);
const model = modelArg ?? "gpt-6-luna";
const isClaude = model.startsWith("claude");
// USD / 1M 토큰. run.mjs와 같은 표를 쓴다
const PRICE = JSON.parse(Deno.readTextFileSync(new URL("prices.json", import.meta.url)))[model];
if (!PRICE) throw new Error(`${model} 단가가 prices.json에 없어요`);

const env = Object.fromEntries(Deno.readTextFileSync(new URL(".env", ROOT)).split(/\r?\n/)
  .map((l) => l.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*)\s*$/)).filter((m): m is RegExpMatchArray => !!m)
  .map((m) => [m[1], m[2].replace(/^["']|["']$/g, "")]));
const keyName = isClaude ? "ANTHROPIC_API_KEY" : "OPENAI_API_KEY";
if (!env[keyName]) throw new Error(`${keyName}가 .env에 없어요`);

const parse = (v: unknown) => (typeof v === "string" ? JSON.parse(v) : v);
const raw = parse(JSON.parse(Deno.readTextFileSync(new URL(recordFile, PRIVATE))).rows[0].out);
const prompt = Deno.readTextFileSync(new URL(promptFile, import.meta.url));
const rec = raw.record;
const segments = (raw.segments as { text: string; speaker?: string }[]).map((s, idx) => ({ idx, text: s.text, speaker: s.speaker }));
const places = [
  ...(raw.projects ?? []).map((p: { id: string; name: string; purpose?: string }) => ({ line: `- ${p.id}: ${p.name} (프로젝트)${p.purpose ? " — " + p.purpose : ""}` })),
  ...(raw.areas ?? []).map((a: { id: string; name: string }) => ({ line: `- ${a.id}: ${a.name} (반복실행)` })),
];
const input = buildExtractInput({
  recordKind: rec.record_kind, occurredAt: new Date(rec.occurred_at ?? rec.ingested_at), places,
  attendees: rec.attendees ?? [], vocab: raw.vocab ?? [], segments,
  myNames: new Set<string>((raw.my_names ?? []).map(normName).filter(Boolean)),
});

type Call = { out: { decisions?: { segments: number[]; text: string }[]; actions?: { segments: number[]; owner_kind: string; assignee?: string | null; due_kind: string; due_date?: string | null; title: string }[] }; inTok: number; outTok: number };

// 같은 입력·지시문·출력 형식으로 부른다. Claude는 응답 형식(JSON 스키마)으로 구조를 강제한다(run.mjs와 같은 방식)
async function call(): Promise<Call> {
  const res = isClaude
    ? await fetch("https://api.anthropic.com/v1/messages", {
      method: "POST",
      headers: { "content-type": "application/json", "x-api-key": env.ANTHROPIC_API_KEY, "anthropic-version": "2023-06-01" },
      body: JSON.stringify({
        model, max_tokens: 16000, system: prompt, messages: [{ role: "user", content: input }],
        output_config: { format: { type: "json_schema", schema: OUTPUT_SCHEMA } },
      }),
    })
    : await fetch("https://api.openai.com/v1/responses", {
      method: "POST",
      headers: { "content-type": "application/json", Authorization: `Bearer ${env.OPENAI_API_KEY}` },
      body: JSON.stringify({
        model, instructions: prompt, input,
        text: { format: { type: "json_schema", name: "record_extraction", schema: OUTPUT_SCHEMA, strict: true } },
      }),
    });
  const d = await res.json();
  if (!res.ok) throw new Error(d?.error?.message ?? `HTTP ${res.status}`);
  if (isClaude) {
    // 거절·길이 초과면 JSON이 스키마대로가 아닐 수 있어 멈춘다
    if (d.stop_reason !== "end_turn") throw new Error(`stop_reason ${d.stop_reason}`);
    const text = d.content?.find((c: { type?: string }) => c.type === "text")?.text;
    return { out: JSON.parse(text), inTok: d.usage?.input_tokens ?? 0, outTok: d.usage?.output_tokens ?? 0 };
  }
  const text = d.output_text ?? d.output?.flatMap((o: { content?: unknown[] }) => o.content ?? [])
    .find((c: { type?: string }) => c.type === "output_text")?.text;
  return { out: JSON.parse(text), inTok: d.usage?.input_tokens ?? 0, outTok: d.usage?.output_tokens ?? 0 };
}

const runs = [];
let usd = 0;
for (let i = 0; i < repeat; i++) {
  const { out, inTok, outTok } = await call();
  usd += (inTok * PRICE.input + outTok * PRICE.output) / 1e6;
  runs.push(out);
  console.log(`── ${model} ${promptFile} ${i + 1}/${repeat}`);
  for (const x of out.decisions ?? []) console.log(`  결정 ${JSON.stringify(x.segments)} ${x.text}`);
  for (const a of out.actions ?? []) {
    console.log(`  할 일 ${JSON.stringify(a.segments)} [${a.owner_kind}${a.assignee ? ":" + a.assignee : ""} · ${a.due_kind}${a.due_date ? " " + a.due_date : ""}] ${a.title}`);
  }
}
Deno.mkdirSync(new URL("runs/", PRIVATE), { recursive: true });
const file = new URL(`runs/record-${recordFile.replace(/\.raw\.json$/, "")}-${model}-${promptFile.replace(/\.md$/, "")}-${new Date().toISOString().replace(/[:.]/g, "-")}.json`, PRIVATE);
Deno.writeTextFileSync(file, JSON.stringify({ model, prompt: promptFile, usd, runs }, null, 1));
console.log(`합계 ${Math.round(usd * KRW * 10) / 10}원 · 결과 ${decodeURIComponent(file.pathname)}`);
