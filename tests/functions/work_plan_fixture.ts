// 업무계획(FR-33) 시험·견본용 합성 자료. 실제 동료·회사·프로젝트 이름을 쓰지 않는다(공개 저장소).
// 기준일 2026-11-10(화): 전주 칸 = 11/9(월)~11/15(일), 금주 칸 = 11/16(월)~11/22(일)
import type { PlanInput } from "../../supabase/functions/_shared/work-plan.ts";

export const SAMPLE_INPUT: PlanInput = {
  kind: "weekly",
  baseDate: "2026-11-10",
  ownerName: "김하늘",
  places: [
    { id: "p-crm", type: "project", name: "CRM 이관 12건", archived: false,
      stateSummary: "12건 중 8건 이관 끝. 남은 4건은 정산 규칙 확정을 기다리는 중." },
    { id: "p-mobile", type: "project", name: "모바일 보상 접수 개편", archived: false, stateSummary: null },
    { id: "p-old", type: "project", name: "콜센터 연동", archived: true, stateSummary: "연동 끝, 운영 이관함." },
    { id: "p-idle", type: "project", name: "약관 정비", archived: false, stateSummary: "검토 순서만 정함." },
    { id: "a-ops", type: "area", name: "보상 운영", archived: false, stateSummary: null },
  ],
  tasks: [
    // CRM: 이번 주 완료 2(하나는 회의에서 나옴) · 이번 주 예정 1 · 팔로업 1 · 다음 주 1
    { id: "t1", title: "이관 대상 목록 확정", status: "done", ownerKind: "me", assignee: null,
      dueDate: "2026-11-11", dueTime: null, dueAmbiguous: false, dueText: null, doneAt: "2026-11-11T02:00:00Z", placeId: "p-crm",
      source: { recordId: "r-crm", title: "이관 점검 회의", at: "2026-11-09T01:00:00Z" } },
    { id: "t2", title: "정산 규칙 초안 공유", status: "done", ownerKind: "me", assignee: null,
      dueDate: null, dueTime: null, dueAmbiguous: false, dueText: null, doneAt: "2026-11-10T08:30:00Z", placeId: "p-crm", source: null },
    { id: "t3", title: "정산 규칙 검토", status: "open", ownerKind: "me", assignee: null,
      dueDate: "2026-11-13", dueTime: null, dueAmbiguous: false, dueText: null, doneAt: null, placeId: "p-crm",
      source: { recordId: "r-crm", title: "이관 점검 회의", at: "2026-11-09T01:00:00Z" } },
    { id: "t4", title: "이관 일정 회신", status: "open", ownerKind: "other", assignee: "박지훈",
      dueDate: "2026-11-12", dueTime: null, dueAmbiguous: false, dueText: null, doneAt: null, placeId: "p-crm", source: null },
    { id: "t5", title: "남은 4건 이관 테스트", status: "open", ownerKind: "me", assignee: null,
      dueDate: "2026-11-18", dueTime: null, dueAmbiguous: false, dueText: null, doneAt: null, placeId: "p-crm", source: null },
    // 모바일: 다음 주만(시각 있는 기한·모호한 기한) → 전주 칸에는 회의만
    { id: "t6", title: "화면 설계서 검토 회의", status: "open", ownerKind: "me", assignee: null,
      dueDate: null, dueTime: "2026-11-17T06:00:00Z", dueAmbiguous: false, dueText: null, doneAt: null, placeId: "p-mobile", source: null },
    { id: "t7", title: "접수 항목 정의서 받기", status: "open", ownerKind: "other", assignee: "이도윤",
      dueDate: "2026-11-20", dueTime: null, dueAmbiguous: true, dueText: "다음 주 중", doneAt: null, placeId: "p-mobile", source: null },
    // 보관한 프로젝트: 이번 주 완료는 실적이라 넣고, 안 끝난 일은 뺀다
    { id: "t8", title: "운영 이관 확인서 제출", status: "done", ownerKind: "me", assignee: null,
      dueDate: null, dueTime: null, dueAmbiguous: false, dueText: null, doneAt: "2026-11-09T05:00:00Z", placeId: "p-old", source: null },
    { id: "t9", title: "남은 문의 정리", status: "open", ownerKind: "me", assignee: null,
      dueDate: "2026-11-12", dueTime: null, dueAmbiguous: false, dueText: null, doneAt: null, placeId: "p-old", source: null },
    // 반복실행
    { id: "t10", title: "주간 지급 현황 정리", status: "done", ownerKind: "me", assignee: null,
      dueDate: "2026-11-10", dueTime: null, dueAmbiguous: false, dueText: null, doneAt: "2026-11-10T00:30:00Z", placeId: "a-ops", source: null },
    { id: "t11", title: "주간 지급 현황 정리", status: "open", ownerKind: "me", assignee: null,
      dueDate: "2026-11-17", dueTime: null, dueAmbiguous: false, dueText: null, doneAt: null, placeId: "a-ops", source: null },
    // 넣지 않는 것: 정리 안 됨, 지난주 완료, 다다음 주 기한, 기한 없는 안 끝난 일
    { id: "t12", title: "정리 안 된 메모", status: "open", ownerKind: "me", assignee: null,
      dueDate: "2026-11-12", dueTime: null, dueAmbiguous: false, dueText: null, doneAt: null, placeId: null, source: null },
    { id: "t13", title: "지난주 끝낸 일", status: "done", ownerKind: "me", assignee: null,
      dueDate: null, dueTime: null, dueAmbiguous: false, dueText: null, doneAt: "2026-11-08T14:00:00Z", placeId: "p-crm", source: null },
    { id: "t14", title: "다다음 주 일", status: "open", ownerKind: "me", assignee: null,
      dueDate: "2026-11-24", dueTime: null, dueAmbiguous: false, dueText: null, doneAt: null, placeId: "p-crm", source: null },
    { id: "t15", title: "기한 없는 일", status: "open", ownerKind: "me", assignee: null,
      dueDate: null, dueTime: null, dueAmbiguous: false, dueText: null, doneAt: null, placeId: "p-crm", source: null },
  ],
  meetings: [
    // CRM 회의는 할 일 출처로 이미 '*'에 보이므로 따로 줄을 만들지 않는다
    { id: "r-crm", title: "이관 점검 회의", at: "2026-11-09T01:00:00Z", kind: "meeting", projectIds: ["p-crm"] },
    { id: "r-mobile", title: "접수 화면 1차 리뷰", at: "2026-11-12T05:00:00Z", kind: "meeting", projectIds: ["p-mobile"] },
    { id: "r-call", title: "IT 개발팀 통화", at: "2026-11-13T07:00:00Z", kind: "call", projectIds: ["p-mobile"] },
    // 지난주 회의는 넣지 않는다
    { id: "r-old", title: "지난주 회의", at: "2026-11-06T01:00:00Z", kind: "meeting", projectIds: ["p-mobile"] },
  ],
};
