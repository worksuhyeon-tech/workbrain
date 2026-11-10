// 주간·월간 업무계획(FR-33, DEC-81·87, Design §3.13): 할 일·회의·현재 상태로 회사 양식 4칸 표의 '전주 | 금주' 칸 글을 규칙으로 만든다.
// AI를 부르지 않는다(0원). 숫자·결론은 지어내지 않고, 사용자가 Word에서 고친다.
// 앱 미리보기(JSON)와 Word 파일이 같은 결과를 쓰도록 이 순수 함수 하나로 만든다. 나중에 PC 웹·MCP도 이걸 쓴다.

export type PlanKind = "weekly" | "monthly";

export type PlanPlace = {
  id: string;
  type: "project" | "area";
  name: string;
  archived: boolean;
  /** 프로젝트 현재 상태 요약(FR-09). 반복실행·상태 없음은 null */
  stateSummary: string | null;
};

export type PlanTask = {
  id: string;
  title: string;
  status: string;                 // open · doing · done
  ownerKind: string;              // me · other(팔로업) · unknown
  assignee: string | null;
  dueDate: string | null;         // YYYY-MM-DD(날짜만 기한은 날짜 그대로)
  dueTime: string | null;         // ISO(시각 있는 기한)
  dueAmbiguous: boolean;
  dueText: string | null;
  doneAt: string | null;          // ISO
  placeId: string | null;         // 프로젝트나 반복실행. 없으면 '정리 안 됨'이라 넣지 않는다(DEC-87)
  /** AI 제안을 승인해 생긴 할 일의 출처 기록. 직접 추가는 null */
  source: { recordId: string; title: string | null; at: string } | null;
};

export type PlanMeeting = {
  id: string;
  title: string | null;
  at: string;                     // ISO(회의 시각, 없으면 올린 시각)
  kind: "meeting" | "call";
  projectIds: string[];           // 사용자가 확정한 연결만
};

export type PlanInput = {
  kind: PlanKind;
  baseDate: string;               // 서울 날짜 YYYY-MM-DD. 이 날이 속한 주(월)가 '전주(전월)' 칸
  ownerName: string;
  places: PlanPlace[];
  tasks: PlanTask[];
  meetings: PlanMeeting[];
};

/**
 * 칸 안의 한 줄. head = □ 대주제(굵게) · item = - 소주제 · note = * 부연(작은 파란 글씨). 글자 크기는 Word에서 주간·월간이 다르다.
 * head에는 어느 프로젝트·반복실행인지(placeId)를 달아 □ 줄을 AI 한 줄로 바꿀 때 쓴다(DEC-88)
 */
export type PlanLine = { level: "head" | "item" | "note"; text: string; placeId?: string };

export type PlanCell = {
  label: string;                  // 표 머리 '전주 (11.10 ~ 11.14)'
  start: string;                  // 포함 시작일 YYYY-MM-DD
  end: string;                    // 포함 끝일(주간은 일요일까지, 월간은 말일)
  lines: PlanLine[];
};

export type WorkPlan = {
  kind: PlanKind;
  title: string;
  ownerName: string;
  previous: PlanCell;             // 전주·전월 = 이번 주·이번 달(화요일 제출 기준, DEC-81 ③)
  next: PlanCell;                 // 금주·금월 = 다음 주·다음 달
};

const SEOUL_MS = 9 * 3600 * 1000;
const DAY_MS = 24 * 3600 * 1000;

/** ISO 시각 → 서울 날짜 YYYY-MM-DD */
export function seoulDate(iso: string): string {
  return new Date(Date.parse(iso) + SEOUL_MS).toISOString().slice(0, 10);
}

function addDays(date: string, days: number): string {
  return new Date(Date.parse(`${date}T00:00:00Z`) + days * DAY_MS).toISOString().slice(0, 10);
}

/** 'YYYY-MM-DD' → 'M.D'(표 머리) */
const dotted = (date: string) => `${Number(date.slice(5, 7))}.${Number(date.slice(8, 10))}`;
/** 'YYYY-MM-DD' → 'M/D'(줄 안 날짜) */
const slashed = (date: string) => `${Number(date.slice(5, 7))}/${Number(date.slice(8, 10))}`;

/**
 * 두 칸의 기간. 주간: 기준일이 속한 주의 월요일부터 — 표 머리는 월~금, 포함은 일요일까지(주말에 끝낸 일도 그 주 실적).
 * 월간: 기준일이 속한 달 1일~말일과 다음 달
 */
export function periods(kind: PlanKind, baseDate: string): { previous: Omit<PlanCell, "lines">; next: Omit<PlanCell, "lines"> } {
  if (kind === "weekly") {
    const weekday = new Date(`${baseDate}T00:00:00Z`).getUTCDay(); // 0 일 ~ 6 토
    const monday = addDays(baseDate, -((weekday + 6) % 7));
    const nextMonday = addDays(monday, 7);
    return {
      previous: { label: `전주 (${dotted(monday)} ~ ${dotted(addDays(monday, 4))})`, start: monday, end: addDays(monday, 6) },
      next: { label: `금주 (${dotted(nextMonday)} ~ ${dotted(addDays(nextMonday, 4))})`, start: nextMonday, end: addDays(nextMonday, 6) },
    };
  }
  const first = `${baseDate.slice(0, 7)}-01`;
  const nextFirst = new Date(Date.UTC(Number(baseDate.slice(0, 4)), Number(baseDate.slice(5, 7)), 1)).toISOString().slice(0, 10);
  const afterNext = new Date(Date.UTC(Number(nextFirst.slice(0, 4)), Number(nextFirst.slice(5, 7)), 1)).toISOString().slice(0, 10);
  const last = addDays(nextFirst, -1), nextLast = addDays(afterNext, -1);
  return {
    previous: { label: `전월 (${dotted(first)} ~ ${dotted(last)})`, start: first, end: last },
    next: { label: `금월 (${dotted(nextFirst)} ~ ${dotted(nextLast)})`, start: nextFirst, end: nextLast },
  };
}

const within = (date: string | null, cell: { start: string; end: string }) => !!date && date >= cell.start && date <= cell.end;

/** 기한 날짜(날짜만 기한은 그대로, 시각 있는 기한은 서울 날짜) */
function dueOf(t: PlanTask): string | null {
  return t.dueDate ?? (t.dueTime ? seoulDate(t.dueTime) : null);
}

/** 현재 상태 요약의 첫 문장. 개조식이라 끝 마침표는 뺀다 */
export function firstSentence(summary: string | null): string | null {
  const text = (summary ?? "").replace(/\s+/g, " ").trim();
  if (!text) return null;
  const m = text.match(/^(.+?[.!?。])(\s|$)/);
  return (m ? m[1] : text).replace(/[.。]$/, "").trim() || null;
}

// 괄호 안 띄어쓰기는 줄바꿈 없는 공백: 좁은 칸에서 '(완료 / 11/10)'처럼 갈라지지 않고 괄호째 다음 줄로 넘어간다(견본 확인)
const NBSP = "\u00A0";
const tail = (parts: string[]) => `(${parts.join(" ").replace(/ /g, NBSP)})`;

/**
 * 할 일 한 줄. kind: done = 그 기간에 끝냄 '(완료 11/11)' · previous = 전주 칸의 안 끝난 일 · next = 금주 칸.
 * 전주 칸은 실적 칸이라 그 주 예정 일은 예정일에 끝낸다고 보고 날짜만 '(11/9)'(2026-11-11 사용자 결정, 지난 기한이 '예정'으로 보이던 것).
 * 금주 칸은 '(11/18 예정)'. 팔로업은 회사 보고 표기대로 '(w.박지훈, 11/14 예정)'(DEC-88)
 */
function taskLine(t: PlanTask, kind: "done" | "previous" | "next"): string {
  const due = dueOf(t);
  const date = t.dueAmbiguous && t.dueText ? t.dueText.trim() : due ? slashed(due) : "";
  const when = kind === "done"
    ? `완료 ${slashed(seoulDate(t.doneAt!))}`
    : kind === "previous" ? date : date ? `${date} 예정` : "예정";
  const who = t.ownerKind === "other" && t.assignee ? [`w.${t.assignee.trim()},`] : [];
  return `${t.title.trim()} ${tail([...who, when])}`;
}

/** 회의 한 줄: '- 접수 화면 1차 리뷰 (11/12 회의)'. 제목에 이미 회의·통화·미팅이 있으면 날짜만('IT 개발팀 통화 (11/13)') */
function meetingLine(m: PlanMeeting): string {
  const title = (m.title ?? "제목 없는 기록").trim();
  const word = /회의|통화|미팅/.test(title) ? [] : [m.kind === "call" ? "통화" : "회의"];
  return `${title} ${tail([slashed(seoulDate(m.at)), ...word])}`;
}

const byKo = (a: string, b: string) => a.localeCompare(b, "ko");

export function buildWorkPlan(input: PlanInput): WorkPlan {
  const p = periods(input.kind, input.baseDate);
  const places = [...input.places].sort((a, b) =>
    a.type === b.type ? byKo(a.name, b.name) : a.type === "project" ? -1 : 1
  );
  const tasksOf = (placeId: string) => input.tasks.filter((t) => t.placeId === placeId);

  const previous: PlanLine[] = [];
  const next: PlanLine[] = [];
  for (const place of places) {
    const mine = tasksOf(place.id);
    // 전주 칸: 그 주에 끝낸 일(보관한 곳도 실적이라 넣음) + 그 주 기한인데 안 끝난 일(보관한 곳은 뺌)
    const done = mine.filter((t) => t.status === "done" && t.doneAt && within(seoulDate(t.doneAt), p.previous))
      .sort((a, b) => a.doneAt!.localeCompare(b.doneAt!));
    const planned = place.archived ? [] : mine.filter((t) => t.status !== "done" && within(dueOf(t), p.previous))
      .sort((a, b) => dueOf(a)!.localeCompare(dueOf(b)!));
    const shownSources = new Set([...done, ...planned].map((t) => t.source?.recordId).filter(Boolean));
    // 그 주 회의 중 이 칸 할 일의 출처로 이미 보이는 회의는 '*'로만, 아니면 따로 한 줄(DEC-87)
    const meetings = place.type === "project"
      ? input.meetings.filter((m) => m.projectIds.includes(place.id) && within(seoulDate(m.at), p.previous) && !shownSources.has(m.id))
        .sort((a, b) => a.at.localeCompare(b.at))
      : [];
    const prevLines: PlanLine[] = [];
    const noted = new Set<string>();
    for (const t of done) pushTask(prevLines, t, "done", noted);
    for (const t of planned) pushTask(prevLines, t, "previous", noted);
    for (const m of meetings) prevLines.push({ level: "item", text: meetingLine(m) });
    if (prevLines.length) {
      // □ 줄은 우선 규칙으로(이름 : 현재 상태 첫 문장). AI가 이름+진행 상태 한 줄로 다듬으면 applyHeads로 바꾼다(DEC-88)
      previous.push({ level: "head", text: fallbackHead(place), placeId: place.id });
      previous.push(...prevLines);
    }

    // 금주 칸: 다음 주 기한인데 안 끝난 일(보관한 곳은 뺌)
    const upcoming = place.archived ? [] : mine.filter((t) => t.status !== "done" && within(dueOf(t), p.next))
      .sort((a, b) => dueOf(a)!.localeCompare(dueOf(b)!));
    if (upcoming.length) {
      next.push({ level: "head", text: place.name.trim(), placeId: place.id });
      const nextNoted = new Set<string>();
      for (const t of upcoming) pushTask(next, t, "next", nextNoted);
    }
  }

  const name = input.kind === "weekly" ? "주간 업무계획" : "월간 업무계획";
  return {
    kind: input.kind,
    title: `${name} (${dotted(p.previous.start)} ~ ${dotted(addDays(p.next.end, input.kind === "weekly" ? -2 : 0))})`,
    ownerName: input.ownerName.trim(),
    previous: { ...p.previous, lines: previous },
    next: { ...p.next, lines: next },
  };
}

/**
 * 할 일 줄 + 출처 회의가 있으면 그 아래 '* 11/12 회의 제목'. 같은 묶음(□ 하나) 안에서 같은 회의는 처음 한 번만 적는다
 * (견본에서 할 일마다 같은 출처가 되풀이돼 제출 문서가 지저분했음)
 */
function pushTask(lines: PlanLine[], t: PlanTask, kind: "done" | "previous" | "next", noted: Set<string>) {
  lines.push({ level: "item", text: taskLine(t, kind) });
  if (t.source && !noted.has(t.source.recordId)) {
    noted.add(t.source.recordId);
    lines.push({ level: "note", text: `${slashed(seoulDate(t.source.at))} ${(t.source.title ?? "기록").trim()}` });
  }
}

/** AI를 못 쓸 때의 □ 줄(0원): 현재 상태가 있으면 '이름 : 첫 문장', 없으면 이름 */
export function fallbackHead(place: PlanPlace): string {
  const lead = place.type === "project" ? firstSentence(place.stateSummary) : null;
  return lead ? `${place.name.trim()} : ${lead}` : place.name.trim();
}

/** □ 줄 AI 다듬기 입력(전주 칸에 나온 곳만): 이름 · 현재 상태 전체 · 그 주 줄들 */
export type HeadInput = { id: string; name: string; state: string | null; lines: string[] };

export function headInputs(plan: WorkPlan, places: PlanPlace[]): HeadInput[] {
  const byId = new Map(places.map((p) => [p.id, p]));
  const out: HeadInput[] = [];
  let cur: HeadInput | null = null;
  for (const line of plan.previous.lines) {
    if (line.level === "head") {
      const place = line.placeId ? byId.get(line.placeId) : undefined;
      cur = place ? { id: place.id, name: place.name.trim(), state: place.stateSummary?.trim() || null, lines: [] } : null;
      if (cur) out.push(cur);
    } else if (cur) {
      cur.lines.push(`${line.level === "item" ? "-" : "*"} ${line.text.replace(/\u00A0/g, " ")}`);
    }
  }
  return out;
}

/** 전주 칸 □ 줄을 AI 한 줄로 바꾼다. 없는 곳은 규칙 줄 그대로. 금주 칸 □는 이름 그대로 */
export function applyHeads(plan: WorkPlan, heads: Map<string, string>): WorkPlan {
  const lines = plan.previous.lines.map((l) =>
    l.level === "head" && l.placeId && heads.has(l.placeId) ? { ...l, text: heads.get(l.placeId)! } : l
  );
  return { ...plan, previous: { ...plan.previous, lines } };
}

/**
 * 앱이 Word로 받으려고 돌려보낸 계획이 맞는 모양인지(미리보기와 같은 글로 Word를 만들어 AI를 두 번 부르지 않게 하려고 받는다).
 * 내 글이지만 크기는 제한한다: 제목 120자·이름 40자·칸 머리 40자·칸마다 500줄·줄 400자. 어긋나면 null
 */
export function checkPlan(raw: unknown): WorkPlan | null {
  const p = raw as Partial<WorkPlan> | null;
  const str = (v: unknown, max: number) => typeof v === "string" && v.length <= max;
  const cell = (c: unknown): c is PlanCell => {
    const x = c as Partial<PlanCell> | null;
    return !!x && str(x.label, 40) && str(x.start, 10) && str(x.end, 10) && Array.isArray(x.lines) && x.lines.length <= 500 &&
      x.lines.every((l) => !!l && ["head", "item", "note"].includes(l.level) && str(l.text, 400));
  };
  if (!p || (p.kind !== "weekly" && p.kind !== "monthly") || !str(p.title, 120) || !str(p.ownerName, 40)) return null;
  if (!cell(p.previous) || !cell(p.next)) return null;
  return {
    kind: p.kind, title: p.title!, ownerName: p.ownerName!,
    previous: { ...p.previous, lines: p.previous.lines.map(({ level, text }) => ({ level, text })) },
    next: { ...p.next, lines: p.next.lines.map(({ level, text }) => ({ level, text })) },
  };
}
