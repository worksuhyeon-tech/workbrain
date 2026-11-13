-- 0041: 업로드 전 저장 허용 질문 폐지(DEC-106, M11 철회 — 2026-11-13 사용자 결정)
-- 올릴 때마다 묻던 '이 자료를 클라우드에 올려도 되나요?'(개인·샘플·회사 허용 확인)를 앱·웹에서 뺀다.
-- 이 값은 저장만 되고 어떤 기능도 읽지 않았고, 업무 녹음 대부분을 사실과 다르게 고르게 만들었다(사용자).
-- 새 기록은 이 칸을 비워 둔다(NULL = 묻지 않음). 기존 기록의 값과 값 범위 제약(personal·sample·company-approved)은 그대로 둔다:
-- CHECK는 NULL을 통과시켜 바꿀 필요가 없고, 예전 앱이 보내는 값도 계속 받는다.
-- 데이터를 지우거나 바꾸지 않는다. 다시 실행해도 같은 결과(이미 NULL 허용이면 아무 일 없음)
ALTER TABLE public.source_record ALTER COLUMN storage_permission DROP NOT NULL;

COMMENT ON COLUMN public.source_record.storage_permission IS
  '예전 업로드 전 저장 허용 선택(personal·sample·company-approved, M11). 2026-11-13부터 묻지 않아 새 기록은 NULL(DEC-106)';
