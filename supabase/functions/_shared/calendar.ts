// 구글 캘린더 보기·넣기(FR-24a, DEC-105, Design §3.16)의 순수 규칙. 처리 함수 `calendar`가 쓰고 deno 시험으로 확인한다.
// 네트워크·DB는 여기서 다루지 않는다.

export const CALENDAR_SCOPE = "https://www.googleapis.com/auth/calendar.events";
/** 같은 계정인지 확인하려고 openid·email을 함께 받는다(id_token의 이메일) */
export const CONNECT_SCOPES = ["openid", "email", CALENDAR_SCOPE];
export const SEOUL = "Asia/Seoul";
/** 시각이 있는 할 일을 넣을 때 일정 길이(11/14 사용자 결정 ③) */
export const TIMED_EVENT_MINUTES = 60;
/** 한 번에 읽는 기간: 오늘 하루 ~ 다음 7일(오늘 포함 8일) */
export const MAX_EVENT_DAYS = 8;
export const STATE_TTL_MINUTES = 10;

/** 허락 뒤 돌아갈 곳: 정해 둔 셋만(열린 리디렉션 방지). DB 확인 제약(calendar_oauth_state.return_to)과 같다 */
export const RETURN_TARGETS = {
  app: "workbrain://calendar-callback",
  web: "https://workbrain-web.vercel.app/settings",
  "web-local": "http://localhost:5173/settings",
} as const;
export type ReturnTo = keyof typeof RETURN_TARGETS;
export type ConnectResult = "ok" | "denied" | "wrong_account" | "error";

export function returnToOf(v: unknown): ReturnTo | null {
  return typeof v === "string" && Object.hasOwn(RETURN_TARGETS, v) ? v as ReturnTo : null;
}

export function returnUrl(to: ReturnTo, result: ConnectResult): string {
  return `${RETURN_TARGETS[to]}?calendar=${result}`;
}

/** Google 허락 화면 주소. prompt=consent·access_type=offline이어야 연결 열쇠(refresh token)를 매번 받는다 */
export function authUrl(p: { clientId: string; redirectUri: string; state: string; loginHint?: string }): string {
  const q = new URLSearchParams({
    client_id: p.clientId,
    redirect_uri: p.redirectUri,
    response_type: "code",
    scope: CONNECT_SCOPES.join(" "),
    access_type: "offline",
    prompt: "consent",
    include_granted_scopes: "true",
    state: p.state,
  });
  if (p.loginHint) q.set("login_hint", p.loginHint);
  return `https://accounts.google.com/o/oauth2/v2/auth?${q}`;
}

/** 허락에서 캘린더 칸을 끄고 넘어오면(Google의 항목별 허락) 범위가 빠진다 */
export function hasCalendarScope(scope: unknown): boolean {
  return typeof scope === "string" && scope.split(/\s+/).includes(CALENDAR_SCOPE);
}

/**
 * Google 토큰 응답의 id_token에서 확인된 이메일만 꺼낸다. 토큰 주소와 직접(TLS) 주고받은 id_token이라
 * 서명 확인 대신 TLS로 발급자를 믿는다(OIDC Core 3.1.3.7). 형식이 이상하면 null
 */
export function emailFromIdToken(idToken: unknown): string | null {
  if (typeof idToken !== "string") return null;
  const part = idToken.split(".")[1];
  if (!part) return null;
  try {
    const json = JSON.parse(new TextDecoder().decode(base64UrlDecode(part)));
    if (json.email_verified !== true && json.email_verified !== "true") return null;
    return typeof json.email === "string" ? json.email.toLowerCase() : null;
  } catch {
    return null;
  }
}

function base64UrlDecode(s: string): Uint8Array {
  const b64 = s.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((s.length + 3) % 4);
  return Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
}

/** 1회용 확인값: 무작위 32바이트를 base64url로(43자) */
export function newState(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

// ── 일정 만들기·고치기 ──

export interface TaskForEvent {
  id: string;
  title: string;
  due_date: string | null; // YYYY-MM-DD(날짜만인 기한)
  due_time: string | null; // timestamptz(시각까지 있는 기한, 앱·웹이 서울 시각으로 저장 — 이게 있으면 날짜도 이것으로 본다)
}

type EventTime = { date: string } | { dateTime: string; timeZone: string };
export interface GoogleEventBody {
  summary: string;
  description: string;
  start: EventTime;
  end: EventTime;
  reminders: { useDefault: true };
  extendedProperties: { private: { wbTaskId: string; wbOwner: string } };
}

/** Google이 돌려주는 일정 중 쓰는 칸 */
export interface GoogleEvent {
  id: string;
  status?: string;
  summary?: string;
  htmlLink?: string;
  start?: { date?: string; dateTime?: string };
  end?: { date?: string; dateTime?: string };
  attendees?: { self?: boolean; responseStatus?: string }[];
  extendedProperties?: { private?: Record<string, string> };
}

const DATE_RE = /^\d{4}-\d{2}-\d{2}$/;

export function isDate(v: unknown): v is string {
  return typeof v === "string" && DATE_RE.test(v) && !Number.isNaN(Date.parse(`${v}T00:00:00Z`));
}

/** 서울 벽시계 'YYYY-MM-DDTHH:MM'에 분을 더한다(서울은 서머타임이 없어 UTC 계산으로 충분) */
function addMinutes(date: string, hhmm: string, minutes: number): { date: string; hhmm: string } {
  const t = new Date(`${date}T${hhmm}:00Z`);
  t.setUTCMinutes(t.getUTCMinutes() + minutes);
  const iso = t.toISOString();
  return { date: iso.slice(0, 10), hhmm: iso.slice(11, 16) };
}

function addDays(date: string, days: number): string {
  const t = new Date(`${date}T00:00:00Z`);
  t.setUTCDate(t.getUTCDate() + days);
  return t.toISOString().slice(0, 10);
}

const timed = (date: string, hhmm: string): EventTime => ({ dateTime: `${date}T${hhmm}:00`, timeZone: SEOUL });

/** timestamptz → 서울 벽시계 날짜·시각. 읽을 수 없으면 null */
function seoulWall(at: string): { date: string; hhmm: string } | null {
  const t = Date.parse(at);
  if (Number.isNaN(t)) return null;
  const iso = new Date(t + 9 * 3600_000).toISOString();
  return { date: iso.slice(0, 10), hhmm: iso.slice(11, 16) };
}

/** 기존 일정의 길이(분). 종일은 날 수 × 1440. 읽을 수 없으면 null */
function durationMinutes(e: GoogleEvent): number | null {
  if (e.start?.date && e.end?.date) {
    const d = (Date.parse(`${e.end.date}T00:00:00Z`) - Date.parse(`${e.start.date}T00:00:00Z`)) / 60000;
    return d > 0 ? d : null;
  }
  if (e.start?.dateTime && e.end?.dateTime) {
    const d = (Date.parse(e.end.dateTime) - Date.parse(e.start.dateTime)) / 60000;
    return d > 0 ? d : null;
  }
  return null;
}

/**
 * 할 일 → 일정 본문. 기한 날짜가 없으면 null(넣을 수 없음).
 * 날짜만 = 종일 일정, 날짜+시각 = 그 시각부터 1시간(11/14 결정 ③). current(이미 있는 일정)가 같은 종류면 그 길이를 그대로 둔다
 * — 캘린더에서 늘리거나 줄인 길이를 WB에서 제목·기한을 고칠 때 되돌리지 않으려는 것.
 */
export function eventBody(task: TaskForEvent, owner: string, current?: GoogleEvent): GoogleEventBody | null {
  const wall = task.due_time ? seoulWall(task.due_time) : null;
  if (!wall && !isDate(task.due_date)) return null;
  const kept = current ? durationMinutes(current) : null;
  let start: EventTime, end: EventTime;
  if (wall) {
    const minutes = current?.start?.dateTime && kept ? kept : TIMED_EVENT_MINUTES;
    const e = addMinutes(wall.date, wall.hhmm, minutes);
    start = timed(wall.date, wall.hhmm);
    end = timed(e.date, e.hhmm);
  } else {
    const date = task.due_date!;
    const days = current?.start?.date && kept ? Math.max(1, Math.round(kept / 1440)) : 1;
    start = { date };
    end = { date: addDays(date, days) };
  }
  return {
    summary: task.title.slice(0, 1000),
    description: "WorkBrain에서 넣은 할 일",
    start,
    end,
    reminders: { useDefault: true },
    extendedProperties: { private: { wbTaskId: task.id, wbOwner: owner } },
  };
}

/** WB가 넣은 일정인지(남의 일정은 지우지 않는다). owner를 주면 그 사람이 넣은 것만 */
export function isWbEvent(e: GoogleEvent, owner?: string): boolean {
  const p = e.extendedProperties?.private;
  if (!p?.wbTaskId) return false;
  return owner === undefined || p.wbOwner === owner;
}

// ── 일정 읽기 ──

/** 앱·웹에 돌려주는 일정 한 줄. 서버에 저장하지 않는다 */
export interface CalEvent {
  id: string;
  title: string;
  start: string; // 종일 = YYYY-MM-DD, 시각 있음 = ISO(오프셋 포함)
  end: string;
  allDay: boolean;
  link: string | null;
}

/**
 * 보여 줄 일정만: 취소된 일정·내가 거절한 일정·WB가 넣은 일정(할 일 줄에 아이콘으로 보이므로, 11/14 결정 ②)은 뺀다.
 * Google이 시각순으로 주지만 종일·시각 섞임을 위해 다시 정렬하지 않는다(화면 규칙이 정렬)
 */
export function visibleEvents(items: GoogleEvent[]): CalEvent[] {
  return items
    .filter((e) => e.status !== "cancelled")
    .filter((e) => !e.attendees?.some((a) => a.self && a.responseStatus === "declined"))
    .filter((e) => !isWbEvent(e))
    .filter((e) => (e.start?.date || e.start?.dateTime) && (e.end?.date || e.end?.dateTime))
    .map((e) => ({
      id: e.id,
      title: (e.summary ?? "").trim() || "(제목 없음)",
      start: (e.start!.dateTime ?? e.start!.date)!,
      end: (e.end!.dateTime ?? e.end!.date)!,
      allDay: !e.start!.dateTime,
      link: e.htmlLink ?? null,
    }));
}

/** 서울 날짜 from부터 days일(그날 0시 ~ 마지막 날 다음 0시). 형식이 틀리거나 days가 범위 밖이면 null */
export function dayRange(from: unknown, days: unknown): { timeMin: string; timeMax: string } | null {
  const n = typeof days === "number" ? days : Number.NaN;
  if (!isDate(from) || !Number.isInteger(n) || n < 1 || n > MAX_EVENT_DAYS) return null;
  return { timeMin: `${from}T00:00:00+09:00`, timeMax: `${addDays(from, n)}T00:00:00+09:00` };
}
