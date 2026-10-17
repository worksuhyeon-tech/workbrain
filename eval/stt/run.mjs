// 한국어 전사(gpt-transcribe) 품질 시험 (P0, DEC-19 / 로드맵 §2)
//   node eval/stt/run.mjs --scripts   녹음용 대본(scripts.md)을 개선용 평가 세트에서 만든다
//   node eval/stt/run.mjs             recordings/p0-stt/<ID>.m4a 등을 전사하고 문자 오류율(CER)·핵심어를 잰다
// 녹음 파일은 recordings/(커밋 안 함)에 둔다. API 키는 .env의 OPENAI_API_KEY.
import { readFileSync, writeFileSync, existsSync, mkdirSync, statSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = join(HERE, "..", "..");
const AUDIO_DIR = join(ROOT, "recordings", "p0-stt");
const MODEL = "gpt-transcribe";
const USD_PER_MIN = 0.0045;     // DEC-19
const KRW = 1400;
const DEV = JSON.parse(readFileSync(join(HERE, "..", "llm", "dev.json"), "utf8"));

// 녹음할 기록과 꼭 맞아야 하는 핵심어(이름·숫자·업무 용어). 숫자는 말한 형태와 숫자 표기를 모두 인정한다
const SET = {
  D01: { note: "조용한 곳 · 회의처럼 또박또박", terms: [["박지훈"], ["김하늘"], ["사고접수"], ["진행조회"], ["법무팀"], ["벤치마크"], ["10월16일", "시월십육일"], ["열장", "10장"], ["금요일"], ["목요일"]] },
  D04: { note: "TV나 음악을 작게 켜 둔 곳 · 잡음 조건", terms: [["박지훈"], ["이도윤"], ["지급심사"], ["기준서"], ["단순청구"], ["재시도버튼"], ["10월23일", "시월이십삼일"], ["20개", "스무개", "이십개"], ["31일", "삼십일일"]] },
  D05: { note: "스피커폰 통화처럼 휴대폰을 조금 멀리", terms: [["박지훈"], ["사고접수"], ["보상팀"], ["10메가", "10mb", "십메가"], ["로그분석"], ["화요일"]] },
  D09: { note: "혼잣말 메모처럼 편하게 · 다른 휴대폰으로", terms: [["사고접수"], ["실손"], ["민원처리기준"], ["월간보고서"], ["31일", "삼십일일"], ["10시", "열시"], ["은행"], ["재택"]] },
};

const norm = (s) => s.toLowerCase().replace(/[\s.,?!'"“”‘’…·~\-()\[\]:;]/g, "");

// 문자 오류율 = 편집 거리 / 정답 글자 수(띄어쓰기·문장부호 제외)
function cer(ref, hyp) {
  const a = [...norm(ref)], b = [...norm(hyp)];
  let prev = Array.from({ length: b.length + 1 }, (_, j) => j);
  for (let i = 1; i <= a.length; i++) {
    const cur = [i];
    for (let j = 1; j <= b.length; j++) cur[j] = Math.min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
    prev = cur;
  }
  return a.length ? prev[b.length] / a.length : 0;
}

function writeScripts() {
  let md = "# 전사 시험 녹음 대본 (P0)\n\n가상 업무 대본이에요. 실제 회사 내용이 아니에요. 한 사람이 모든 줄을 읽어도 돼요(화자 분리는 P2라 이번 시험과 상관없어요).\n\n" +
    "- 파일 이름: `D01.m4a`처럼 기록 번호로. 저장 위치: 저장소 폴더 `recordings/p0-stt/` (Git에 올라가지 않아요)\n" +
    "- 휴대폰 기본 녹음 앱으로 녹음해 PC로 옮기면 돼요. m4a·mp3·wav 모두 괜찮아요.\n" +
    "- 줄 사이는 평소 말하듯 잠깐 쉬고, 틀리게 읽어도 다시 하지 말고 이어 가요(실제 회의처럼).\n\n";
  for (const [id, s] of Object.entries(SET)) {
    const r = DEV.find((x) => x.id === id);
    md += `## ${id} · ${r.type === "meeting" ? "회의" : r.type === "call" ? "통화" : "음성메모"} — ${s.note}\n\n` + r.segments.map((x) => `- ${x}`).join("\n") + "\n\n";
  }
  writeFileSync(join(HERE, "scripts.md"), md);
  console.log("scripts.md 저장");
}

function loadKey() {
  const p = join(ROOT, ".env");
  const m = existsSync(p) && readFileSync(p, "utf8").match(/^\s*OPENAI_API_KEY\s*=\s*(.+)\s*$/m);
  return process.env.OPENAI_API_KEY ?? (m ? m[1].trim() : null);
}

async function transcribe(key, file) {
  const form = new FormData();
  form.append("model", MODEL);
  form.append("language", "ko");
  form.append("file", new Blob([readFileSync(file)]), file.split(/[\\/]/).pop());
  const r = await fetch("https://api.openai.com/v1/audio/transcriptions", { method: "POST", headers: { authorization: `Bearer ${key}` }, body: form });
  const d = await r.json();
  if (!r.ok) throw new Error(d?.error?.message ?? `HTTP ${r.status}`);
  return d;
}

async function main() {
  if (process.argv.includes("--scripts")) return writeScripts();
  const key = loadKey();
  if (!key) { console.error("OPENAI_API_KEY가 .env에 없어요"); process.exit(2); }
  const out = [];
  for (const [id, s] of Object.entries(SET)) {
    const file = ["m4a", "mp3", "wav", "webm", "mp4"].map((e) => join(AUDIO_DIR, `${id}.${e}`)).find(existsSync);
    if (!file) { console.log(`${id}: 녹음 없음(건너뜀)`); continue; }
    if (statSync(file).size > 25 * 1024 * 1024) { console.log(`${id}: 25MB 초과 — 분할 필요(P1 처리기에서 구현)`); continue; }
    const ref = DEV.find((x) => x.id === id).segments.join(" ");
    const t0 = Date.now();
    const d = await transcribe(key, file);
    const hyp = d.text ?? "";
    const missed = s.terms.filter((alts) => !alts.some((a) => norm(hyp).includes(norm(a))));
    const row = { id, file: file.split(/[\\/]/).pop(), ms: Date.now() - t0, cer: cer(ref, hyp), terms: s.terms.length, missed: missed.map((a) => a[0]), usage: d.usage ?? null, text: hyp };
    out.push(row);
    console.log(`${id} CER ${(row.cer * 100).toFixed(1)}% · 핵심어 ${s.terms.length - missed.length}/${s.terms.length}${missed.length ? " (놓침: " + row.missed.join(", ") + ")" : ""}`);
  }
  if (!out.length) { console.log(`녹음 파일이 없어요. ${AUDIO_DIR}에 D01.m4a 등을 넣어 주세요.`); return; }
  // 비용은 공급자 응답의 길이 정보가 있으면 그걸로, 없으면 대본 길이로 어림하지 않고 비워 둔다
  const sec = out.reduce((n, r) => n + (r.usage?.seconds ?? 0), 0);
  mkdirSync(join(HERE, "runs"), { recursive: true });
  const f = join(HERE, "runs", `stt__${new Date().toISOString().replace(/[:.]/g, "-")}.json`);
  writeFileSync(f, JSON.stringify({ model: MODEL, created_at: new Date().toISOString(), results: out }, null, 2));
  const avg = out.reduce((n, r) => n + r.cer, 0) / out.length;
  console.log(`평균 CER ${(avg * 100).toFixed(1)}%${sec ? ` · 음성 ${(sec / 60).toFixed(1)}분 · 약 ₩${Math.round(sec / 60 * USD_PER_MIN * KRW)}` : ""}\n저장: ${f}`);
}

main().catch((e) => { console.error(e.message); process.exit(1); });
