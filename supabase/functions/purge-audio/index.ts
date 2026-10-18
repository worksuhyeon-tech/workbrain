// 서버 음성 보관 기한 자동 삭제 (DEC-35). pg_cron이 매일 한 번 부른다.
// 보관 기한(audio_asset.retention_expires_at)이 지난 음성 파일만 저장소에서 지우고 purged_at을 남긴다.
// 로그인 없이 부를 수 있지만, 하는 일이 '이미 기한이 지난 파일 지우기' 하나뿐이라 일찍 불려도 결과가 같다.
// 개수만 돌려주고 파일 이름·소유자는 밖으로 내보내지 않는다.
import { createClient } from "jsr:@supabase/supabase-js@2";

const BATCH = 100;   // 한 번에 지울 최대 개수(실행 시간 제한 안에 들게)

Deno.serve(async (req) => {
  if (req.method !== "POST") return new Response(null, { status: 405 });
  const db = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, { auth: { persistSession: false } });

  const { data: rows, error } = await db.from("audio_asset").select("id,storage_key")
    .is("purged_at", null).lt("retention_expires_at", new Date().toISOString())
    .order("retention_expires_at").limit(BATCH);
  if (error) return json(500, { error: "목록 조회 실패" });
  if (!rows?.length) return json(200, { purged: 0 });

  // 저장소에서 먼저 지우고, 지워진 것만 표시한다(표시만 하고 파일이 남는 일이 없게)
  const { data: removed, error: rmErr } = await db.storage.from("audio").remove(rows.map((r) => r.storage_key));
  if (rmErr) return json(500, { error: "파일 삭제 실패" });
  // 이미 없는 파일(수동 삭제 등)도 다시 시도하지 않게 함께 표시한다
  const ids = rows.map((r) => r.id);
  const { error: upErr } = await db.from("audio_asset").update({ purged_at: new Date().toISOString() }).in("id", ids);
  if (upErr) return json(500, { error: "삭제 표시 실패" });
  return json(200, { purged: ids.length, files_removed: removed?.length ?? 0, more: rows.length === BATCH });
});

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
