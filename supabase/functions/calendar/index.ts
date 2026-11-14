// 구글 캘린더 보기·넣기(FR-24a, DEC-105, Design §3.16). 로그인한 같은 Google 계정의 기본 캘린더 하나만 다룬다.
// - POST(앱·웹, 로그인 확인): connect · disconnect · events · insert · sync · remove
// - GET(Google이 허락 뒤 돌려보냄, 로그인 머리 없음): code·state로 연결 열쇠를 받아 Vault에 넣고 앱·웹으로 돌려보낸다
// 연결 열쇠·접근 토큰·일정 제목은 로그에 남기지 않는다(번호·상태만, §8.2). 일정은 서버에 저장하지 않는다.
// 서버 키로 읽고 쓰므로 소유자 검사(owner_id = 로그인한 사람)를 코드로 직접 한다(§4.7).
import { createClient, type SupabaseClient } from "jsr:@supabase/supabase-js@2";
import { withCors } from "../_shared/cors.ts";
import {
  authUrl,
  type ConnectResult,
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
  type ReturnTo,
  STATE_TTL_MINUTES,
  type TaskForEvent,
  visibleEvents,
} from "../_shared/calendar.ts";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const CLIENT_ID = Deno.env.get("GOOGLE_CLIENT_ID");
const CLIENT_SECRET = Deno.env.get("GOOGLE_CLIENT_SECRET");
// Google이 허락 뒤 돌려보내는 주소. 웹(Vercel)이 이 함수로 그대로 넘긴다(web/vercel.json).
// 함수 주소를 바로 쓰면 허락 화면에 '…supabase.co(으)로 이동'이 보여 웹 주소를 거친다(11/14 사용자).
// Google 클라이언트의 '승인된 리디렉션 URI'에 넣은 주소와 글자까지 같아야 한다
const REDIRECT_URI = "https://workbrain-web.vercel.app/calendar-callback";
const EVENTS_URL = "https://www.googleapis.com/calendar/v3/calendars/primary/events";
const TOKEN_URL = "https://oauth2.googleapis.com/token";
/** 한 번에 반영할 '고친 할 일' 수(함수 시간 제한 안에서) */
const SYNC_LIMIT = 50;

const reply = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json", "cache-control": "no-store" } });
const redirect = (to: string) => new Response(null, { status: 302, headers: { location: to, "cache-control": "no-store" } });
/** 돌아갈 곳을 모를 때(확인값이 없거나 지남)만 쓰는 짧은 안내 화면 */
const page = (status: number, text: string) =>
  new Response(`<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>WorkBrain</title><p style="font:16px sans-serif;padding:24px">${text}</p>`, {
    status, headers: { "content-type": "text/html; charset=utf-8", "cache-control": "no-store" },
  });

/** 기대한 실패(연결 안 됨·끊김 등)는 200 + reason으로 돌려 앱·웹이 문구를 고르게 한다 */
class Expected extends Error {
  constructor(readonly reason: "not-connected" | "broken" | "no-due" | "not-found") { super(reason); }
}

Deno.serve(withCors(async (req) => {
  const db = createClient(SUPABASE_URL, SERVICE_KEY, { auth: { persistSession: false } });
  if (req.method === "GET") return await callback(db, new URL(req.url));
  if (req.method !== "POST") return reply(405, { error: "POST만 받아요" });
  if (!CLIENT_ID || !CLIENT_SECRET) {
    console.error("calendar: google client secrets missing");
    return reply(503, { error: "캘린더 연결 준비가 안 됐어요(서버 설정)" });
  }

  const token = (req.headers.get("Authorization") ?? "").replace(/^Bearer\s+/i, "");
  const { data: who } = await db.auth.getUser(token);
  const uid = who?.user?.id;
  if (!uid) return reply(401, { error: "로그인이 필요해요" });

  let body: Record<string, unknown> = {};
  try { body = await req.json(); } catch { /* 아래에서 거부 */ }

  try {
    switch (body.action) {
      case "connect": return reply(200, await connect(db, uid, who?.user?.email, body.return_to));
      case "disconnect": return reply(200, await disconnect(db, uid));
      case "events": return reply(200, await events(db, uid, body.from, body.days));
      case "insert": return reply(200, await insert(db, uid, body.task_id));
      case "sync": return reply(200, await sync(db, uid));
      case "remove": return reply(200, await remove(db, uid, body.task_ids, body.delete_event === true));
      default: return reply(400, { error: "알 수 없는 요청이에요" });
    }
  } catch (e) {
    if (e instanceof Expected) return reply(200, { reason: e.reason });
    if (e instanceof BadInput) return reply(400, { error: e.message });
    console.error("calendar failed", body.action, e instanceof GoogleError ? e.message : String(e));
    return reply(502, { error: "캘린더와 주고받지 못했어요. 잠시 뒤 다시 해 주세요" });
  }
}));

class BadInput extends Error {}
class GoogleError extends Error {
  constructor(readonly status: number, reason = "") { super(`google ${status}${reason ? ` ${reason}` : ""}`); }
}

/** Google 오류 답에서 이유 낱말만 꺼낸다(예: badRequest). 메시지 문장은 일정 내용이 섞일 수 있어 남기지 않는다 */
async function googleError(res: Response): Promise<GoogleError> {
  const j = await res.json().catch(() => null);
  const reason = j?.error?.errors?.[0]?.reason;
  return new GoogleError(res.status, typeof reason === "string" ? reason.slice(0, 80) : "");
}

// ── 연결 ──

/**
 * 로그인 계정 이메일은 로그인(auth) 정보에서 읽는다. app_user 표는 서버 키에도 읽기 권한을 주지 않아(열 단위 권한)
 * 거기서 읽으면 늘 비어 '다른 계정'으로 거절됐다(11/14 PC 웹 첫 연결 실패)
 */
async function loginEmailOf(db: SupabaseClient, uid: string): Promise<string | null> {
  const { data, error } = await db.auth.admin.getUserById(uid);
  if (error) console.error("calendar login email lookup failed", error.status ?? "");
  return data?.user?.email?.toLowerCase() ?? null;
}

async function connect(db: SupabaseClient, uid: string, email: string | undefined, rawReturn: unknown) {
  const to = returnToOf(rawReturn);
  if (!to) throw new BadInput("돌아갈 곳이 올바르지 않아요");
  const state = newState();
  const { error } = await db.from("calendar_oauth_state").insert({ nonce: state, owner_id: uid, return_to: to });
  if (error) throw new Error(`state insert: ${error.code}`);
  // 허락 화면이 로그인한 계정을 먼저 고르게 한다
  return { url: authUrl({ clientId: CLIENT_ID!, redirectUri: REDIRECT_URI, state, loginHint: email }) };
}

/** Google이 허락 뒤 돌려보낸 GET: 확인값을 한 번만 쓰고, 열쇠를 받아 같은 계정인지 확인한 뒤 Vault에 넣는다 */
async function callback(db: SupabaseClient, url: URL): Promise<Response> {
  const state = url.searchParams.get("state") ?? "";
  if (!state) return page(400, "잘못된 주소예요.");
  const cutoff = new Date(Date.now() - STATE_TTL_MINUTES * 60_000).toISOString();
  // 쓴 것으로 표시하면서 꺼낸다(같은 확인값을 두 번 쓸 수 없음)
  const { data: st } = await db.from("calendar_oauth_state")
    .update({ used_at: new Date().toISOString() })
    .eq("nonce", state).is("used_at", null).gt("created_at", cutoff)
    .select("owner_id, return_to").maybeSingle();
  if (!st) return page(400, "연결 시간이 지났어요. WorkBrain에서 [연결]을 다시 눌러 주세요.");
  const to = returnToOf(st.return_to) as ReturnTo;
  const done = (r: ConnectResult) => redirect(returnUrl(to, r));

  const code = url.searchParams.get("code");
  if (url.searchParams.get("error") || !code) return done("denied");
  if (!CLIENT_ID || !CLIENT_SECRET) return done("error");

  try {
    const res = await fetch(TOKEN_URL, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ code, client_id: CLIENT_ID, client_secret: CLIENT_SECRET, redirect_uri: REDIRECT_URI, grant_type: "authorization_code" }),
    });
    const tok = await res.json().catch(() => ({}));
    if (!res.ok) {
      console.error("calendar token exchange failed", res.status, tok?.error ?? "");
      return done("error");
    }
    // 허락 화면에서 캘린더 칸을 끄고 넘어오면 범위가 빠진다
    if (!hasCalendarScope(tok.scope)) {
      console.warn("calendar connect rejected: calendar scope not granted");
      await revoke(tok.refresh_token ?? tok.access_token);
      return done("denied");
    }
    const loginEmail = await loginEmailOf(db, st.owner_id);
    const email = emailFromIdToken(tok.id_token);
    if (!email || !loginEmail || email !== loginEmail) {
      // 이메일은 남기지 않고 어느 쪽이 비었는지만
      console.warn("calendar connect rejected: account", !email ? "no google email" : !loginEmail ? "no login email" : "mismatch");
      await revoke(tok.refresh_token ?? tok.access_token);
      return done("wrong_account");
    }
    if (typeof tok.refresh_token !== "string") {
      console.error("calendar token exchange: no refresh token");
      return done("error");
    }
    const { error } = await db.rpc("wb_calendar_save_token", { p_owner: st.owner_id, p_token: tok.refresh_token });
    if (error) {
      console.error("calendar save token failed", error.code);
      return done("error");
    }
    accessCache.delete(st.owner_id);
    return done("ok");
  } catch (e) {
    console.error("calendar callback failed", String(e));
    return done("error");
  }
}

async function disconnect(db: SupabaseClient, uid: string) {
  const { data: refresh } = await db.rpc("wb_calendar_token", { p_owner: uid });
  if (typeof refresh === "string" && refresh) await revoke(refresh);
  const { error } = await db.rpc("wb_calendar_forget", { p_owner: uid });
  if (error) throw new Error(`forget: ${error.code}`);
  accessCache.delete(uid);
  return { ok: true };
}

/** Google에 열쇠 취소 요청. 실패해도 WB 쪽 연결은 끊는다(사용자는 Google 계정 설정에서도 지울 수 있음) */
async function revoke(token: unknown) {
  if (typeof token !== "string" || !token) return;
  try {
    await fetch(`https://oauth2.googleapis.com/revoke?token=${encodeURIComponent(token)}`, { method: "POST" });
  } catch { /* 위 설명 */ }
}

// ── 접근 토큰(열쇠 → 1시간짜리 토큰, 함수 안에서 기억) ──

const accessCache = new Map<string, { token: string; until: number }>();

async function accessToken(db: SupabaseClient, uid: string): Promise<string> {
  const hit = accessCache.get(uid);
  if (hit && hit.until > Date.now()) return hit.token;
  const { data: refresh, error } = await db.rpc("wb_calendar_token", { p_owner: uid });
  if (error) throw new Error(`token read: ${error.code}`);
  if (typeof refresh !== "string" || !refresh) throw new Expected("not-connected");
  const res = await fetch(TOKEN_URL, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ client_id: CLIENT_ID!, client_secret: CLIENT_SECRET!, refresh_token: refresh, grant_type: "refresh_token" }),
  });
  const tok = await res.json().catch(() => ({}));
  if (!res.ok) {
    // 사용자가 Google에서 권한을 취소했거나, 테스트 모드라 7일이 지나 열쇠가 만료됨 → 다시 연결이 필요
    if (tok?.error === "invalid_grant") {
      await db.from("calendar_link").update({ broken_at: new Date().toISOString() }).eq("owner_id", uid);
      throw new Expected("broken");
    }
    throw new GoogleError(res.status, typeof tok?.error === "string" ? tok.error.slice(0, 80) : "");
  }
  const token = String(tok.access_token);
  const seconds = Number(tok.expires_in) || 3600;
  accessCache.set(uid, { token, until: Date.now() + Math.min(seconds - 300, 3000) * 1000 });
  return token;
}

async function google(db: SupabaseClient, uid: string, path: string, init: RequestInit = {}): Promise<Response> {
  const token = await accessToken(db, uid);
  return await fetch(`${EVENTS_URL}${path}`, {
    ...init,
    headers: { authorization: `Bearer ${token}`, "content-type": "application/json", ...(init.headers ?? {}) },
  });
}

// ── 일정 읽기 ──

async function events(db: SupabaseClient, uid: string, from: unknown, days: unknown) {
  const range = dayRange(from, days);
  if (!range) throw new BadInput("기간이 올바르지 않아요");
  const q = new URLSearchParams({
    ...range,
    singleEvents: "true",
    orderBy: "startTime",
    maxResults: "250",
    fields: "items(id,status,summary,location,htmlLink,start,end,attendees(self,responseStatus),extendedProperties/private)",
  });
  const res = await google(db, uid, `?${q}`);
  if (!res.ok) throw await googleError(res);
  const json = await res.json();
  return { events: visibleEvents((json.items ?? []) as GoogleEvent[]) };
}

// ── 넣기·고치기·빼기 ──

async function myTask(db: SupabaseClient, uid: string, taskId: unknown): Promise<TaskForEvent> {
  if (typeof taskId !== "string" || !/^[0-9a-f-]{36}$/i.test(taskId)) throw new BadInput("할 일 ID가 올바르지 않아요");
  const { data } = await db.from("task").select("id,title,due_date,due_time").eq("id", taskId).eq("owner_id", uid).maybeSingle();
  if (!data) throw new Expected("not-found");
  return data as TaskForEvent;
}

/** 지금 일정(길이 유지용). 캘린더에서 이미 지웠으면 null */
async function currentEvent(db: SupabaseClient, uid: string, eventId: string): Promise<GoogleEvent | null> {
  const res = await google(db, uid, `/${encodeURIComponent(eventId)}`);
  if (res.status === 404 || res.status === 410) return null;
  if (!res.ok) throw await googleError(res);
  const e = await res.json() as GoogleEvent;
  return e.status === "cancelled" ? null : e;
}

async function insert(db: SupabaseClient, uid: string, taskId: unknown) {
  const task = await myTask(db, uid, taskId);
  if (!eventBody(task, uid)) throw new Expected("no-due");
  const { data: link } = await db.from("task_calendar").select("event_id").eq("task_id", task.id).eq("owner_id", uid).maybeSingle();
  // 이미 넣은 할 일이면 고치기로(두 번 눌러도 일정 하나)
  if (link) {
    const kept = await patchOrForget(db, uid, task, link.event_id);
    if (kept) return { event_id: link.event_id };
  }
  const res = await google(db, uid, "", { method: "POST", body: JSON.stringify(eventBody(task, uid)) });
  if (!res.ok) throw await googleError(res);
  const created = await res.json() as GoogleEvent;
  const { error } = await db.from("task_calendar").insert({ task_id: task.id, owner_id: uid, event_id: created.id });
  if (error) {
    // 연결을 못 적으면 만든 일정을 지워 떠도는 일정을 남기지 않는다
    await google(db, uid, `/${encodeURIComponent(created.id)}`, { method: "DELETE" }).catch(() => undefined);
    throw new Error(`link insert: ${error.code}`);
  }
  return { event_id: created.id };
}

/** 일정을 할 일에 맞춰 고친다. 캘린더에서 이미 지워졌으면 연결만 지우고 false */
async function patchOrForget(db: SupabaseClient, uid: string, task: TaskForEvent, eventId: string): Promise<boolean> {
  const current = await currentEvent(db, uid, eventId);
  if (!current) {
    await db.from("task_calendar").delete().eq("task_id", task.id).eq("owner_id", uid);
    return false;
  }
  const body = eventPatch(task, uid, current);
  if (body) {
    const res = await google(db, uid, `/${encodeURIComponent(eventId)}`, { method: "PATCH", body: JSON.stringify(body) });
    if (res.status === 404 || res.status === 410) {
      await db.from("task_calendar").delete().eq("task_id", task.id).eq("owner_id", uid);
      return false;
    }
    if (!res.ok) throw await googleError(res);
  }
  // 기한을 지운 할 일(body 없음)은 일정을 건드리지 않는다 — 지울지는 앱·웹이 먼저 물어 remove로 처리(11/14 결정 ④)
  await db.from("task_calendar").update({ dirty: false, updated_at: new Date().toISOString() }).eq("task_id", task.id).eq("owner_id", uid);
  return true;
}

/** 고친 할 일(dirty)을 캘린더에 반영. 하나가 실패해도 나머지는 계속, 실패한 것은 다음 sync에서 다시 */
async function sync(db: SupabaseClient, uid: string) {
  const { data, error } = await db.from("task_calendar")
    .select("task_id,event_id,task(id,title,due_date,due_time)")
    .eq("owner_id", uid).eq("dirty", true).limit(SYNC_LIMIT);
  if (error) throw new Error(`sync read: ${error.code}`);
  let synced = 0, failed = 0;
  for (const row of data ?? []) {
    const task = row.task as unknown as TaskForEvent | null;
    if (!task) continue;
    try {
      await patchOrForget(db, uid, task, row.event_id);
      synced++;
    } catch (e) {
      if (e instanceof Expected) throw e; // 연결 끊김 등은 전체 답으로
      failed++;
      console.error("calendar sync item failed", e instanceof GoogleError ? e.message : String(e));
    }
  }
  return { synced, failed };
}

/**
 * 할 일을 지우거나 기한을 지울 때(11/14 결정 ④·DEC-105): 연결을 지우고, deleteEvent면 일정도 지운다.
 * WB가 이 사람 이름으로 넣은 일정만 지운다(캘린더의 다른 일정 보호)
 */
async function remove(db: SupabaseClient, uid: string, rawIds: unknown, deleteEvent: boolean) {
  const ids = Array.isArray(rawIds) ? rawIds.filter((v): v is string => typeof v === "string" && /^[0-9a-f-]{36}$/i.test(v)) : [];
  if (ids.length === 0 || ids.length > 200) throw new BadInput("할 일 ID가 올바르지 않아요");
  const { data, error } = await db.from("task_calendar").select("task_id,event_id").eq("owner_id", uid).in("task_id", ids);
  if (error) throw new Error(`remove read: ${error.code}`);
  let deleted = 0;
  if (deleteEvent) {
    for (const row of data ?? []) {
      const current = await currentEvent(db, uid, row.event_id);
      if (!current || !isWbEvent(current, uid)) continue;
      const res = await google(db, uid, `/${encodeURIComponent(row.event_id)}`, { method: "DELETE" });
      if (res.ok || res.status === 404 || res.status === 410) deleted++;
      else throw await googleError(res);
    }
  }
  if ((data ?? []).length) await db.from("task_calendar").delete().eq("owner_id", uid).in("task_id", ids);
  return { unlinked: (data ?? []).length, deleted };
}
