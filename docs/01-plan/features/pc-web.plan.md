# PC 웹 Plan — DEC-102

버전 0.1 · 2026-11-12 (Asia/Seoul) · 상태: **1단계 시작**

근거: [DEC-102](../../06-RISK-AND-DECISIONS.md) · [DEC-47](../../06-RISK-AND-DECISIONS.md)(3칸은 PC 웹만) · 웹 목업 `docs/02-design/mockups/workbrain-*.html` · PRD 첫 문단(PC 웹과 Android에서 프로젝트·회의록·할 일·승인을 모두 제공)

## 1. 목적과 성공 기준

- 목적: 회사·집 PC에서 할 일·프로젝트를 큰 화면으로 보고 고치고, 긴 회의록을 검토·승인하고, PC에 있는 녹음·받아쓴 글을 올린다. Android 앱과 같은 데이터·같은 규칙.
- 성공 기준: 단계마다 사용자가 PC에서 그 단계 화면을 실제로 써 보고 통과. 같은 입력에 앱과 같은 결과(규칙 시험 예시를 앱에서 그대로 옮겨 통과). 웹이 서버에 새 권한·새 비밀을 요구하지 않음.

## 2. 사용자 결정(2026-11-12, 모두 추천안)

| 항목 | 결정 |
|---|---|
| 1단계 범위 | 할 일 중심 + 검토·회의록 + 녹음 파일 올리기(업무계획 표 복사는 이번 범위 밖) |
| 순서 | ① 할 일 → ② 검토·회의록 → ③ 올리기. 단계마다 사용자 확인 뒤 다음 단계 |
| 기술 | Vite + React + TypeScript, Tailwind CSS, shadcn/ui. 서버 코드 없음 — 브라우저가 Supabase에 바로 붙는다(앱과 같은 RLS·RPC·처리 함수) |
| 규칙 코드 | 앱(Kotlin) 규칙을 TS로 옮기고, 앱 시험의 예시를 그대로 TS 시험(Vitest)으로 옮긴다. 앱 코드는 건드리지 않는다 |
| 호스팅 | Vercel 무료. ① 단계가 끝나면 바로 띄운다(그전에는 이 PC `localhost`에서만) |
| 올리기 | 녹음 파일 + 받아쓴 글(txt) 둘 다(앱 '파일 가져오기'와 같음) |

설계 원안(Design §1 그림)의 'Next.js 업무 API(/api/v1)'는 쓰지 않는다. Android가 이미 Supabase에 바로 붙어 서버 역할을 Supabase(RLS·RPC·Edge Function)가 맡고 있어서, 웹이 같은 길을 쓰는 편이 규칙이 한 곳(DB)에 남는다.

## 3. 구조

- 폴더 `web/`(저장소 맨 위). `web/src/`: `lib/`(supabase 클라이언트·세션), `data/`(앱 `Server.kt`·`Para.kt`에 해당하는 읽기·쓰기), `rules/`(옮긴 규칙 + 시험), `screens/`(화면), `components/`(shadcn/ui와 공용 조각).
- 색·간격·글꼴은 웹 목업의 CSS 토큰(`--canvas`·`--primary`… 밝은/어두운)을 그대로 Tailwind 테마로. 레이아웃은 목업 `.app`(왼쪽 레일 240px + 위 막대 56px, 760px 아래는 레일 64px 아이콘만).
- 비밀: 브라우저에는 공개 키(publishable)와 프로젝트 주소만. 관리자 키는 쓰지 않는다. 공개 저장소이므로 `.env.local`은 git 제외, Vercel 환경 변수로 넣는다.
- 로그인: 앱과 같은 Supabase Google OAuth(브라우저 이동 방식). 허용 목록(본인 Gmail 하나)은 서버 그대로.

## 4. 단계

### ① 할 일(이번)
- 뼈대: 레일 메뉴(오늘·프로젝트·기록·검토함·설정 — ②③ 전에는 '준비 중'), 위 막대 🔍, Google 로그인·로그아웃, 세션 갱신(앱 DEC-96처럼 로그인이 정해질 때까지 요청을 기다림).
- 오늘(목업 dashboard): 오늘 할 일(지연·오늘·챙길 팔로업·오늘 완료), '상위 › 하위' 줄, 빠른 입력(QuickParse), 검토 대기 요약은 숫자만(②에서 연결).
- 프로젝트(목업 project): PARA 트리 + 상세(현재 상태 카드 읽기·할 일·관련 기록 목록), 할 일 수정 서랍, 하위 할 일(칩·펼침·추가·삭제), 선택해서 이동·삭제, 정렬.
- 모든 할 일(목업 tasks): 기간·담당 거르기, 기한 없음 접힌 줄.
- 검색(목업 search): `wb_search` 그대로.
- 옮길 규칙(앱 파일 → 시험): `tasks/QuickParse.kt`(QuickParseTest 40), `tasks/Subtasks.kt`(SubtaskTest 7), `tasks/TaskSelect.kt` 정렬(TaskSelectTest 4), `tasks/EarlierDone`(EarlierDoneTest 6), `tasks/Recipients`(RecipientsTest 3), `projects/DropRules.kt`(DropRulesTest 20, 웹은 끌어 놓기 대신 '이동' 먼저 — 끌기는 확인 뒤), `search/SearchQuery.kt`(SearchQueryTest 8), 오늘 묶기(`TodayScreen` dueDay·isLate).
- 끝나면: Vercel 프로젝트 만들기(사용자 승인) → Supabase 로그인 허용 주소에 Vercel 주소 추가(사용자 승인) → 사용자 확인.

### ② 검토·회의록
검토함(목업 review-inbox: 할 일 후보·연결 제안·현재 상태 제안 승인/거절/미루기), 기록 목록(목업 record), 기록 상세(목업 record-detail: 회의록 읽기·승인·Word 받기, 전사 보기, 참석자·분류), 현재 상태 반영.

### ③ 올리기
녹음 파일(m4a·mp3·wav·ogg·webm·flac, 앱과 같은 목록)·받아쓴 글 txt 올리기 → `source_record` 만들기 → 저장소 `audio/<uid>/<기록>/audio.<ext>` → `wb_record_ready` / `wb_record_text_ready` → 처리 상태 보기. 파일 크기 한도는 서버 저장소 설정을 따른다(시작 때 확인).

## 5. 서버 쪽 필요한 것(실행 전 사용자 승인)

- Supabase Auth 'Redirect URLs'에 `http://localhost:5173/**`(① 개발 중), Vercel 주소(① 끝). 앱용 기존 주소는 그대로 둔다.
- 스키마·함수 변경은 없을 것으로 본다. 필요해지면 그때 따로 묻는다.

## 6. 위험

| 위험 | 대응 |
|---|---|
| 앱과 웹 규칙이 어긋남 | 옮길 때 앱 시험 예시를 모두 TS 시험으로. 앱 규칙을 고칠 때는 같은 커밋에서 웹도 고치는 것을 todo에 적는다 |
| 로그인 뒤 다른 주소로 튕김 | Redirect URLs에 개발·배포 주소를 미리 넣고, 넣기 전에는 로그인 시험을 하지 않는다 |
| 공개 저장소에 비밀 | `.env.local` git 제외, 공개 키만 브라우저에. 시험·예시는 합성 이름 |
| 큰 녹음 파일 | ③ 시작 때 저장소 한도 확인, 넘으면 안내 |
