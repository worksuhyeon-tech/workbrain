// 회의록 Word 받기(FR-29, DEC-60): 앱이 화면에 보이는 회의록 버전 ID를 보내면 그 버전의 문서를 .docx로 돌려준다(M14).
// 본인 기록만(Design §4.7: 서버 키로 읽으므로 소유자 검사를 코드로 직접 한다). 외부 AI 호출이 없어 비용·동의 게이트가 없다.
// 업무계획 Word(FR-33, Design §3.13): 앱이 미리보기로 받은 계획(plan)을 그대로 보내면 같은 글로 표를 그린다. DB를 읽지 않는다.
import { createClient } from "jsr:@supabase/supabase-js@2";
import { type MinutesDoc, renderMinutesDocx } from "../_shared/minutes-docx.ts";
import { renderPlanDocx } from "../_shared/plan-docx.ts";
import { checkPlan } from "../_shared/work-plan.ts";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

const reply = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });

Deno.serve(async (req) => {
  if (req.method !== "POST") return reply(405, { error: "POST만 받아요" });
  const db = createClient(SUPABASE_URL, SERVICE_KEY, { auth: { persistSession: false } });

  const token = (req.headers.get("Authorization") ?? "").replace(/^Bearer\s+/i, "");
  const { data: who } = await db.auth.getUser(token);
  const uid = who?.user?.id;
  if (!uid) return reply(401, { error: "로그인이 필요해요" });

  let versionId = "", planRaw: unknown = undefined;
  try {
    const body = await req.json();
    versionId = String(body.meeting_version_id ?? ""); planRaw = body.plan;
  } catch { /* 아래에서 거부 */ }
  if (planRaw !== undefined) {
    const plan = checkPlan(planRaw);
    if (!plan) return reply(400, { error: "업무계획 모양이 올바르지 않아요" });
    try {
      return new Response(await renderPlanDocx(plan), {
        status: 200, headers: { "content-type": DOCX_MIME, "cache-control": "no-store", "x-wb-plan-kind": plan.kind },
      });
    } catch (e) {
      console.error("plan docx render failed", String(e));
      return reply(500, { error: "Word 파일을 만들지 못했어요" });
    }
  }
  if (!/^[0-9a-f-]{36}$/i.test(versionId)) return reply(400, { error: "회의록 ID가 올바르지 않아요" });

  // 다른 사람 회의록은 있는지조차 알리지 않도록 '없음'과 같은 답을 준다
  const { data: mv } = await db.from("meeting_version")
    .select("document,state,transcript_version(source_record(occurred_at,ingested_at))")
    .eq("id", versionId).eq("owner_id", uid).maybeSingle();
  const doc = mv?.document as MinutesDoc | null | undefined;
  if (!mv || !doc?.sections?.length) return reply(404, { error: "회의록 문서가 없어요" });

  // 파일 이름·메일 제목에 쓸 회의 날짜(서울). 한글 파일 이름은 다운로드가 실패한 적이 있어 앱이 ASCII 이름을 만든다
  const rec = (mv.transcript_version as { source_record?: { occurred_at?: string; ingested_at?: string } } | null)?.source_record;
  const at = new Date(rec?.occurred_at ?? rec?.ingested_at ?? Date.now());
  const meetingDate = new Date(at.getTime() + 9 * 3600 * 1000).toISOString().slice(0, 10);

  try {
    const draft = mv.state !== "approved";
    const bytes = await renderMinutesDocx(doc, { draft });
    return new Response(bytes, {
      status: 200,
      headers: { "content-type": DOCX_MIME, "cache-control": "no-store", "x-wb-meeting-date": meetingDate, "x-wb-draft": String(draft) },
    });
  } catch (e) {
    console.error("docx render failed", String(e));
    return reply(500, { error: "Word 파일을 만들지 못했어요" });
  }
});
