// 처리 함수의 지시문·출력 형식을 평가에 쓴 원본(eval/llm)에서 다시 만든다(DEC-28). 지시문을 바꾸면 평가 → 이 스크립트 → 배포 순서.
//   node scripts/gen-prompt.mjs
import { readFileSync, writeFileSync } from "node:fs";
const prompt = readFileSync("eval/llm/prompt-v2.md", "utf8");
const schema = JSON.parse(readFileSync("eval/llm/schema.json", "utf8"));
const out = "// 자동 생성: node 스크립트가 eval/llm/prompt-v2.md·schema.json에서 만든다. 직접 고치지 않는다(DEC-28).\n" +
  'export const PROMPT_VERSION = "prompt-v2";\n' +
  "export const SYSTEM_PROMPT = " + JSON.stringify(prompt) + ";\n" +
  "export const OUTPUT_SCHEMA = " + JSON.stringify(schema) + ";\n";
writeFileSync("supabase/functions/process-record/prompt.ts", out);
console.log("supabase/functions/process-record/prompt.ts", out.length);
