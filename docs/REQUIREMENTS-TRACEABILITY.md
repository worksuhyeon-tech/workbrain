# WorkBrain 요구사항 추적표

버전 0.2 · 2026-10-16

원본 요구사항은 [PRD](01-PRD.md), 시험 정의는 [로드맵](05-ROADMAP.md), 화면은 [UX](04-UX-PLAN.md), 엔터티는 [데이터 모델](03-DATA-MODEL-PLAN.md)를 따른다. 아래 연결은 설계·검증 계획의 추적이며 구현 완료 표시가 아니다.

## 기능

| ID | 원본 요구사항 묶음 | 배치 | 주요 데이터 | 화면 | 시험 |
|---|---|---|---|---|---|
| FR-01 | 사용자 인증·세션 | P1 | User | S01 | TC-01 |
| FR-02 | 프로젝트·Areas·Resources·Archives·업무 공간·트리 정리 | P1 (부업 공간 추가 P4, AI 정리 제안 P2) | Workspace/Project/Area/Resource/TaskRevision | S03(PARA 트리, (+)·끌어서 옮기기) | TC-02 |
| FR-03 | 회의·음성메모 녹음, 중단 복구 | P1 | AudioAsset | S04 | TC-03 |
| FR-04 | 기존 파일·삼성 통화 파일·재시도 | P1 | SourceRecord/ProcessingJob | S04/S05(웹 기록 목록 업로드) | TC-04 |
| FR-05 | STT·원문 수정 | P1 | TranscriptVersion | S05 | TC-05 |
| FR-06 | 업무/개인/불확실·기존 프로젝트 연결·항목 단위·확신 등급 | P1 | SourceProjectLink(item_ref, confidence) | S05/S06 | TC-06 |
| FR-07 | 회의록 모든 항목·편집·재생성·버전 승인 | P1 | MeetingVersion/ApprovalEvent | S05/S06 | TC-07/TC-08/TC-09 |
| FR-08 | 기본 할 일·기한·우선순위·빠른 입력·담당 구분 | P1 | Task(project_id 또는 area_id, owner_kind) | S07(S03 트리 '모든 할 일')/S05(후보 바로 추가)/S02 | TC-07/TC-17/TC-26 |
| FR-09 | 기존 프로젝트 상태·할 일 변경 | P1 | ProjectStateVersion/ChangeProposal | S03/S06 | TC-08 |
| FR-10 | 중요 변경 승인·거절·일괄 확인 | P1 | ApprovalEvent | S06 | TC-08 |
| FR-11 | 출처·변경 이력·덮어쓰기 방지 | P1 | EvidenceLink/ActivityLog | S05/S06 | TC-09 |
| FR-12 | 오늘/지연/팔로업·기록별 승인 대기·처리 중 기록 | P1 | Task/ChangeProposal/ProcessingJob | S02 | TC-17 |
| FR-13 | 프로젝트·기간·기본 필터·키워드 | P1 | Project/MeetingVersion | S08 | TC-17 |
| FR-14 | PC/모바일 동기화·처리 상태 | P1 | ProcessingJob | S02/S05 | TC-13 |
| FR-15 | 기간별 보관·삭제·복구·내보내기 | P1 | DeletionRequest/Tombstone | S09 | TC-10/TC-11/TC-12 |
| FR-16 | 운영 비용·사용량·예산 대기 | P1 | CostLedger/BudgetReservation | S09 | TC-14 |
| FR-17 | 업로드 전 저장 허용·비식별화·외부 AI 전송 동의 | P1 | SourceRecord(storagePermission)/ConsentGrant/TranscriptVersion | S01/S04/S05/S09 | TC-06/TC-15 |
| FR-18 | 결정·요구·이슈·일정·위험 수명 관리 | P2 | Decision/Requirement/Issue | S03/S06 확장 | TC-18 |
| FR-19 | 의미 검색·근거·프로젝트 간 비교 | P2/P3 | KnowledgeItem/SearchChunk | S08 확장 | TC-18/TC-19 |
| FR-20 | AI 우선순위·브리핑·업무 부담 | P2 | KnowledgeItem/Task/Issue | S02 확장 | TC-18 |
| FR-21 | REST·MCP·ChatGPT/Claude/Claude Code/Codex | P3 | ExternalConnection/AccessAudit | S09 확장 | TC-19 |
| FR-22 | 회의록 이메일·수신 주소 설정 | P1 본인 발송 / P4 확장 | MeetingVersion/MailDispatch/Settings(mail_recipients) | S05/S09 | TC-24 / TC-20 |
| FR-23 | 반복·하위 작업·태그·고급 필터 | P4 | Task 확장 | S07 확장 | TC-21 |
| FR-24 | 알림·캘린더·Google 동기화 | P4 | 외부 일정 매핑 추가 | S02/S07(보기 전환 자리 P1)/S09 확장 | TC-20/TC-21 |
| FR-25 | 매트릭스·뽀모도로·습관·업무 통계 | P4 | 매트릭스는 Task.priority·기한으로 계산(추가 없음), 집중/습관 기록 추가 | S07 보기 전환(매트릭스), 나머지 P4 별도 화면 | TC-21 |
| FR-26 | 화자 분리 검토 | P2 선택 | TranscriptVersion 화자 매핑 | S05 확장 | TC-18 |
| FR-27 | 복수 사용자·프로젝트 공유 권한 | P1 격리/P5 공유 | User/멤버십 추가 | S09 확장 | TC-01/TC-22 |
| FR-28 | 검토 미루기·리마인드 | P1 미룸 / P4 알림 | ChangeProposal(snoozedUntil) | S06 | TC-08/TC-23 |
| FR-29 | 회의록 Word 다운로드 | P1 | MeetingVersion(document) | S05 | TC-24 |
| FR-30 | 회의록 작성 지침 가져오기(SKILL.md) | P1 | Settings(minutesGuideline)/MeetingVersion(guidelineRev) | S09/S05 | TC-25 |
| FR-31 | 내 용어집(업무 단어 등록) | P1 | vocab_term·wb_vocab_add | 더보기 > 내 용어집 | TC-27 |

## 비기능

| ID | 요구사항 | 관련 영역 | 시험 |
|---|---|---|---|
| NFR-01 | 권한·키·접근 로그 | 모든 API/저장소/검색 | TC-01/TC-19 |
| NFR-02 | 추출 정확도·근거·비허구 | STT/AI/승인 | TC-07/TC-18 |
| NFR-03 | 재시도·중복·복구 | 녹음/업로드/작업/승인 | TC-03/TC-04/TC-05/TC-08 |
| NFR-04 | 동기화 지연 | 웹/Android | TC-13 |
| NFR-05 | 접근성·Fold | 전체 화면 | TC-16 |
| NFR-06 | 개인정보·삭제·저장 암호화 | 보관/검색/백업/저장소 | TC-01/TC-10/TC-11/TC-12 |
| NFR-07 | 비용 제한 | 장부/작업 큐 | TC-14 |
| NFR-08 | AI 입력 안전 | 분석·출력 검증 | TC-15 |
| NFR-09 | 공급자 교체·백업 운영 | 어댑터/작업/내보내기 | TC-05/TC-12/TC-14 |

## 범위 제외 기록

PC 마이크 녹음, 무인 통화 직접 캡처, 무인 파일 감시, 실시간 STT, 오프라인 업무 편집·승인, 무승인 자동 반영은 현재 범위에서 제외한다. 외부 AI 쓰기 도구는 후속 독립 Plan이다. 개인정보를 완전히 탐지한다고 보장하거나 승인 없는 회사 자료 처리를 지원하지 않는다.

하위 작업·알림·프로젝트 간 비교·비식별화는 삭제된 요구사항이 아니라 위 표에 각각 FR-23/24/19/17로 배치되어 있다.
