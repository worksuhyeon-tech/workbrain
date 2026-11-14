// 구글 캘린더 규칙(FR-24a, DEC-105, Design §3.16) 시험. 실행: npx -y deno@2.9.4 test tests/functions/calendar_test.ts
import { assertEquals } from "jsr:@std/assert@1";
import {
  authUrl,
  CALENDAR_SCOPE,
  dayRange,
  emailFromIdToken,
  eventBody,
  eventPatch,
  type GoogleEvent,
  hasCalendarScope,
  isWbEvent,
  newState,
  returnToOf,
  returnUrl,
  visibleEvents,
} from "../../supabase/functions/_shared/calendar.ts";

const OWNER = "00000000-0000-4000-a000-0000000042a1";
const task = (due_date: string | null, due_time: string | null = null) => ({ id: "t1", title: "업체 미팅", due_date, due_time });

Deno.test("날짜만 있는 할 일은 종일 일정(끝은 다음 날), 표식·기본 알림", () => {
  const b = eventBody(task("2026-11-20"), OWNER)!;
  assertEquals(b.start, { date: "2026-11-20" });
  assertEquals(b.end, { date: "2026-11-21" });
  assertEquals(b.summary, "업체 미팅");
  assertEquals(b.extendedProperties.private, { wbTaskId: "t1", wbOwner: OWNER });
  assertEquals(b.reminders, { useDefault: true });
});

Deno.test("시각 있는 할 일은 그 시각(서울)부터 1시간 — 기한 시각은 timestamptz로 저장됨", () => {
  // 서버 값 그대로(UTC 06:00 = 서울 15:00)
  const b = eventBody(task("2026-11-20", "2026-11-20T06:00:00+00:00"), OWNER)!;
  assertEquals(b.start, { dateTime: "2026-11-20T15:00:00", timeZone: "Asia/Seoul" });
  assertEquals(b.end, { dateTime: "2026-11-20T16:00:00", timeZone: "Asia/Seoul" });
});

Deno.test("자정·월말·연말을 넘는 1시간과 종일 다음 날", () => {
  assertEquals(eventBody(task("2026-11-30", "2026-11-30T23:30:00+09:00"), OWNER)!.end, { dateTime: "2026-12-01T00:30:00", timeZone: "Asia/Seoul" });
  // UTC로는 전날이어도 서울 날짜로 넣는다(UTC 11/30 16:00 = 서울 12/1 01:00)
  assertEquals(eventBody(task(null, "2026-11-30T16:00:00Z"), OWNER)!.start, { dateTime: "2026-12-01T01:00:00", timeZone: "Asia/Seoul" });
  assertEquals(eventBody(task("2026-12-31"), OWNER)!.end, { date: "2027-01-01" });
  assertEquals(eventBody(task("2028-02-28"), OWNER)!.end, { date: "2028-02-29" });
});

Deno.test("기한 날짜가 없거나 이상하면 넣을 수 없음", () => {
  assertEquals(eventBody(task(null), OWNER), null);
  assertEquals(eventBody(task("2026-13-40"), OWNER), null);
  assertEquals(eventBody(task("다음 주"), OWNER), null);
  assertEquals(eventBody(task(null, "언젠가"), OWNER), null);
});

Deno.test("고칠 때: 캘린더에서 늘린 길이는 그대로, 종류가 바뀌면 기본 길이(반대 종류 칸은 null로 지움)", () => {
  const timed90: GoogleEvent = { id: "e", start: { dateTime: "2026-11-20T15:00:00+09:00" }, end: { dateTime: "2026-11-20T16:30:00+09:00" } };
  assertEquals(eventPatch(task("2026-11-21", "2026-11-21T10:00:00+09:00"), OWNER, timed90)!.end, { dateTime: "2026-11-21T11:30:00", timeZone: "Asia/Seoul", date: null });
  // 시각 있음 → 날짜만: 종일 하루
  assertEquals(eventPatch(task("2026-11-21"), OWNER, timed90)!.end, { date: "2026-11-22", dateTime: null, timeZone: null });
  // 이틀짜리 종일 일정은 이틀 그대로
  const allDay2: GoogleEvent = { id: "e", start: { date: "2026-11-20" }, end: { date: "2026-11-22" } };
  assertEquals(eventPatch(task("2026-11-25"), OWNER, allDay2)!.end, { date: "2026-11-27", dateTime: null, timeZone: null });
  // 종일 → 시각 있음: 1시간
  assertEquals(eventPatch(task("2026-11-25", "2026-11-25T00:00:00Z"), OWNER, allDay2)!.start, { dateTime: "2026-11-25T09:00:00", timeZone: "Asia/Seoul", date: null });
  assertEquals(eventPatch(task("2026-11-25", "2026-11-25T00:00:00Z"), OWNER, allDay2)!.end, { dateTime: "2026-11-25T10:00:00", timeZone: "Asia/Seoul", date: null });
  // 기한을 지웠으면 고칠 것 없음
  assertEquals(eventPatch(task(null), OWNER, allDay2), null);
});

Deno.test("고칠 때 알림·설명은 보내지 않음 — 종일 일정은 Google이 알림을 '직접 정한 알림'으로 바꿔 두어 기본 알림을 합치면 400(11/14)", () => {
  // 11/14 실제로 넣은 종일 일정 모양(기본 알림으로 넣었는데 직접 정한 알림 30분으로 저장됨)
  const saved = { id: "e", start: { date: "2026-11-15" }, end: { date: "2026-11-16" }, reminders: { useDefault: false, overrides: [{ method: "popup", minutes: 30 }] } } as GoogleEvent;
  const p = eventPatch({ id: "t1", title: "매뉴얼 작성", due_date: "2026-11-14", due_time: null }, OWNER, saved)!;
  assertEquals(Object.keys(p).sort(), ["end", "extendedProperties", "start", "summary"]);
  assertEquals(p.start, { date: "2026-11-14", dateTime: null, timeZone: null });
  assertEquals(p.end, { date: "2026-11-15", dateTime: null, timeZone: null });
  assertEquals(p.extendedProperties.private, { wbTaskId: "t1", wbOwner: OWNER });
});

Deno.test("보여 줄 일정: 취소·내가 거절·WB가 넣은 일정은 뺌, 제목 없으면 '(제목 없음)'", () => {
  const items: GoogleEvent[] = [
    { id: "a", summary: "주간 회의", start: { dateTime: "2026-11-14T10:00:00+09:00" }, end: { dateTime: "2026-11-14T11:00:00+09:00" }, htmlLink: "https://cal/a" },
    { id: "b", status: "cancelled", summary: "취소됨", start: { dateTime: "2026-11-14T12:00:00+09:00" }, end: { dateTime: "2026-11-14T13:00:00+09:00" } },
    { id: "c", summary: "거절함", attendees: [{ self: true, responseStatus: "declined" }], start: { dateTime: "2026-11-14T13:00:00+09:00" }, end: { dateTime: "2026-11-14T14:00:00+09:00" } },
    { id: "d", summary: "WB 할 일", extendedProperties: { private: { wbTaskId: "t1", wbOwner: OWNER } }, start: { date: "2026-11-14" }, end: { date: "2026-11-15" } },
    { id: "e", start: { date: "2026-11-14" }, end: { date: "2026-11-15" } },
    { id: "f", summary: "다른 사람 거절은 그대로", attendees: [{ responseStatus: "declined" }, { self: true, responseStatus: "accepted" }], start: { dateTime: "2026-11-14T15:00:00+09:00" }, end: { dateTime: "2026-11-14T16:00:00+09:00" } },
  ];
  const v = visibleEvents(items);
  assertEquals(v.map((e) => e.id), ["a", "e", "f"]);
  assertEquals(v[0], { id: "a", title: "주간 회의", start: "2026-11-14T10:00:00+09:00", end: "2026-11-14T11:00:00+09:00", allDay: false, link: "https://cal/a" });
  assertEquals(v[1].title, "(제목 없음)");
  assertEquals(v[1].allDay, true);
});

Deno.test("WB 일정 표식: 지울 때는 이 사람이 넣은 것만", () => {
  const e: GoogleEvent = { id: "x", extendedProperties: { private: { wbTaskId: "t1", wbOwner: OWNER } } };
  assertEquals(isWbEvent(e, OWNER), true);
  assertEquals(isWbEvent(e, "someone-else"), false);
  assertEquals(isWbEvent({ id: "y" }, OWNER), false);
});

Deno.test("기간: 서울 0시부터 n일, 1~8일만", () => {
  assertEquals(dayRange("2026-11-14", 1), { timeMin: "2026-11-14T00:00:00+09:00", timeMax: "2026-11-15T00:00:00+09:00" });
  assertEquals(dayRange("2026-11-28", 8), { timeMin: "2026-11-28T00:00:00+09:00", timeMax: "2026-12-06T00:00:00+09:00" });
  assertEquals(dayRange("2026-11-14", 0), null);
  assertEquals(dayRange("2026-11-14", 9), null);
  assertEquals(dayRange("2026-11-14", "1"), null);
  assertEquals(dayRange("11/14", 1), null);
});

Deno.test("돌아갈 곳은 정해 둔 셋만, 결과를 붙임", () => {
  assertEquals(returnToOf("app"), "app");
  assertEquals(returnToOf("https://evil.example.test"), null);
  assertEquals(returnToOf("toString"), null);
  assertEquals(returnUrl("app", "ok"), "workbrain://calendar-callback?calendar=ok");
  assertEquals(returnUrl("web", "wrong_account"), "https://workbrain-web.vercel.app/settings?calendar=wrong_account");
});

Deno.test("허락 주소: 열쇠를 받는 옵션·범위·확인값", () => {
  const u = new URL(authUrl({ clientId: "cid", redirectUri: "https://x.supabase.co/functions/v1/calendar", state: "s".repeat(43), loginHint: "me@example.test" }));
  assertEquals(u.origin + u.pathname, "https://accounts.google.com/o/oauth2/v2/auth");
  assertEquals(u.searchParams.get("access_type"), "offline");
  assertEquals(u.searchParams.get("prompt"), "consent");
  assertEquals(u.searchParams.get("scope"), `openid email ${CALENDAR_SCOPE}`);
  assertEquals(u.searchParams.get("login_hint"), "me@example.test");
  assertEquals(u.searchParams.get("state"), "s".repeat(43));
});

Deno.test("확인값은 43자 base64url, 매번 다름", () => {
  const a = newState(), b = newState();
  assertEquals(/^[A-Za-z0-9_-]{43}$/.test(a), true);
  assertEquals(a === b, false);
});

Deno.test("캘린더 범위 확인·id_token 이메일(확인된 것만, 소문자)", () => {
  assertEquals(hasCalendarScope(`openid ${CALENDAR_SCOPE} email`), true);
  assertEquals(hasCalendarScope("openid email"), false);
  const jwt = (payload: unknown) => `h.${btoa(JSON.stringify(payload)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "")}.s`;
  assertEquals(emailFromIdToken(jwt({ email: "Me@Example.test", email_verified: true })), "me@example.test");
  assertEquals(emailFromIdToken(jwt({ email: "me@example.test", email_verified: false })), null);
  assertEquals(emailFromIdToken("not-a-jwt"), null);
  assertEquals(emailFromIdToken(undefined), null);
});
