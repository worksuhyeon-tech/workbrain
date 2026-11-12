// 업무계획(FR-33) 자료 읽기: 서버 키로 읽으므로 모든 조회에 owner_id = 호출한 사람을 건다(Design §4.7).
// 두 칸에 들어갈 수 있는 것만 읽는다 — 전주 칸에 끝낸 일, 두 칸 기간 안 기한의 안 끝난 일, 전주 칸 기간의 회의·통화.
// 권한: task·task_revision(task_id·version·approval_event_id)·approval_event(id·proposal_id)·project·area·settings·
// project_state_version·source_record·source_project_link는 처리 함수에 이미 열려 있다(0012·0017·0021·0033·0037). 새 권한 없음
// 하위 할 일(FR-23a, 0040): 하위가 칸에 들면 그 상위도 읽고, 상위마다 하위 개수(전체)를 센다. task.parent_id는 처리 함수의 task SELECT 권한에 들어 있다
import type { SupabaseClient } from "jsr:@supabase/supabase-js@2";
import { periods, type PlanInput, type PlanKind, type PlanMeeting, type PlanPlace, type PlanTask } from "../_shared/work-plan.ts";

const TASK_COLUMNS = "id,title,status,owner_kind,assignee,due_date,due_time,due_ambiguous,due_text,done_at,project_id,area_id,parent_id";

/** 서울 날짜 하루의 시작 시각(UTC ISO) */
const seoulStart = (date: string) => new Date(`${date}T00:00:00+09:00`).toISOString();
const nextDay = (date: string) => new Date(Date.parse(`${date}T00:00:00Z`) + 86400000).toISOString().slice(0, 10);

type TaskRow = {
  id: string; title: string; status: string; owner_kind: string; assignee: string | null;
  due_date: string | null; due_time: string | null; due_ambiguous: boolean | null; due_text: string | null;
  done_at: string | null; project_id: string | null; area_id: string | null; parent_id: string | null;
};
type SourceRow = {
  task_id: string;
  approval_event: { change_proposal: { analysis_run: { transcript_version: { source_record: {
    id: string; title: string | null; occurred_at: string | null; ingested_at: string;
  } | null } | null } | null } | null } | null;
};

export async function loadPlanInput(db: SupabaseClient, uid: string, kind: PlanKind, baseDate: string): Promise<PlanInput> {
  const p = periods(kind, baseDate);
  const prevFrom = seoulStart(p.previous.start), prevTo = seoulStart(nextDay(p.previous.end));
  const nextTo = seoulStart(nextDay(p.next.end));

  const [settings, projects, areas, states, doneTasks, dueTasks, timedTasks, recOccurred, recIngested] = await Promise.all([
    db.from("settings").select("my_names").eq("user_id", uid).maybeSingle(),
    db.from("project").select("id,name,archived_at,current_state_version").eq("owner_id", uid),
    db.from("area").select("id,name,archived_at").eq("owner_id", uid),
    db.from("project_state_version").select("project_id,version,summary").eq("owner_id", uid),
    // 전주 칸: 그 기간에 끝낸 일
    db.from("task").select(TASK_COLUMNS).eq("owner_id", uid).eq("status", "done").gte("done_at", prevFrom).lt("done_at", prevTo),
    // 두 칸: 기간 안 기한의 안 끝난 일(날짜만 기한)
    db.from("task").select(TASK_COLUMNS).eq("owner_id", uid).neq("status", "done")
      .gte("due_date", p.previous.start).lte("due_date", p.next.end),
    // 시각만 있는 기한
    db.from("task").select(TASK_COLUMNS).eq("owner_id", uid).neq("status", "done").is("due_date", null)
      .gte("due_time", prevFrom).lt("due_time", nextTo),
    // 전주 칸 회의·통화(회의 시각이 없으면 올린 시각)
    db.from("source_record").select("id,title,record_kind,occurred_at,ingested_at,classification").eq("owner_id", uid)
      .is("archived_at", null).in("record_kind", ["meeting", "call"]).gte("occurred_at", prevFrom).lt("occurred_at", prevTo),
    db.from("source_record").select("id,title,record_kind,occurred_at,ingested_at,classification").eq("owner_id", uid)
      .is("archived_at", null).in("record_kind", ["meeting", "call"]).is("occurred_at", null).gte("ingested_at", prevFrom).lt("ingested_at", prevTo),
  ]);
  for (const r of [settings, projects, areas, states, doneTasks, dueTasks, timedTasks, recOccurred, recIngested]) {
    if (r.error) throw new Error("업무계획 자료를 읽지 못함: " + r.error.message);
  }

  const summaries = new Map<string, string>();
  const currentOf = new Map((projects.data ?? []).map((pr) => [pr.id as string, pr.current_state_version as number | null]));
  for (const s of states.data ?? []) {
    if (s.summary && currentOf.get(s.project_id) === s.version) summaries.set(s.project_id, s.summary);
  }
  const places: PlanPlace[] = [
    ...(projects.data ?? []).map((pr) => ({
      id: pr.id, type: "project" as const, name: pr.name, archived: !!pr.archived_at, stateSummary: summaries.get(pr.id) ?? null,
    })),
    ...(areas.data ?? []).map((a) => ({ id: a.id, type: "area" as const, name: a.name, archived: !!a.archived_at, stateSummary: null })),
  ];

  const taskRows = new Map<string, TaskRow>();
  for (const t of [...(doneTasks.data ?? []), ...(dueTasks.data ?? []), ...(timedTasks.data ?? [])] as TaskRow[]) taskRows.set(t.id, t);
  // 칸에 든 하위의 상위(자기는 칸에 안 들어도 '하위 N건 중 M건' 줄로 나간다)
  const missingParents = [...new Set([...taskRows.values()].map((t) => t.parent_id).filter((id): id is string => !!id && !taskRows.has(id)))];
  if (missingParents.length) {
    const { data, error } = await db.from("task").select(TASK_COLUMNS).eq("owner_id", uid).in("id", missingParents);
    if (error) throw new Error("상위 할 일을 읽지 못함: " + error.message);
    for (const t of (data ?? []) as TaskRow[]) taskRows.set(t.id, t);
  }
  // 상위마다 하위 개수(기간과 상관없이 전체)
  const subCounts = new Map<string, { total: number; done: number }>();
  const parentIds = [...taskRows.values()].filter((t) => !t.parent_id).map((t) => t.id);
  if (parentIds.length) {
    const { data, error } = await db.from("task").select("parent_id,status").eq("owner_id", uid).in("parent_id", parentIds);
    if (error) throw new Error("하위 할 일을 읽지 못함: " + error.message);
    for (const k of (data ?? []) as { parent_id: string; status: string }[]) {
      const c = subCounts.get(k.parent_id) ?? { total: 0, done: 0 };
      c.total++;
      if (k.status === "done") c.done++;
      subCounts.set(k.parent_id, c);
    }
  }

  // 할 일 출처: AI 제안을 승인해 생긴 할 일만 승인 기록 → 제안 → 전사 → 기록으로 따라간다(앱 Para.sources와 같은 길)
  const sources = new Map<string, PlanTask["source"]>();
  if (taskRows.size) {
    const { data, error } = await db.from("task_revision")
      .select("task_id,approval_event(change_proposal(analysis_run(transcript_version(source_record(id,title,occurred_at,ingested_at)))))")
      .in("task_id", [...taskRows.keys()]).not("approval_event_id", "is", null);
    if (error) throw new Error("할 일 출처를 읽지 못함: " + error.message);
    for (const r of (data ?? []) as unknown as SourceRow[]) {
      const rec = r.approval_event?.change_proposal?.analysis_run?.transcript_version?.source_record;
      if (rec && !sources.has(r.task_id)) sources.set(r.task_id, { recordId: rec.id, title: rec.title, at: rec.occurred_at ?? rec.ingested_at });
    }
  }
  const tasks: PlanTask[] = [...taskRows.values()].map((t) => ({
    id: t.id, title: t.title, status: t.status, ownerKind: t.owner_kind, assignee: t.assignee,
    dueDate: t.due_date, dueTime: t.due_time, dueAmbiguous: !!t.due_ambiguous, dueText: t.due_text, doneAt: t.done_at,
    placeId: t.project_id ?? t.area_id, source: sources.get(t.id) ?? null,
    parentId: t.parent_id, subtasks: subCounts.get(t.id) ?? null,
  }));

  // 회의: 개인으로 분류한 기록은 빼고, 사용자가 확정한 기록 전체 연결만(앱 트리·관련 기록과 같은 기준)
  const records = [...(recOccurred.data ?? []), ...(recIngested.data ?? [])].filter((r) => r.classification !== "personal");
  const linksByRecord = new Map<string, string[]>();
  if (records.length) {
    const { data, error } = await db.from("source_project_link").select("source_record_id,project_id")
      .eq("owner_id", uid).eq("status", "confirmed").eq("item_ref->>scope", "record").in("source_record_id", records.map((r) => r.id));
    if (error) throw new Error("기록 연결을 읽지 못함: " + error.message);
    for (const l of data ?? []) linksByRecord.set(l.source_record_id, [...(linksByRecord.get(l.source_record_id) ?? []), l.project_id]);
  }
  const meetings: PlanMeeting[] = records.flatMap((r) => {
    const projectIds = linksByRecord.get(r.id) ?? [];
    return projectIds.length
      ? [{ id: r.id, title: r.title, at: r.occurred_at ?? r.ingested_at, kind: r.record_kind === "call" ? "call" as const : "meeting" as const, projectIds }]
      : [];
  });

  const names = (settings.data?.my_names ?? []) as string[];
  return { kind, baseDate, ownerName: names[0] ?? "", places, tasks, meetings };
}
