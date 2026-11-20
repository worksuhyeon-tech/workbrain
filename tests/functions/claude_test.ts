// 할 일·결정 후보 뽑기 모델(소넷 5.5, DEC-113) 요청·응답 읽기 시험. 실행: npx -y deno test -A tests/functions/claude_test.ts
import { assert, assertEquals, assertThrows } from "jsr:@std/assert@1";
import {
  AFTER_EXTRACT_MS, claudeExtractBody, LUNA_EXTRACT_MS, readClaudeReply, REQUEST_BUDGET_MS,
} from "../../supabase/functions/process-record/claude.ts";
import { AiAccountError } from "../../supabase/functions/process-record/ai-error.ts";
import { EXTRACT_MODEL, EXTRACT_USD_PER_1M, llmUsd } from "../../supabase/functions/_shared/ai-cost.ts";

const usage = { input_tokens: 27000, output_tokens: 3500 };
// 생각 블록(빈 글) 뒤에 답 글이 온다
const ok = { id: "msg_1", stop_reason: "end_turn", usage, content: [{ type: "thinking", thinking: "" }, { type: "text", text: '{"actions":[]}' }] };

Deno.test("요청 본문: 도구 강제 호출 없이 응답 형식(JSON 스키마)으로 구조를 강제한다", () => {
  const body = claudeExtractBody("지시문", "입력", { type: "object" });
  assertEquals(body.model, EXTRACT_MODEL);
  assertEquals(body.output_config, { format: { type: "json_schema", schema: { type: "object" } } });
  assert(!("tool_choice" in body) && !("tools" in body));
  assertEquals(body.messages, [{ role: "user", content: "입력" }]);
});

Deno.test("답: 생각 블록은 건너뛰고 글 블록을 읽는다", () => {
  assertEquals(readClaudeReply(200, ok), { text: '{"actions":[]}', usage, id: "msg_1" });
});

Deno.test("거절·길이 초과·몰림·과부하는 luna로 다시 부를 일", () => {
  assertEquals(readClaudeReply(200, { ...ok, stop_reason: "refusal", content: [] }), { retry: "refusal", usage });
  assertEquals(readClaudeReply(200, { ...ok, stop_reason: "max_tokens" }), { retry: "max_tokens", usage });
  assertEquals(readClaudeReply(429, { type: "error", error: { type: "rate_limit_error", message: "rate" } }).hasOwnProperty("retry"), true);
  assertEquals(readClaudeReply(529, { type: "error", error: { type: "overloaded_error", message: "Overloaded" } }), { retry: "Anthropic 529", usage: { input_tokens: 0, output_tokens: 0 } });
  assertEquals(readClaudeReply(500, null).hasOwnProperty("retry"), true);
});

Deno.test("키·크레딧 문제는 기다리게(AiAccountError), 요청 잘못은 처리 실패", () => {
  const e = assertThrows(() => readClaudeReply(401, { type: "error", error: { type: "authentication_error", message: "invalid x-api-key" } }));
  assert(e instanceof AiAccountError && e.provider === "Anthropic" && e.kind === "key-invalid");
  const q = assertThrows(() => readClaudeReply(400, { type: "error", error: { message: "Your credit balance is too low to access the Anthropic API." } }));
  assert(q instanceof AiAccountError && q.kind === "quota");
  const bad = assertThrows(() => readClaudeReply(400, { type: "error", error: { message: "output_config: bad schema" } }));
  assert(!(bad instanceof AiAccountError));
  assertEquals((bad as Error).message, "분석 실패: output_config: bad schema");
});

Deno.test("끝났는데 글 블록이 없으면 형식 오류", () => {
  assertThrows(() => readClaudeReply(200, { ...ok, content: [{ type: "thinking", thinking: "" }] }), Error, "분석 결과 형식이 맞지 않아요");
});

Deno.test("비용: 소넷 단가로 센다(입력 2·출력 10달러/100만 토큰)", () => {
  assertEquals(llmUsd(usage, EXTRACT_USD_PER_1M), 0.054 + 0.035);
  assertEquals(llmUsd(usage), (27000 * 0.10 + 3500 * 0.50) / 1e6);   // 단가를 안 주면 luna
});

// 11/20 실제 회의 실측: 2시간 회의(1,566구간)에서 소넷 약 105초, luna 약 56초. 함수·앱은 150초에 끊는다
Deno.test("시간 나누기: 150초 안에서 긴 회의 소넷이 끝날 수 있고, 늦으면 다음 호출의 luna가 끝날 수 있다", () => {
  assert(REQUEST_BUDGET_MS < 150_000);
  assert(REQUEST_BUDGET_MS - AFTER_EXTRACT_MS >= 110_000);   // 소넷에 주는 시간
  assert(LUNA_EXTRACT_MS >= 56_000 && LUNA_EXTRACT_MS + AFTER_EXTRACT_MS <= REQUEST_BUDGET_MS);
});
