// 비서와 결정(DEC-111, Design §3.21)의 규칙 부분 — 서버 호출 없이 시험할 수 있게 나눈다.
// 사실(출처·근거 문장·미룬 횟수·손댐)은 기록에서 그대로, 제안(첫 2분 동작·조각)은 AI 또는 남은 하위 할 일에서.

export const SECRETARY_PROMPT_VERSION = "secretary-v1";
/** 한 번에 받는 카드 수(앱은 5장씩, 넉넉히) */
export const MAX_TASKS = 10;
/** 동작·조각 한 줄 최대 글자 */
export const STEP_MAX = 40;

export const SECRETARY_PROMPT = `너는 한 가지에 오래 집중하기 어려운 사용자를 돕는 업무 비서다. 사용자가 미뤄 온 할 일마다 두 가지를 만든다.
1) step: 지금 바로 2분 안에 손댈 수 있는 첫 동작 하나. 눈에 보이는 몸 동작(열기·찾기·모으기·적기·보내기·표시하기)으로, 끝났는지 알 수 있게 쓴다. '검토하기·생각하기·정리하기·준비하기'처럼 막연한 말만으로 쓰지 않는다. 30자 안팎, '~하기'로 끝낸다.
2) pieces: 이 할 일을 끝까지 하는 순서대로 2~4조각. 조각마다 30자 안팎, '~하기'로 끝낸다. 첫 조각은 step과 같은 일이어도 된다.
- 팔로업(남이 맡은 일)이면 step은 그 사람에게 진행을 묻거나 확인하는 짧은 동작으로 쓴다. 보낼 메시지 문장은 쓰지 않는다.
- 회의에서 들은 말이 있으면 그 내용에 맞춘다.
- 제목·곳·회의에서 들은 말에 없는 파일 이름·사람 이름·숫자를 지어내지 않는다.
- 높임말·설명 없이 동작만 쓴다.
- n은 입력의 번호를 그대로 쓴다.`;

export const SECRETARY_SCHEMA = {
  type: "object",
  properties: {
    items: {
      type: "array",
      items: {
        type: "object",
        properties: {
          n: { type: "integer" },
          step: { type: "string" },
          pieces: { type: "array", items: { type: "string" } },
        },
        required: ["n", "step", "pieces"],
        additionalProperties: false,
      },
    },
  },
  required: ["items"],
  additionalProperties: false,
} as const;

/** AI에 보낼 할 일 한 개(제목·곳·담당·근거 문장만 — 그 밖의 개인 정보는 보내지 않는다) */
export interface AiTask {
  id: string;
  title: string;
  place: string | null;
  /** 팔로업이면 담당 이름 */
  follow: string | null;
  quote: string | null;
}

export interface Suggestion {
  step: string;
  pieces: string[];
}

export function buildInput(tasks: AiTask[]): string {
  return tasks.map((t, i) =>
    [
      `${i + 1}. 제목: ${t.title}`,
      t.place ? `곳: ${t.place}` : null,
      t.follow ? `팔로업(남이 맡은 일): ${t.follow}` : null,
      t.quote ? `회의에서 들은 말: "${t.quote}"` : null,
    ].filter(Boolean).join(" | ")
  ).join("\n");
}

/** 한 줄 다듬기: 공백 정리·감싼 따옴표 빼기·너무 길면 버림(잘라 쓰면 뜻이 바뀌므로) */
export function cleanLine(v: unknown): string | null {
  if (typeof v !== "string") return null;
  const s = v.replace(/\s+/g, " ").trim().replace(/^["'“”‘’]+|["'“”‘’]+$/g, "").trim();
  if (!s || s.length > STEP_MAX) return null;
  return s;
}

/** AI 응답 → 번호별 제안. 번호가 없거나 겹치거나 동작이 비면 그 할 일은 제안 없음 */
export function cleanSuggestions(raw: unknown, tasks: AiTask[]): Map<string, Suggestion> {
  const out = new Map<string, Suggestion>();
  const items = (raw as { items?: unknown })?.items;
  if (!Array.isArray(items)) return out;
  for (const it of items) {
    const n = (it as { n?: unknown })?.n;
    if (typeof n !== "number" || !Number.isInteger(n) || n < 1 || n > tasks.length) continue;
    const id = tasks[n - 1].id;
    if (out.has(id)) continue;
    const step = cleanLine((it as { step?: unknown }).step);
    if (!step) continue;
    const rawPieces = (it as { pieces?: unknown }).pieces;
    const pieces = [...new Set((Array.isArray(rawPieces) ? rawPieces : []).map(cleanLine).filter((p): p is string => !!p))].slice(0, 4);
    out.set(id, { step, pieces: pieces.length >= 2 ? pieces : [] });
  }
  return out;
}

/** 이력·하위·집중으로 본 '손댐': 만든 뒤 한 번도 바뀌지 않았고 하위도 집중 기록도 없으면 손대지 않은 것 */
export function untouched(revisions: number, children: number, focused: boolean): boolean {
  return revisions <= 1 && children === 0 && !focused;
}

export interface Child {
  id: string;
  title: string;
  done: boolean;
}

/** 안 끝난 하위 할 일이 있으면 AI 없이 그 첫 조각이 첫 동작(남은 하위가 조각) */
export function stepFromChildren(children: Child[]): { step: string; stepTaskId: string; pieces: string[] } | null {
  const open = children.filter((c) => !c.done);
  if (!open.length) return null;
  return { step: open[0].title, stepTaskId: open[0].id, pieces: open.map((c) => c.title) };
}
