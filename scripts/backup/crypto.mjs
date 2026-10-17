// 백업 파일 암호화 공통: gzip 후 AES-256-GCM. 키는 사용자가 정한 암호문에서 scrypt로 만든다.
// 파일 구조: "WBK1"(4) + salt(16) + iv(12) + tag(16) + 암호문. 암호문을 잃으면 복원할 수 없다.
import { createCipheriv, createDecipheriv, randomBytes, scryptSync } from "node:crypto";
import { gzipSync, gunzipSync } from "node:zlib";
import { existsSync, readFileSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const MAGIC = Buffer.from("WBK1");
// 한글 암호문은 기기마다 자모 조합 방식(NFC/NFD)이 달라 같은 글자라도 바이트가 다를 수 있다.
// 항상 NFC로 맞추고 앞뒤 공백·줄바꿈을 지워, 맥·윈도우·GitHub 어디서 넣어도 같은 키가 나오게 한다.
const normalizePass = (pass) => String(pass ?? "").trim().normalize("NFC");
const key = (pass, salt) => scryptSync(normalizePass(pass), salt, 32, { N: 2 ** 15, r: 8, p: 1, maxmem: 64 * 1024 * 1024 });

export function encrypt(plain, pass) {
  if ([...normalizePass(pass)].length < 12) throw new Error("BACKUP_PASSPHRASE는 12자 이상이어야 해요");
  const salt = randomBytes(16), iv = randomBytes(12);
  const c = createCipheriv("aes-256-gcm", key(pass, salt), iv);
  const body = Buffer.concat([c.update(gzipSync(plain)), c.final()]);
  return Buffer.concat([MAGIC, salt, iv, c.getAuthTag(), body]);
}

export function decrypt(buf, pass) {
  if (!buf.subarray(0, 4).equals(MAGIC)) throw new Error("WorkBrain 백업 파일이 아니에요");
  const salt = buf.subarray(4, 20), iv = buf.subarray(20, 32), tag = buf.subarray(32, 48);
  const d = createDecipheriv("aes-256-gcm", key(pass, salt), iv);
  d.setAuthTag(tag);
  // 암호문이 틀리거나 파일이 바뀌었으면 여기서 오류가 난다(조용히 깨진 데이터를 복원하지 않음)
  return gunzipSync(Buffer.concat([d.update(buf.subarray(48)), d.final()]));
}

// 환경 변수 우선, 없으면 저장소 루트 .env
export function loadEnv() {
  const p = join(dirname(fileURLToPath(import.meta.url)), "..", "..", ".env");
  const file = existsSync(p) ? Object.fromEntries(readFileSync(p, "utf8").split(/\r?\n/)
    .map((l) => l.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*)\s*$/)).filter(Boolean).map((m) => [m[1], m[2]])) : {};
  return { ...file, ...process.env };
}

// Supabase 서버 키 헤더: 새 형식(sb_secret_…)은 apikey로만 보내고, 옛 service_role(JWT)은 Authorization에도 넣는다
export function serviceHeaders(key) {
  const h = { apikey: key, "content-type": "application/json" };
  if (!key.startsWith("sb_secret_")) h.authorization = `Bearer ${key}`;
  return h;
}
