# WorkBrain 다음 작업

현재: Design Draft v0.1.3 작성 완료(2026-10-17). Design 승인 전. Do 미진입.

## 완료

- [x] Plan v0.2 문서(PRD/아키텍처/데이터/UX/로드맵/위험/추적표/개발 지침)
- [x] Design Draft(`docs/02-design/features/workbrain.design.md`)와 검토 사항(`DESIGN-OPEN-ISSUES.md`)
- [x] 화면 목업 9종 S01~S09(`docs/02-design/mockups/`)
- [x] 범위 추가: FR-28 검토 미루기, FR-29 Word 다운로드, FR-30 회의록 지침(SKILL.md 가져오기), FR-22 본인 메일 P1
- [x] 결정: Q2/Q4/Q6 기본안, Q7 실명 추정 허용(DEC-17), bkend 제외(DEC-18), 저가 모델 우선(DEC-19), Supabase Free(DEC-20)
- [x] G1/G2 문서 근거 조사

## Design 승인 전

- [ ] Q0: 원본 브리프 대비 누락 요구사항 교차 확인(Codex)
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
