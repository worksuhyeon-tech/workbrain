// 회의록·할 일 추출 실행기 (DEC-19: gpt-6-luna vs Haiku 5.5)
//   node eval/llm/run.mjs --model gpt-6-luna --split dev            개선용 12건
//   node eval/llm/run.mjs --model claude-haiku-5-5 --split holdout  보류 12건(정답은 모델에 보내지 않는다)
//   node eval/llm/run.mjs --model ... --split all --limit 2         일부만
//   node eval/llm/run.mjs --dry --split dev --limit 1               호출 없이 보낼 내용만 출력
// API 키는 저장소 루트 .env(OPENAI_API_KEY, ANTHROPIC_API_KEY)에서 읽는다. .env는 커밋하지 않는다.
// 결과는 runs/<모델>__<세트>__<시각>.json, 채점은 score.mjs.
import { readFileSync, writeFileSync, mkdirSync, existsSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = join(HERE, "..", "..");
const args = Object.fromEntries(process.argv.slice(2).reduce((acc, a, i, all) => {
  if (a.startsWith("--")) acc.push([a.slice(2), all[i + 1] && !all[i + 1].startsWith("--") ? all[i + 1] : true]);
  return acc;
}, []));

// DEC-19 단가(USD / 1M 토큰). 환율은 --krw로 바꿀 수 있다
const PRICE = { input: 0.10, output: 0.50 };
const KRW = Number(args.krw ?? 1400);
const PROMPT_FILE = args.prompt ?? "prompt-v1.md";
const MODEL = args.model;
const SPLIT = args.split ?? "dev";
const LIMIT = args.limit ? Number(args.limit) : Infinity;

function loadEnv() {
  const p = join(ROOT, ".env");
  if (!existsSync(p)) return {};
  return Object.fromEntries(readFileSync(p, "utf8").split(/\r?\n/)
    .map((l) => l.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*)\s*$/)).filter(Boolean)
    .map((m) => [m[1], m[2].replace(/^["']|["']$/g, "")]));
}

const load = (name) => JSON.parse(readFileSync(join(HERE, name), "utf8"));
const SYSTEM = readFileSync(join(HERE, PROMPT_FILE), "utf8");
const SCHEMA = load("schema.json");
const CONTEXT = load("context.json");
const records = (SPLIT === "all" ? ["dev", "holdout", "security"] : [SPLIT])
  .flatMap((s) => load(`${s}.json`)).slice(0, LIMIT);

const WEEKDAY = "일월화수목금토";
// 모델에 보내는 내용: 기록 정보·공간 목록·구간. 정답(gold)은 절대 넣지 않는다
function userMessage(r) {
  const at = r.recorded_at;
  const day = WEEKDAY[new Date(at.slice(0, 10) + "T00:00:00Z").getUTCDay()];
  const items = CONTEXT.items.map((i) => `- ${i.id}: ${i.name} (${i.kind === "area" ? "반복실행" : "프로젝트"}${i.archived ? ", archived" : ""}) — ${i.purpose}`).join("\n");
  const segs = r.segments.map((s, i) => `[${i}] ${s}`).join("\n");
  return `기록 종류: ${r.type}\n녹음 시각: ${at.slice(0, 10)} (${day}) ${at.slice(11, 16)} Asia/Seoul\n\n공간: ${CONTEXT.workspace}\n${items}\n\n전사:\n${segs}`;
}

async function callOpenAI(key, r) {
  const res = await fetch("https://api.openai.com/v1/responses", {
    method: "POST",
    headers: { "content-type": "application/json", authorization: `Bearer ${key}` },
    body: JSON.stringify({
      model: MODEL, instructions: SYSTEM, input: userMessage(r),
      text: { format: { type: "json_schema", name: "record_extraction", schema: SCHEMA, strict: true } },
    }),
  });
  const data = await res.json();
  if (!res.ok) throw Object.assign(new Error(data?.error?.message ?? `HTTP ${res.status}`), { status: res.status });
  const text = data.output_text ?? data.output?.flatMap((o) => o.content ?? []).find((c) => c.type === "output_text")?.text;
  return { text, usage: { input_tokens: data.usage?.input_tokens ?? 0, output_tokens: data.usage?.output_tokens ?? 0 }, request_id: data.id };
}

async function callAnthropic(key, r) {
  const res = await fetch("https://api.anthropic.com/v1/messages", {
    method: "POST",
    headers: { "content-type": "application/json", "x-api-key": key, "anthropic-version": "2023-06-01" },
    body: JSON.stringify({
      model: MODEL, max_tokens: 4096, system: SYSTEM,
      messages: [{ role: "user", content: userMessage(r) }],
      // 도구 입력 형식으로 JSON 구조를 강제한다(자유 텍스트 답변을 받지 않음)
      tools: [{ name: "record_extraction", description: "전사에서 뽑은 회의록·할 일 후보", input_schema: SCHEMA }],
      tool_choice: { type: "tool", name: "record_extraction" },
    }),
  });
  const data = await res.json();
  if (!res.ok) throw Object.assign(new Error(data?.error?.message ?? `HTTP ${res.status}`), { status: res.status });
  const tool = data.content?.find((c) => c.type === "tool_use");
  return { text: tool ? JSON.stringify(tool.input) : null, usage: { input_tokens: data.usage?.input_tokens ?? 0, output_tokens: data.usage?.output_tokens ?? 0 }, request_id: data.id };
}

// 일시 오류(429·5xx)만 최대 3번 다시 시도한다
async function withRetry(fn) {
  for (let attempt = 0; ; attempt++) {
    try { return await fn(); }
    catch (e) {
      const transient = e.status === 429 || (e.status >= 500 && e.status < 600);
      if (!transient || attempt >= 3) throw e;
      await new Promise((r) => setTimeout(r, 2000 * 2 ** attempt));
    }
  }
}

async function main() {
  if (args.dry) {
    console.log("── system ──\n" + SYSTEM + "\n── user (" + records[0].id + ") ──\n" + userMessage(records[0]));
    return;
  }
  if (!MODEL) { console.error("--model 이 필요해요 (예: gpt-6-luna, claude-haiku-5-5)"); process.exit(2); }
  const env = { ...loadEnv(), ...process.env };
  const isClaude = MODEL.startsWith("claude");
  const key = isClaude ? env.ANTHROPIC_API_KEY : env.OPENAI_API_KEY;
  if (!key) { console.error(`${isClaude ? "ANTHROPIC_API_KEY" : "OPENAI_API_KEY"}가 .env에 없어요`); process.exit(2); }
  const call = isClaude ? callAnthropic : callOpenAI;

  const results = [], usage = { input_tokens: 0, output_tokens: 0 };
  for (const r of records) {
    const started = Date.now();
    let out = { id: r.id, output: null, error: null };
    try {
      const resp = await withRetry(() => call(key, r));
      usage.input_tokens += resp.usage.input_tokens; usage.output_tokens += resp.usage.output_tokens;
      out.usage = resp.usage; out.request_id = resp.request_id;
      try { out.output = JSON.parse(resp.text); } catch { out.error = "JSON 해석 실패"; out.raw = resp.text; }
    } catch (e) {
      out.error = `${e.status ?? ""} ${e.message}`.trim();
    }
    out.ms = Date.now() - started;
    results.push(out);
    console.log(`${r.id} ${out.error ? "실패 " + out.error : "완료"} (${out.ms}ms)`);
  }
  usage.usd = (usage.input_tokens * PRICE.input + usage.output_tokens * PRICE.output) / 1e6;
  usage.krw = usage.usd * KRW;

  mkdirSync(join(HERE, "runs"), { recursive: true });
  const stamp = new Date().toISOString().replace(/[:.]/g, "-");
  const file = join(HERE, "runs", `${MODEL}__${SPLIT}__${stamp}.json`);
  writeFileSync(file, JSON.stringify({ model: MODEL, split: SPLIT, prompt_version: PROMPT_FILE, created_at: new Date().toISOString(), price_usd_per_1m: PRICE, krw_rate: KRW, usage, results }, null, 2));
  console.log(`저장: ${file}\n토큰 입력 ${usage.input_tokens} · 출력 ${usage.output_tokens} · 약 $${usage.usd.toFixed(4)} (₩${Math.round(usage.krw)})`);
  console.log(`채점: node eval/llm/score.mjs "${file}"`);
}

main().catch((e) => { console.error(e); process.exit(1); });
