// 복원(임시 스키마 wb_restore까지만): 백업·삭제 원장 파일을 풀어 wb_backup_restore를 호출한다.
//   node scripts/backup/restore.mjs <backup-날짜.json.gz.enc> <tombstones-날짜.json.gz.enc>
// 삭제 원장은 GCS에서 '가장 최근 것'을 받는다(백업보다 오래되면 DB 함수가 거부).
// 운영 스키마로 옮기는 마지막 단계는 결과를 확인한 뒤 사람이 따로 한다(Design §4.7).
import { readFileSync } from "node:fs";
import { loadEnv, decrypt, serviceHeaders } from "./crypto.mjs";

const [backupFile, tombFile] = process.argv.slice(2);
if (!backupFile || !tombFile) { console.error("사용법: restore.mjs <백업 파일> <삭제 원장 파일>"); process.exit(2); }
const env = loadEnv();
for (const k of ["SUPABASE_URL", "SUPABASE_SERVICE_ROLE_KEY", "BACKUP_PASSPHRASE"]) {
  if (!env[k]) { console.error(`${k}가 없어요`); process.exit(2); }
}
const backup = JSON.parse(decrypt(readFileSync(backupFile), env.BACKUP_PASSPHRASE));
const tombstones = JSON.parse(decrypt(readFileSync(tombFile), env.BACKUP_PASSPHRASE));

const r = await fetch(`${env.SUPABASE_URL}/rest/v1/rpc/wb_backup_restore`, {
  method: "POST",
  headers: serviceHeaders(env.SUPABASE_SERVICE_ROLE_KEY),
  body: JSON.stringify({ p_backup: backup, p_tombstones: tombstones }),
});
const d = await r.json();
if (!r.ok) { console.error(`복원 실패: ${d.message ?? r.status}`); process.exit(1); }
console.log(`임시 스키마 ${d.schema}에 복원 · 원장 적용 ${d.tombstoned_removed}건 · 부모 없는 행 정리 ${d.orphans_removed}건`);
console.log(JSON.stringify(d.counts));
