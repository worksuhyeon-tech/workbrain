# WorkBrain 다음 작업

현재: Design Draft v0.1.6(2026-10-17). Design 승인 전. Do 미진입.

## 완료

- [x] Plan v0.2 문서(PRD/아키텍처/데이터/UX/로드맵/위험/추적표/개발 지침)
- [x] Design Draft(`docs/02-design/features/workbrain.design.md`)와 검토 사항(`DESIGN-OPEN-ISSUES.md`)
- [x] 화면 목업 9종 S01~S09(`docs/02-design/mockups/`)
- [x] 범위 추가: FR-28 검토 미루기, FR-29 Word 다운로드, FR-30 회의록 지침(SKILL.md 가져오기), FR-22 본인 메일 P1
- [x] 결정: Q2/Q4/Q6 기본안, Q7 실명 추정 허용(DEC-17), bkend 제외(DEC-18), 저가 모델 우선(DEC-19), Supabase Free(DEC-20)
- [x] G1/G2 문서 근거 조사
- [x] DEC-22: PARA 페이지 트리(1~4 사용자 폴더 이름)·업무 공간(P1 본업, 부업 P4)·할 일을 S03 트리로 통합, 목업 예시를 보험 보상 가상 데이터로 교체(Design v0.1.5)
- [x] DEC-23: 동선·중복 점검 반영 — 웹 기록 목록·업로드, 녹음 목업 하나로, 기록 상세에서 할 일 바로 추가, 오늘 화면 축소, 빠른 입력(TC-26), 캘린더·매트릭스 자리(P4), 내 할 일/팔로업(Design v0.1.6)

## Design 승인 전

- [x] Q0 교차 확인(Codex, 2026-10-17): 원본 브리프 §0~10을 v0.1.3과 대조. 결과는 `docs/02-design/DESIGN-OPEN-ISSUES.md` G절
- [x] Q0 보완(Claude, 2026-10-17): M10~M21·C7~C10·U6~U7을 Design v0.1.4·PRD·TC에 반영, Q8~Q10은 DEC-21로 종결. 반영 위치는 `DESIGN-OPEN-ISSUES.md` G.8. **실측은 P0/P1에서 별도**
- [ ] (선택) Codex 재확인: v0.1.4 반영이 G절 지적을 닫는지
- [ ] 사용자 Design 검토 및 승인(Q5) → `docs/.pdca-status.json` 갱신

## Design 승인 후 (P0 기술 검증, 외부 자원 생성은 별도 승인)

- [ ] Supabase 프로젝트 생성(서울 리전 확인), RLS 격리·동시 승인·백업 복원 실측(TC-01/08/12)
- [ ] gpt-6-luna vs Haiku 5.5 한국어 회의록 품질·비용 비교(DEC-19), gpt-transcribe 한국어 전사 품질
- [ ] 실기기 Fold 60분 녹음·중단 손실(G3)
- [ ] 메일 발송 연결(Gmail SMTP 앱 비밀번호) — 비밀값 등록·첫 발송 승인

## 이후 단계

- [ ] P1a 기반 → P1b 수집 → P1c MVP 구현/검증
- [ ] P2~P5 범위별 별도 PDCA

외부 서비스 생성·유료 가입·배포·실제 이메일 발송·GitHub 게시는 각각 사용자 승인 후에만 수행한다.
