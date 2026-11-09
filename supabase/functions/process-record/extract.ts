// 할 일 정리(EXTRACT) 입력 만들기와 출력 검증 도우미. 처리 함수와 평가 스크립트가 같이 써서 입력이 어긋나지 않게 한다.
// 화자 이름(DEC-76): 가져온 txt(클로바·삼성)의 화자는 용어 교정 뒤에도 발언 줄마다 남는다(runCorrect가 줄 수를 지킴).
// 받는 사람(DEC-77): 할 일 결과를 받는 사람·팀.

export const WEEKDAY = "일월화수목금토";

// 한 주는 월요일에 시작한다(DEC-31: 회의는 대부분 평일). 일요일은 그 주의 마지막 날이라
// 일요일에 말한 "다음 주 화요일"은 바로 다음 날 시작하는 주의 화요일이다. 모델이 헷갈리지 않게 날짜 범위를 직접 준다.
// eval/llm/run.mjs의 weekLine과 같은 규칙(평가·운영 입력을 같게 유지)
export function weekLine(ymd: string): string {
  const day = new Date(ymd + "T00:00:00Z");
  const monday = new Date(day.getTime() - ((day.getUTCDay() + 6) % 7) * 86400000);
  const fmt = (offset: number) => {
    const d = new Date(monday.getTime() + offset * 86400000);
    return `${d.toISOString().slice(0, 10)}(${WEEKDAY[d.getUTCDay()]})`;
  };
  return `주 기준(월요일 시작): 이번 주 ${fmt(0)}~${fmt(6)}, 다음 주 ${fmt(7)}~${fmt(13)}`;
}

// "하늘 님", "하늘씨"도 같은 사람으로 본다
export const normName = (s: string) => s.replace(/\s+/g, "").replace(/(님|씨)$/, "").toLowerCase();

/** 번호만 붙은 화자는 누구인지 모른다 */
const NUMBERED = /^(참석자|화자|발화자|speaker)\s*\d+$/i;
// 클로바노트는 녹음한 계정 주인에게 '(나)'를 붙인다
const ME_MARK = /\s*\(나\)\s*$/;

/**
 * 할 일 정리 입력에 붙일 화자 이름. 나면 "나", 이름으로 쓸 수 없으면 null.
 * 번호 화자만 빼고 화자 표시를 이름으로 본다(사용자가 클로바에서 직접 붙인 이름, DEC-76 11/09 사용자 선택).
 */
export function speakerLabel(raw: string | null | undefined, myNames: Set<string>): string | null {
  const label = raw?.trim();
  if (!label || NUMBERED.test(label)) return null;
  if (ME_MARK.test(label)) return "나";
  const name = label.replace(/\s*님$/, "").trim().slice(0, 40);
  if (!name) return null;
  return myNames.has(normName(name)) ? "나" : name;
}

/** 전사 한 줄: 화자 이름을 알면 `[번호] (이름) 문장`, 모르면 `[번호] 문장` */
export function transcriptLine(s: { idx: number; text: string; speaker?: string }, myNames: Set<string>): string {
  const who = speakerLabel(s.speaker, myNames);
  return who ? `[${s.idx}] (${who}) ${s.text}` : `[${s.idx}] ${s.text}`;
}

const MAX_RECIPIENTS = 5;

/** AI가 뽑은 받는 사람(DEC-77): 글자만, 40자 이내, 중복 없이 최대 5명. 내 이름은 '나' */
export function cleanRecipients(raw: unknown, myNames: Set<string>): string[] {
  if (!Array.isArray(raw)) return [];
  const out: string[] = [];
  const seen = new Set<string>();
  for (const r of raw) {
    if (typeof r !== "string") continue;
    let name = r.trim().replace(/\s*님$/, "").slice(0, 40).trim();
    if (!name) continue;
    if (name === "나" || myNames.has(normName(name))) name = "나";
    const key = normName(name);
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(name);
    if (out.length === MAX_RECIPIENTS) break;
  }
  return out;
}

export type Owner = { owner_kind: "me" | "other" | "unknown"; assignee: string | null; bySpeaker: boolean };

/**
 * AI가 정한 담당을 허용 범위로만 받는다(NFR-08).
 * - 회의·통화에서 '나'는 근거 줄 화자가 '나'일 때만(DEC-76). 대화 순서로 추측하지 않는다(DEC-27)
 * - 이름으로 불러 맡긴 일이 내 이름이면 '나'(DEC-49)
 * - bySpeaker: 담당 이름이 근거 문장에 나오지 않고 근거 줄 화자와 같으면 '말한 사람 기준'(후보 칩에 작게 표시)
 */
export function resolveOwner(
  a: { owner_kind?: unknown; assignee?: unknown },
  recordKind: string,
  evidence: { text: string; speaker?: string }[],
  myNames: Set<string>,
): Owner {
  const labels = evidence.map((s) => speakerLabel(s.speaker, myNames)).filter((x): x is string => !!x);
  const meSpoke = labels.includes("나");
  const unknown: Owner = { owner_kind: "unknown", assignee: null, bySpeaker: false };
  const kind = a.owner_kind;
  const name = typeof a.assignee === "string" ? a.assignee.trim().replace(/\s*님$/, "").slice(0, 40).trim() : "";

  if (kind === "me" || (kind === "other" && name === "나")) {
    if (recordKind === "memo") return { owner_kind: "me", assignee: null, bySpeaker: false };
    return meSpoke ? { owner_kind: "me", assignee: null, bySpeaker: true } : unknown;
  }
  if (kind !== "other" || !name) return unknown;
  const key = normName(name);
  const said = normName(evidence.map((s) => s.text).join(" "));
  const bySpeaker = !said.includes(key) && labels.some((l) => normName(l) === key);
  if (myNames.has(key)) return { owner_kind: "me", assignee: null, bySpeaker };
  return { owner_kind: "other", assignee: name, bySpeaker };
}

export type ExtractInput = {
  recordKind: string;
  occurredAt: Date;
  places: { line: string }[];
  attendees: string[];
  vocab: string[];
  segments: { idx: number; text: string; speaker?: string }[];
  myNames: Set<string>;
};

/** 할 일 정리 모델에 보내는 입력(녹음 시각은 서울 날짜·요일, 기한 계산의 기준) */
export function buildExtractInput(x: ExtractInput): string {
  const seoul = new Date(x.occurredAt.getTime() + 9 * 3600 * 1000);
  const ymd = seoul.toISOString().slice(0, 10), hm = seoul.toISOString().slice(11, 16);
  return `기록 종류: ${x.recordKind}\n녹음 시각: ${ymd} (${WEEKDAY[seoul.getUTCDay()]}) ${hm} Asia/Seoul\n${weekLine(ymd)}\n\n` +
    `공간의 프로젝트·반복실행:\n${x.places.map((p) => p.line).join("\n") || "- (아직 없음)"}\n\n` +
    (x.attendees.length ? `참석자(이름 표기 기준. 이 명단만으로 담당을 정하지 않는다): ${x.attendees.join(", ")}\n\n` : "") +
    (x.vocab.length ? `내 용어집(표기 기준. 전사에 비슷하게 잘못 들린 말이 있으면 이 표기로 쓴다): ${x.vocab.join(", ")}\n\n` : "") +
    `전사:\n${x.segments.map((s) => transcriptLine(s, x.myNames)).join("\n")}`;
}
