// 비서와 결정(DEC-111, Design §3.21): 다시 정할 일 카드마다 비서가 말할 사실과 첫 2분 제안을 한 번에 돌려준다.
// POST { task_ids: string[] } (앱, 로그인 확인) → { cards: Card[], ai: "done" | "none" | "no-key" | "budget" | "key" | "failed" }
// - 사실: 출처 기록 제목·때와 회의에서 들은 말(근거 문장), 미룬 횟수(wb_task_defer_counts), 만든 뒤 손댄 적이 있는지.
// - 제안: 안 끝난 하위 할 일이 있으면 그 첫 조각(AI 없이), 없으면 AI가 첫 2분 동작 1개 + 조각 2~4개.
// AI에는 할 일 제목·곳 이름·팔로업 담당·근거 문장만 보낸다. 제안은 저장하지 않는다(앱이 기기에 잠깐 기억).
// AI가 안 되면(키·예산·실패) 사실만 돌려주고 앱은 '직접 적기'로 둔다. 서버 키로 읽으므로 소유자 검사를 코드로 직접 한다(§4.7).
import { createClient, type SupabaseClient } from "jsr:@supabase/supabase-js@2";
import { withCors } from "../_shared/cors.ts";
import { addCost, billingCycle, LLM_MODEL, llmUsd, overBudget } from "../_shared/ai-cost.ts";
import { accountKind } from "../process-record/ai-error.ts";
import {
  type AiTask,
  buildInput,
  type Child,
  cleanSuggestions,
  MAX_TASKS,
  SECRETARY_PROMPT,
  SECRETARY_PROMPT_VERSION,
  SECRETARY_SCHEMA,
  stepFromChildren,
  type Suggestion,
  untouched,
} from "./rules.ts";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const OPENAI_KEY = Deno.env.get("OPENAI_API_KEY");
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

const reply = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json", "cache-control": "no-store" } });

interface Card {
  task_id: string;
  source: { title: string | null; at: string | null } | null;
  quote: string | null;
  defers: number;
  untouched: boolean;
  step: string | null;
  /** 첫 동작이 하위 할 일이면 그 ID(끝냈다고 하면 앱이 그 하위를 체크) */
  step_task_id: string | null;
  pieces: string[];
}

Deno.serve(withCors(async (req) => {
  if (req.method !== "POST") return reply(405, { error: "POST만 받아요" });
  const db = createClient(SUPABASE_URL, SERVICE_KEY, { auth: { persistSession: false } });
  const token = (req.headers.get("Authorization") ?? "").replace(/^Bearer\s+/i, "");
  const { data: who } = await db.auth.getUser(token);
  const uid = who?.user?.id;
  if (!uid) return reply(401, { error: "로그인이 필요해요" });

  let ids: string[] = [];
  try {
    const body = await req.json();
    const raw: unknown[] = Array.isArray(body.task_ids) ? body.task_ids : [];
    ids = [...new Set(raw.map((v) => String(v)))].filter((s) => UUID.test(s));
  } catch { /* 아래에서 거부 */ }
  if (!ids.length || ids.length > MAX_TASKS) return reply(400, { error: `할 일 1~${MAX_TASKS}개를 보내 주세요` });

  try {
    return reply(200, await cards(db, uid, ids));
  } catch (e) {
    console.error("secretary failed", String(e));
    return reply(500, { error: "비서가 카드를 준비하지 못했어요" });
  }
}));

async function cards(db: SupabaseClient, uid: string, ids: string[]): Promise<{ cards: Card[]; ai: string }> {
  // 내 할 일만(남의 ID는 조용히 뺀다)
  const { data: tasks, error } = await db.from("task")
    .select("id,title,owner_kind,assignee,project_id,area_id").eq("owner_id", uid).in("id", ids);
  if (error) throw error;
  const mine = tasks ?? [];
  if (!mine.length) return { cards: [], ai: "none" };
  const myIds = mine.map((t) => t.id as string);

  const [kids, defers, focus, sources, places] = await Promise.all([
    db.from("task").select("id,title,status,parent_id,created_at").eq("owner_id", uid).in("parent_id", myIds).order("created_at"),
    db.rpc("wb_task_defer_counts", { p_ids: myIds }),
    db.from("focus_session").select("task_id").eq("owner_id", uid).in("task_id", myIds),
    db.from("task_revision")
      .select("task_id,version,approval_event(change_proposal(evidence_link(quote,created_at),analysis_run(transcript_version(source_record(title,occurred_at,ingested_at)))))")
      .eq("owner_id", uid).eq("actor", "ai-approved").in("task_id", myIds),
    placeNames(db, uid, mine),
  ]);

  const childrenOf = new Map<string, Child[]>();
  for (const k of kids.data ?? []) {
    const list = childrenOf.get(k.parent_id) ?? [];
    list.push({ id: k.id, title: k.title, done: k.status === "done" });
    childrenOf.set(k.parent_id, list);
  }
  const deferOf = new Map<string, { defers: number; revisions: number }>(
    ((defers.data ?? []) as { task_id: string; defers: number; revisions: number }[]).map((d) => [d.task_id, d]),
  );
  const focused = new Set((focus.data ?? []).map((f) => f.task_id as string));
  const sourceOf = new Map<string, { title: string | null; at: string | null; quote: string | null }>();
  for (const r of (sources.data ?? []) as unknown as SourceRow[]) {
    const cp = one(one(r.approval_event)?.change_proposal);
    const rec = one(one(one(cp?.analysis_run)?.transcript_version)?.source_record);
    if (!rec || sourceOf.has(r.task_id)) continue;
    const quote = (cp?.evidence_link ?? []).filter((e) => e.quote).sort((a, b) => a.created_at.localeCompare(b.created_at))[0]?.quote ?? null;
    sourceOf.set(r.task_id, { title: rec.title, at: rec.occurred_at ?? rec.ingested_at, quote });
  }

  // 제안: 남은 하위가 있으면 그것, 없으면 AI에 물을 것으로
  const fromKids = new Map(myIds.map((id) => [id, stepFromChildren(childrenOf.get(id) ?? [])]));
  const ask: AiTask[] = mine.filter((t) => !fromKids.get(t.id)).map((t) => ({
    id: t.id, title: t.title, place: places.get(t.id) ?? null,
    follow: t.owner_kind === "other" ? (t.assignee ?? "다른 사람") : null,
    quote: sourceOf.get(t.id)?.quote ?? null,
  }));
  const { suggestions, ai } = await suggest(db, uid, ask);

  // 요청한 순서대로
  const byId = new Map(mine.map((t) => [t.id as string, t]));
  const out: Card[] = ids.filter((id) => byId.has(id)).map((id) => {
    const src = sourceOf.get(id);
    const d = deferOf.get(id);
    const kid = fromKids.get(id);
    const s = suggestions.get(id);
    return {
      task_id: id,
      source: src ? { title: src.title, at: src.at } : null,
      quote: src?.quote ?? null,
      defers: d?.defers ?? 0,
      untouched: untouched(d?.revisions ?? 1, childrenOf.get(id)?.length ?? 0, focused.has(id)),
      step: kid?.step ?? s?.step ?? null,
      step_task_id: kid?.stepTaskId ?? null,
      pieces: kid?.pieces ?? s?.pieces ?? [],
    };
  });
  return { cards: out, ai };
}

async function suggest(db: SupabaseClient, uid: string, ask: AiTask[]): Promise<{ suggestions: Map<string, Suggestion>; ai: string }> {
  const none = new Map<string, Suggestion>();
  if (!ask.length) return { suggestions: none, ai: "none" };
  if (!OPENAI_KEY) return { suggestions: none, ai: "no-key" };
  const cycle = billingCycle();
  if (await overBudget(db, uid, cycle)) return { suggestions: none, ai: "budget" };
  try {
    const r = await fetch("https://api.openai.com/v1/responses", {
      method: "POST",
      headers: { "content-type": "application/json", Authorization: `Bearer ${OPENAI_KEY}` },
      body: JSON.stringify({
        model: LLM_MODEL, instructions: SECRETARY_PROMPT, input: buildInput(ask),
        text: { format: { type: "json_schema", name: "secretary_steps", schema: SECRETARY_SCHEMA, strict: true } },
      }),
    });
    const d = await r.json();
    if (!r.ok) {
      // 키 만료·크레딧 없음은 앱이 '직접 적기'로 두고 다른 곳(기록)의 안내를 따르면 된다
      if (accountKind(r.status, d)) return { suggestions: none, ai: "key" };
      throw new Error(`secretary ${r.status}`);
    }
    await addCost(db, uid, cycle, llmUsd(d.usage));
    // 사용량·지시문 판은 함수 로그로만(제목·제안 글은 남기지 않는다, NFR-09)
    console.log(JSON.stringify({ secretary: SECRETARY_PROMPT_VERSION, model: LLM_MODEL, usage: d.usage ?? null, tasks: ask.length }));
    const raw = d.output_text ?? d.output?.flatMap((o: { content?: unknown[] }) => o.content ?? [])
      .find((c: { type?: string }) => c.type === "output_text")?.text;
    return { suggestions: cleanSuggestions(JSON.parse(raw), ask), ai: "done" };
  } catch (e) {
    console.error("secretary ai failed", String(e));
    return { suggestions: none, ai: "failed" };
  }
}

/** 할 일 → 곳 이름(프로젝트·반복실행). 하위는 상위와 같은 곳이라 따로 읽지 않는다 */
async function placeNames(db: SupabaseClient, uid: string, tasks: { id: string; project_id: string | null; area_id: string | null }[]) {
  const pIds = [...new Set(tasks.map((t) => t.project_id).filter(Boolean))] as string[];
  const aIds = [...new Set(tasks.map((t) => t.area_id).filter(Boolean))] as string[];
  const [p, a] = await Promise.all([
    pIds.length ? db.from("project").select("id,name").eq("owner_id", uid).in("id", pIds) : Promise.resolve({ data: [] }),
    aIds.length ? db.from("area").select("id,name").eq("owner_id", uid).in("id", aIds) : Promise.resolve({ data: [] }),
  ]);
  const name = new Map<string, string>([...(p.data ?? []), ...(a.data ?? [])].map((r) => [r.id as string, r.name as string]));
  return new Map(tasks.map((t) => [t.id, name.get(t.project_id ?? t.area_id ?? "") ?? null] as [string, string | null]));
}

// PostgREST는 하나짜리 관계를 객체로, 거꾸로 관계를 배열로 준다
type OneOrMany<T> = T | T[] | null | undefined;
const one = <T>(v: OneOrMany<T>): T | undefined => Array.isArray(v) ? v[0] : (v ?? undefined);

interface SourceRow {
  task_id: string;
  approval_event: OneOrMany<{
    change_proposal: OneOrMany<{
      evidence_link: { quote: string | null; created_at: string }[] | null;
      analysis_run: OneOrMany<{
        transcript_version: OneOrMany<{ source_record: OneOrMany<{ title: string | null; occurred_at: string | null; ingested_at: string | null }> }>;
      }>;
    }>;
  }>;
}
