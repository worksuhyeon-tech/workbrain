// 일일 백업: Supabase에서 내보내기 → gzip → 암호화(AES-256-GCM) → out/ 에 파일 2개(백업, 삭제 원장)
//   node scripts/backup/backup.mjs [출력폴더=out]
// 환경 변수(GitHub Actions 비밀값 또는 로컬 .env): SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY, BACKUP_PASSPHRASE
// 로그에는 크기·행 수만 남긴다(공개 저장소의 Actions 로그가 공개되므로 내용·비밀값 출력 금지).
import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { loadEnv, encrypt, serviceHeaders } from "./crypto.mjs";

const env = loadEnv();
for (const k of ["SUPABASE_URL", "SUPABASE_SERVICE_ROLE_KEY", "BACKUP_PASSPHRASE"]) {
  if (!env[k]) { console.error(`${k}가 없어요`); process.exit(2); }
}

async function rpc(name) {
  const r = await fetch(`${env.SUPABASE_URL}/rest/v1/rpc/${name}`, {
    method: "POST",
    headers: serviceHeaders(env.SUPABASE_SERVICE_ROLE_KEY),
    body: "{}",
  });
  if (!r.ok) throw new Error(`${name} 실패: HTTP ${r.status}`);   // 응답 본문은 출력하지 않는다
  return r.json();
}

const outDir = process.argv[2] ?? "out";
mkdirSync(outDir, { recursive: true });
// 같은 날 다시 실행해도 덮어쓰지 않게 시각까지 넣는다(서비스 계정은 '넣기'만 가능해 덮어쓰기 불가)
const stamp = new Date().toISOString().slice(0, 16).replace(/[:]/g, "").replace("T", "-");   // 2026-10-18-1725 (UTC)

const backup = await rpc("wb_backup_export");
const tombstones = await rpc("wb_tombstone_export");
if (backup.format !== "workbrain-backup-v1" || tombstones.format !== "workbrain-tombstones-v1") throw new Error("형식이 맞지 않아요");

const rows = Object.values(backup.tables).reduce((n, t) => n + t.length, 0);
for (const [name, data] of [[`backup-${stamp}`, backup], [`tombstones-${stamp}`, tombstones]]) {
  const file = join(outDir, `${name}.json.gz.enc`);
  const buf = encrypt(Buffer.from(JSON.stringify(data)), env.BACKUP_PASSPHRASE);
  writeFileSync(file, buf);
  console.log(`${file} ${buf.length} bytes`);
}
console.log(`백업 행 ${rows} · 복구 대기 제외 ${backup.skipped_soft_blocked} · 삭제 원장 ${tombstones.tombstones.length}`);
