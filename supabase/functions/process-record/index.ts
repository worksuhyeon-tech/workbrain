// 기록 처리 (P1 2단계, Design §2.3·§3.1, DEC-28/30)
// 호출 한 번에 한 단계만 진행한다: STT(gpt-transcribe) → EXTRACT(gpt-6-luna) → REVIEWABLE.
// 무료 플랜의 함수 실행 시간 제한 안에 들도록 단계를 나눴고, 앱이 끝날 때까지 다시 부른다(같은 작업은 한 번만 잡힘).
// AI 결과는 초안·제안으로만 저장한다. 할 일은 사용자가 승인해야 생긴다(§3.4).
import { createClient, type SupabaseClient } from "jsr:@supabase/supabase-js@2";
import { OUTPUT_SCHEMA, PROMPT_VERSION, SYSTEM_PROMPT } from "./prompt.ts";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const OPENAI_KEY = Deno.env.get("OPENAI_API_KEY");

const STT_MODEL = "gpt-transcribe";
const LLM_MODEL = "gpt-6-luna";
const STT_USD_PER_MIN = 0.0045;        // DEC-19
const LLM_USD_PER_1M = { input: 0.10, output: 0.50 };
const KRW_PER_USD = 1400;
const STT_MAX_BYTES = 25 * 1024 * 1024; // 전사 API 한 번 한도

type Job = { id: string; stage: string; stage_state: string; retry_count: number };

const reply = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });

Deno.serve(async (req) => {
  if (req.method !== "POST") return reply(405, { error: "POST만 받아요" });
  const db = createClient(SUPABASE_URL, SERVICE_KEY, { auth: { persistSession: false } });

  // 호출한 사람 확인(서버 키로 처리하므로 소유자 검사를 코드로 직접 한다, Design §4.7)
  const token = (req.headers.get("Authorization") ?? "").replace(/^Bearer\s+/i, "");
  const { data: who } = await db.auth.getUser(token);
  const uid = who?.user?.id;
  if (!uid) return reply(401, { error: "로그인이 필요해요" });

  let recordId = "";
  try { recordId = String((await req.json()).record_id ?? ""); } catch { /* 아래에서 거부 */ }
  if (!/^[0-9a-f-]{36}$/i.test(recordId)) return reply(400, { error: "기록 ID가 올바르지 않아요" });

  const { data: job } = await db.from("processing_job").select("id,stage,stage_state,retry_count")
    .eq("source_record_id", recordId).eq("owner_id", uid).maybeSingle<Job>();
  if (!job) return reply(404, { error: "처리할 작업이 없어요" });
  if (job.stage === "REVIEWABLE") return reply(200, { stage: "REVIEWABLE", state: "DONE" });
  if (!OPENAI_KEY) return reply(200, { stage: job.stage, state: "WAITING_KEY", error: "AI 키가 아직 설정되지 않았어요" });
  if (job.stage_state === "FAILED" && job.retry_count >= 3) return reply(200, { stage: job.stage, state: "FAILED", error: "세 번 실패해 멈췄어요" });

  // 예산: 이번 달 확정 비용이 대기선을 넘으면 새 유료 작업을 기다리게 한다(NFR-07)
  const cycle = new Date().toISOString().slice(0, 7);
  const { data: setting } = await db.from("settings").select("budget_wait_krw").eq("user_id", uid).single();
  const { data: spent } = await db.from("cost_ledger").select("confirmed_krw").eq("owner_id", uid).eq("billing_cycle", cycle);
  const spentKrw = (spent ?? []).reduce((n, r) => n + (r.confirmed_krw ?? 0), 0);
  if (spentKrw >= (setting?.budget_wait_krw ?? 24000)) {
    await db.from("processing_job").update({ stage_state: "BUDGET_WAIT", updated_at: new Date().toISOString() }).eq("id", job.id);
    return reply(200, { stage: job.stage, state: "BUDGET_WAIT" });
  }
  if (job.stage_state === "BUDGET_WAIT") {
    await db.from("processing_job").update({ stage_state: "PENDING" }).eq("id", job.id);
  }

  const { data: claimed } = await db.rpc("wb_claim_job", { p_job_id: job.id });
  if (!claimed) return reply(200, { stage: job.stage, state: "RUNNING" });   // 다른 호출이 처리 중

  try {
    if (job.stage === "STT") await runStt(db, uid, recordId, job, cycle);
    else await runExtract(db, uid, recordId, job, cycle);
    const { data: after } = await db.from("processing_job").select("stage,stage_state,last_error").eq("id", job.id).single();
    return reply(200, { stage: after?.stage, state: after?.stage_state, error: after?.last_error ?? undefined });
  } catch (e) {
    const message = String((e as Error)?.message ?? e).slice(0, 300);
    await db.from("processing_job").update({
      stage_state: "FAILED", retry_count: job.retry_count + 1, last_error: message, lease_until: null, updated_at: new Date().toISOString(),
    }).eq("id", job.id);
    return reply(200, { stage: job.stage, state: "FAILED", error: message });
  }
});

async function addCost(db: SupabaseClient, uid: string, cycle: string, usd: number) {
  await db.from("cost_ledger").insert({ owner_id: uid, billing_cycle: cycle, confirmed_krw: Math.max(1, Math.ceil(usd * KRW_PER_USD)) });
}

// 내 용어집(DEC-33): 회의록·할 일 표기 기준. 없으면 빈 목록
async function loadVocab(db: SupabaseClient, uid: string): Promise<string[]> {
  const { data } = await db.from("vocab_term").select("term").eq("owner_id", uid).order("created_at").limit(200);
  return (data ?? []).map((r) => r.term as string);
}

function splitSentences(text: string): string[] {
  return text.split(/(?<=[.?!])\s+|\n+/).map((s) => s.trim()).filter(Boolean);
}

async function runStt(db: SupabaseClient, uid: string, recordId: string, job: Job, cycle: string) {
  const { data: asset } = await db.from("audio_asset").select("storage_key,bytes,duration_sec")
    .eq("source_record_id", recordId).eq("owner_id", uid).limit(1).single();
  if (!asset) throw new Error("음성 파일 정보가 없어요");
  if (asset.bytes > STT_MAX_BYTES) throw new Error("음성이 25MB(약 100분)를 넘어 나눠서 처리해야 해요(다음 버전)");

  const { data: blob, error } = await db.storage.from("audio").download(asset.storage_key);
  if (error || !blob) throw new Error("음성 파일을 읽지 못했어요");

  const form = new FormData();
  form.append("model", STT_MODEL);
  form.append("language", "ko");
  // 용어집을 받아쓰기 힌트로 주지 않는다(DEC-33 개정): A/B에서 맞던 'ARS'가 'AR 리스트'로 바뀌는 부작용이 있어
  // 원문은 힌트 없이 받아쓰고, 용어 표기는 정리 단계(runExtract)에서만 맞춘다
  form.append("file", new File([blob], "audio.m4a", { type: "audio/mp4" }));
  const r = await fetch("https://api.openai.com/v1/audio/transcriptions", {
    method: "POST", headers: { Authorization: `Bearer ${OPENAI_KEY}` }, body: form,
  });
  const d = await r.json();
  if (!r.ok) throw new Error("전사 실패: " + (d?.error?.message ?? r.status));
  await addCost(db, uid, cycle, ((asset.duration_sec ?? 60) / 60) * STT_USD_PER_MIN);

  const text = String(d.text ?? "").trim();
  if (!text) {
    // 말소리가 없으면 다시 해도 같으므로 재시도하지 않는다
    await db.from("processing_job").update({ stage_state: "FAILED", retry_count: 3, last_error: "녹음에서 말소리를 찾지 못했어요", lease_until: null }).eq("id", job.id);
    return;
  }
  const { data: s } = await db.from("settings").select("retention_transcript_months").eq("user_id", uid).single();
  const expires = new Date();
  expires.setMonth(expires.getMonth() + (s?.retention_transcript_months ?? 6));
  const segments = splitSentences(text).map((t, idx) => ({ idx, text: t }));
  const { error: insErr } = await db.from("transcript_version").insert({
    owner_id: uid, source_record_id: recordId, kind: "stt-original", content: text, segments, retention_expires_at: expires.toISOString(),
  });
  if (insErr) throw new Error("전사 저장 실패");
  await db.from("processing_job").update({
    stage: "EXTRACT", stage_state: "PENDING", retry_count: 0, last_error: null, lease_until: null, updated_at: new Date().toISOString(),
  }).eq("id", job.id);
}

const WEEKDAY = "일월화수목금토";

// 한 주는 월요일에 시작한다(DEC-31: 회의는 대부분 평일). 일요일은 그 주의 마지막 날이라
// 일요일에 말한 "다음 주 화요일"은 바로 다음 날 시작하는 주의 화요일이다. 모델이 헷갈리지 않게 날짜 범위를 직접 준다.
// eval/llm/run.mjs의 weekLine과 같은 규칙(평가·운영 입력을 같게 유지)
function weekLine(ymd: string): string {
  const day = new Date(ymd + "T00:00:00Z");
  const monday = new Date(day.getTime() - ((day.getUTCDay() + 6) % 7) * 86400000);
  const fmt = (offset: number) => {
    const d = new Date(monday.getTime() + offset * 86400000);
    return `${d.toISOString().slice(0, 10)}(${WEEKDAY[d.getUTCDay()]})`;
  };
  return `주 기준(월요일 시작): 이번 주 ${fmt(0)}~${fmt(6)}, 다음 주 ${fmt(7)}~${fmt(13)}`;
}

async function runExtract(db: SupabaseClient, uid: string, recordId: string, job: Job, cycle: string) {
  const { data: rec } = await db.from("source_record").select("title,record_kind,occurred_at,ingested_at,workspace_id")
    .eq("id", recordId).eq("owner_id", uid).single();
  const { data: tv } = await db.from("transcript_version").select("id,segments,retention_expires_at")
    .eq("source_record_id", recordId).eq("owner_id", uid).order("created_at", { ascending: false }).limit(1).single();
  if (!rec || !tv) throw new Error("전사를 찾지 못했어요");
  const segments: { idx: number; text: string }[] = tv.segments ?? [];

  // 이 공간의 진행 중 프로젝트·반복실행만 연결 후보로 준다(다른 공간·아카이브 제외, DEC-22)
  const [{ data: projects }, { data: areas }] = await Promise.all([
    db.from("project").select("id,name,purpose").eq("owner_id", uid).eq("workspace_id", rec.workspace_id).is("archived_at", null),
    db.from("area").select("id,name").eq("owner_id", uid).eq("workspace_id", rec.workspace_id).is("archived_at", null),
  ]);
  const places = [
    ...(projects ?? []).map((p) => ({ id: p.id, line: `- ${p.id}: ${p.name} (프로젝트)${p.purpose ? " — " + p.purpose : ""}` })),
    ...(areas ?? []).map((a) => ({ id: a.id, line: `- ${a.id}: ${a.name} (반복실행)` })),
  ];
  const validIds = new Set(places.map((p) => p.id));
  const vocab = await loadVocab(db, uid);

  // 녹음 시각은 서울 기준 날짜·요일로 준다(기한 계산의 기준)
  const at = new Date(rec.occurred_at ?? rec.ingested_at);
  const seoul = new Date(at.getTime() + 9 * 3600 * 1000);
  const ymd = seoul.toISOString().slice(0, 10), hm = seoul.toISOString().slice(11, 16);
  const user = `기록 종류: ${rec.record_kind}\n녹음 시각: ${ymd} (${WEEKDAY[seoul.getUTCDay()]}) ${hm} Asia/Seoul\n${weekLine(ymd)}\n\n` +
    `공간의 프로젝트·반복실행:\n${places.map((p) => p.line).join("\n") || "- (아직 없음)"}\n\n` +
    (vocab.length ? `내 용어집(표기 기준. 전사에 비슷하게 잘못 들린 말이 있으면 이 표기로 쓴다): ${vocab.join(", ")}\n\n` : "") +
    `전사:\n${segments.map((s) => `[${s.idx}] ${s.text}`).join("\n")}`;

  const r = await fetch("https://api.openai.com/v1/responses", {
    method: "POST",
    headers: { "content-type": "application/json", Authorization: `Bearer ${OPENAI_KEY}` },
    body: JSON.stringify({
      model: LLM_MODEL, instructions: SYSTEM_PROMPT, input: user,
      text: { format: { type: "json_schema", name: "record_extraction", schema: OUTPUT_SCHEMA, strict: true } },
    }),
  });
  const d = await r.json();
  if (!r.ok) throw new Error("분석 실패: " + (d?.error?.message ?? r.status));
  const usage = { input_tokens: d.usage?.input_tokens ?? 0, output_tokens: d.usage?.output_tokens ?? 0 };
  await addCost(db, uid, cycle, (usage.input_tokens * LLM_USD_PER_1M.input + usage.output_tokens * LLM_USD_PER_1M.output) / 1e6);
  const raw = d.output_text ?? d.output?.flatMap((o: { content?: unknown[] }) => o.content ?? [])
    .find((c: { type?: string }) => c.type === "output_text")?.text;
  let out: Record<string, unknown>;
  try { out = JSON.parse(raw); } catch { throw new Error("분석 결과 형식이 맞지 않아요"); }

  // 서버 검증(NFR-08): 근거 구간·프로젝트 ID·담당·기한을 허용 범위로만 받는다. 맞지 않으면 버리거나 '확인 필요'로 낮춘다
  const inRange = (xs: unknown) => (Array.isArray(xs) ? xs : []).filter((i) => Number.isInteger(i) && i >= 0 && i < segments.length) as number[];
  const isDate = (v: unknown) => typeof v === "string" && /^\d{4}-\d{2}-\d{2}$/.test(v);
  const isTime = (v: unknown) => typeof v === "string" && /^\d{2}:\d{2}$/.test(v);
  const actions = (Array.isArray(out.actions) ? out.actions : []).flatMap((a: Record<string, unknown>) => {
    const segs = inRange(a.segments);
    const title = String(a.title ?? "").trim().slice(0, 200);
    if (!title || !segs.length) return [];          // 근거 없는 할 일은 만들지 않는다
    let ownerKind = ["me", "other", "unknown"].includes(a.owner_kind as string) ? a.owner_kind as string : "unknown";
    const assignee = ownerKind === "other" && typeof a.assignee === "string" && a.assignee.trim() ? a.assignee.trim().slice(0, 40) : null;
    if (ownerKind === "other" && !assignee) ownerKind = "unknown";
    if (ownerKind === "me" && rec.record_kind !== "memo") ownerKind = "unknown";   // 회의·통화에서 '나'는 추정 금지(DEC-27)
    const after: Record<string, unknown> = {
      title, owner_kind: ownerKind, assignee,
      project_id: typeof a.project_id === "string" && validIds.has(a.project_id) ? a.project_id : null,
      due_date: null, due_time: null, due_ambiguous: false, due_text: null,
    };
    if (a.due_kind === "date" && isDate(a.due_date)) after.due_date = a.due_date;
    else if (a.due_kind === "datetime" && isDate(a.due_date) && isTime(a.due_time)) {
      after.due_date = a.due_date;
      after.due_time = `${a.due_date}T${a.due_time}:00+09:00`;
    } else if (a.due_kind === "vague") {
      after.due_ambiguous = true;
      after.due_text = typeof a.due_text === "string" ? a.due_text.slice(0, 40) : "확인 필요";
    }
    return [{ after, segs }];
  });

  const { data: run, error: runErr } = await db.from("analysis_run").insert({
    owner_id: uid, input_transcript_version_id: tv.id, model_id: LLM_MODEL, prompt_version: PROMPT_VERSION,
    config_hash: `${LLM_MODEL}:${PROMPT_VERSION}`, status: "done", provider_request_id: d.id ?? null, usage,
  }).select("id").single();
  if (runErr || !run) throw new Error("분석 기록 저장 실패");

  const fields = {
    title: rec.title,
    classification: out.classification,
    decisions: (Array.isArray(out.decisions) ? out.decisions : []).map((x: Record<string, unknown>) => ({ text: String(x.text ?? ""), segments: inRange(x.segments) })),
    open_questions: (Array.isArray(out.open_questions) ? out.open_questions : []).map((x: Record<string, unknown>) => ({ text: String(x.text ?? ""), segments: inRange(x.segments) })),
  };
  const { data: mv, error: mvErr } = await db.from("meeting_version").insert({
    owner_id: uid, transcript_version_id: tv.id, state: "draft", fields, retention_expires_at: tv.retention_expires_at,
  }).select("id").single();
  if (mvErr || !mv) throw new Error("회의록 저장 실패");

  for (const { after, segs } of actions) {
    const { data: p, error: pErr } = await db.from("change_proposal").insert({
      owner_id: uid, analysis_run_id: run.id, target_type: "task", after_json: after,
      rationale: segs.map((i) => segments[i].text).join(" ").slice(0, 300),
    }).select("id").single();
    if (pErr || !p) throw new Error("할 일 후보 저장 실패");
    await db.from("evidence_link").insert({
      owner_id: uid, proposal_id: p.id, meeting_version_id: mv.id, transcript_version_id: tv.id, segment_ref: { idx: segs },
    });
  }
  if (["work", "personal", "uncertain"].includes(out.classification as string)) {
    await db.from("source_record").update({ classification: out.classification }).eq("id", recordId);
  }
  await db.from("processing_job").update({
    stage: "REVIEWABLE", stage_state: "DONE", retry_count: 0, last_error: null, lease_until: null, updated_at: new Date().toISOString(),
  }).eq("id", job.id);
}
