// AI 계정 문제 가리기(키 만료·맞지 않는 키·크레딧, 11/18) 시험. 실행: npx -y deno@2.1.4 test tests/functions/ai_error_test.ts
import { assert, assertEquals } from "jsr:@std/assert@1";
import { accountKind, AI_ACCOUNT_MESSAGE, AiAccountError, openAiError } from "../../supabase/functions/process-record/ai-error.ts";

// 11/17~18 실제로 받은 응답 모양(키 만료)
const expired = { error: { message: "Your API key has expired. Create a new API key to continue.", type: "invalid_request_error", code: "invalid_api_key" } };

Deno.test("401은 키 문제: 만료라고 적혀 있으면 '기한 만료', 아니면 '맞지 않는 키'", () => {
  assertEquals(accountKind(401, expired), "key-expired");
  assertEquals(accountKind(401, { error: { message: "Incorrect API key provided: sk-...", code: "invalid_api_key" } }), "key-invalid");
  assertEquals(accountKind(401, null), "key-invalid");
});

Deno.test("429는 크레딧이 떨어졌을 때만 계정 문제(잠깐 몰린 것은 다시 시도할 일)", () => {
  assertEquals(accountKind(429, { error: { message: "You exceeded your current quota", type: "insufficient_quota", code: "insufficient_quota" } }), "quota");
  assertEquals(accountKind(429, { error: { message: "Rate limit reached", type: "requests", code: "rate_limit_exceeded" } }), null);
  assertEquals(accountKind(500, { error: { message: "server error" } }), null);
  assertEquals(accountKind(400, { error: { message: "Invalid file format." } }), null);
});

Deno.test("던질 오류: 계정 문제는 바로 알 수 있는 글, 그 밖은 '단계: 원문'", () => {
  const e = openAiError("용어 교정 실패", 401, expired);
  assert(e instanceof AiAccountError);
  assertEquals(e.message, "AI API 키 기한이 만료됐어요. 새 키를 넣은 뒤 '이어서 처리'를 누르면 돼요(다시 올리지 않아도 돼요)");
  const other = openAiError("전사 실패", 400, { error: { message: "Invalid file format." } });
  assert(!(other instanceof AiAccountError));
  assertEquals(other.message, "전사 실패: Invalid file format.");
  assertEquals(openAiError("분석 실패", 502, null).message, "분석 실패: 502");
  // 글은 모두 '다시 올리지 않아도 돼요'로 끝난다(지우고 다시 올리던 것을 막는다)
  Object.values(AI_ACCOUNT_MESSAGE).forEach((m) => assert(m.endsWith("(다시 올리지 않아도 돼요)")));
});
