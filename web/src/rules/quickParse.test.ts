import { describe, expect, it } from 'vitest'
import { choosePlace, parseQuick } from './quickParse'
import type { Place } from './place'

/**
 * 앱 QuickParseTest.kt의 예시를 그대로 옮겼다(DEC-102 — 같은 입력에 같은 결과).
 * TC-26 빠른 입력. 오늘은 2026-11-06(금)으로 고정한다. 한 주는 월요일 시작(DEC-31)
 */
const friday = '2026-11-06'
const claim: Place = { type: 'PROJECT', id: 'p1', name: '사고접수 개편' }
const accident: Place = { type: 'PROJECT', id: 'p2', name: '사고 조사' }
const erp: Place = { type: 'PROJECT', id: 'p3', name: '01. 가상 ERP구축' }
const review: Place = { type: 'AREA', id: 'a1', name: '심사' }
const autoReview: Place = { type: 'AREA', id: 'a2', name: '자동심사' }
const places = [claim, accident, erp, review, autoReview]

const parse = (raw: string, o: { today?: string; def?: Place | null; noDueMeansToday?: boolean } = {}) =>
  parseQuick(raw, o.today ?? friday, places, o.def ?? null, o.noDueMeansToday ?? false)
const day = (m: number, d: number, y = 2026) => `${y}-${String(m).padStart(2, '0')}-${String(d).padStart(2, '0')}`

describe('QuickParse', () => {
  it('목업 예시 한 줄을 모두 알아본다', () => {
    const r = parse('내일 3시 판독 업체 미팅 !높음 #사고접수 @박지훈')
    expect(r.title).toBe('판독 업체 미팅')
    expect(r.dueDate).toBe(day(11, 7))
    expect(r.dueTime).toBe('15:00')
    expect(r.priority).toBe(1)
    expect(r.place).toEqual(claim)
    expect(r.assignee).toBe('박지훈')
    expect(r.warnings).toEqual([])
  })

  it('오늘 내일 모레', () => {
    expect(parse('오늘 보고').dueDate).toBe(friday)
    expect(parse('내일 보고').dueDate).toBe(day(11, 7))
    expect(parse('모레 보고').dueDate).toBe(day(11, 8))
  })

  it('시각 여러 모양', () => {
    expect(parse('오후 3시 반 회의').dueTime).toBe('15:30')
    expect(parse('15:00 회의').dueTime).toBe('15:00')
    expect(parse('3시 30분 회의').dueTime).toBe('15:30')
    expect(parse('8시 조찬').dueTime).toBe('08:00')
    expect(parse('12시 점심').dueTime).toBe('12:00')
    expect(parse('오전 12시 배치 확인').dueTime).toBe('00:00')
    expect(parse('03:00 배치 확인').dueTime).toBe('03:00')
  })

  it('시각만 적으면 오늘', () => {
    const r = parse('4시 통화')
    expect(r.dueDate).toBe(friday)
    expect(r.dueTime).toBe('16:00')
    expect(r.title).toBe('통화')
  })

  it('3시간은 시각이 아니다', () => {
    const r = parse('3시간 회의')
    expect(r.title).toBe('3시간 회의')
    expect(r.dueDate).toBeNull()
    expect(r.dueTime).toBeNull()
  })

  it('없는 시각은 제목에 남기고 경고', () => {
    const r = parse('25시 회의')
    expect(r.title).toBe('25시 회의')
    expect(r.dueTime).toBeNull()
    expect(r.warnings).toHaveLength(1)
  })

  it('M/D는 올해, 이미 지났으면 다음 해(DEC-70)', () => {
    expect(parse('12/24 송년회').dueDate).toBe(day(12, 24))
    expect(parse('11/6 정리').dueDate).toBe(friday)
    expect(parse('10/24 보고서').dueDate).toBe(day(10, 24, 2027))
    expect(parse('1/5 보고서').dueDate).toBe(day(1, 5, 2027))
  })

  it('없는 날짜는 제목에 남기고 경고', () => {
    const r = parse('13/40 회의')
    expect(r.title).toBe('13/40 회의')
    expect(r.dueDate).toBeNull()
    expect(r.warnings).toEqual(['13/40는 없는 날짜예요'])
    // 2026·2027년 모두 2/29가 없다
    expect(parse('2/29 회의').dueDate).toBeNull()
    expect(parse('4/31 회의').dueDate).toBeNull()
  })

  it('요일은 오늘 포함 가장 가까운 날', () => {
    expect(parse('금요일 정리').dueDate).toBe(friday)
    expect(parse('월요일 정리').dueDate).toBe(day(11, 9))
    expect(parse('일요일 정리').dueDate).toBe(day(11, 8))
  })

  it('이번 주 다음 주 요일(월요일 시작)', () => {
    expect(parse('다음 주 월요일 회의').dueDate).toBe(day(11, 9))
    expect(parse('다음주 금요일 회의').dueDate).toBe(day(11, 13))
    expect(parse('이번 주 수요일 회의').dueDate).toBe(day(11, 4))
    // 일요일에 말한 '다음 주 화요일'은 바로 다음 날 시작하는 주의 화요일(DEC-31)
    expect(parse('다음 주 화요일 회의', { today: day(11, 8) }).dueDate).toBe(day(11, 10))
  })

  it('모호한 기한은 날짜로 바꾸지 않는다', () => {
    const r = parse('다음 주 보고')
    expect(r.vague).toBe('다음 주')
    expect(r.dueDate).toBeNull()
    expect(r.title).toBe('보고')
    expect(parse('월말까지 정산').vague).toBe('월말')
    expect(parse('이번 달 정리').vague).toBe('이번 달')
  })

  it('모호한 기한에 시각이 붙으면 원문으로 함께 둔다', () => {
    const r = parse('다음 주 3시 미팅')
    expect(r.vague).toBe('다음 주 3시')
    expect(r.dueDate).toBeNull()
    expect(r.dueTime).toBeNull()
    expect(r.title).toBe('미팅')
  })

  it('조사 까지 에를 함께 뗀다', () => {
    const r = parse('금요일까지 보고서 제출')
    expect(r.dueDate).toBe(friday)
    expect(r.title).toBe('보고서 제출')
    expect(parse('3시에 미팅').dueTime).toBe('15:00')
    expect(parse('12/24까지 정리').dueDate).toBe(day(12, 24))
  })

  it('날짜·시각은 붙여 써도 된다(시간 띄고 본문)', () => {
    const r = parse('내일3시 미팅')
    expect(r.title).toBe('미팅')
    expect(r.dueDate).toBe(day(11, 7))
    expect(r.dueTime).toBe('15:00')
    const next = parse('다음주월요일3시 미팅')
    expect(next.title).toBe('미팅')
    expect(next.dueDate).toBe(day(11, 9))
    expect(next.dueTime).toBe('15:00')
    expect(parse('내일오후3시반 회의').dueTime).toBe('15:30')
    expect(parse('내일오후 3시 회의').dueTime).toBe('15:00')
    expect(parse('11/7오전10시 회의').dueTime).toBe('10:00')
    expect(parse('내일3시까지 보고').dueTime).toBe('15:00')
    expect(parse('다음주3시 미팅').vague).toBe('다음주 3시')
  })

  it('본문에 붙여 쓰면 알아보지 않는다', () => {
    for (const raw of ['내일3시미팅', '회의금요일3시 정리', '내일3시간 회의']) {
      const r = parse(raw)
      expect(r.title).toBe(raw)
      expect(r.dueDate).toBeNull()
      expect(r.dueTime).toBeNull()
    }
  })

  it('자정 전후와 월말 연말', () => {
    expect(parse('오늘 정리', { today: day(11, 7) }).dueDate).toBe(day(11, 7))
    expect(parse('내일 정리', { today: day(11, 30) }).dueDate).toBe(day(12, 1))
    expect(parse('내일 정리', { today: day(12, 31) }).dueDate).toBe(day(1, 1, 2027))
    expect(parse('1/1 정리', { today: day(12, 31) }).dueDate).toBe(day(1, 1, 2027))
  })

  it('우선순위', () => {
    expect(parse('!3 정리').priority).toBe(3)
    expect(parse('정리 !낮음').priority).toBe(3)
    expect(parse('정리').priority).toBe(2)
    expect(parse('정리 !4').title).toBe('정리 !4')
  })

  it('#은 띄어쓰기·대소문자 무시하고 이름 일부로 찾는다', () => {
    expect(parse('#erp 회의').place).toEqual(erp)
    expect(parse('#사고접수개편 회의').place).toEqual(claim)
    // 이름이 똑같은 곳이 먼저('심사'는 '자동심사'에도 들어 있다)
    expect(parse('#심사 회의').place).toEqual(review)
  })

  it('#이 여럿에 걸리거나 없으면 넣지 않고 경고', () => {
    const many = parse('#사고 회의', { def: review })
    expect(many.place).toEqual(review)
    expect(many.title).toBe('#사고 회의')
    expect(many.warnings).toHaveLength(1)
    expect(many.placeChoices).toEqual([claim, accident])
    expect(many.placeAsked).toBe('#사고')
    const none = parse('#없는곳 회의')
    expect(none.place).toBeNull()
    expect(none.title).toBe('#없는곳 회의')
    expect(none.warnings).toEqual(['#없는곳 프로젝트를 못 찾았어요'])
  })

  // DEC-80: 이름이 비슷한 프로젝트가 많을 때(합성 이름)
  const crm12: Place = { type: 'PROJECT', id: 'c1', name: 'CRM) 이관 12건' }
  const crm31: Place = { type: 'PROJECT', id: 'c2', name: 'CRM)3-1.협력사 정산' }
  const crm52: Place = { type: 'PROJECT', id: 'c3', name: 'CRM)5-2.고객 알림' }
  const crmPay: Place = { type: 'PROJECT', id: 'c4', name: 'CRM) 고객 정산' }
  const crm = [crm12, crm31, crm52, crmPay]
  const parseCrm = (raw: string) => parseQuick(raw, friday, crm, null)

  it('#조각 맞추기 - 영문·숫자·한글 덩어리가 이름에 순서대로 있으면 찾는다', () => {
    expect(parseCrm('#CRM12건 보고서').place).toEqual(crm12)
    expect(parseCrm('#crm3 일정').place).toEqual(crm31)
    expect(parseCrm('#crm3 일정').title).toBe('일정')
    expect(parseCrm('#이관 보고서').place).toEqual(crm12)
  })

  it('#여럿이면 띄어 쓴 다음 낱말까지 붙여 좁히고 그 낱말은 제목에서 뺀다', () => {
    const r = parseCrm('#CRM 12건 보고서 수정')
    expect(r.place).toEqual(crm12)
    expect(r.title).toBe('보고서 수정')
    expect(r.warnings).toEqual([])
    expect(r.placeChoices).toEqual([])
    // 하나로 정해진 뒤에는 그 이름에 이어 들어 있는 낱말만 더 뺀다
    expect(parse('#erp 회의').title).toBe('회의')
  })

  it('#하나로 정해진 뒤에도 이름에 이어 들어 있는 낱말은 이름으로 본다(DEC-82)', () => {
    const r = parseCrm('#CRM 이관 12건 자료 정리')
    expect(r.place).toEqual(crm12)
    expect(r.title).toBe('자료 정리')
    expect(parseCrm('#이관 12건 검토').title).toBe('검토')
    expect(parseCrm('#CRM 이관 12건 12건 개선안').title).toBe('12건 개선안')
    expect(parseCrm('#CRM 이관 개선안 정리').title).toBe('개선안 정리')
    expect(parseCrm('#CRM 이관 개선안 정리').place).toEqual(crm12)
  })

  it('#붙여서 안 맞는 낱말은 본문이라 멈추고 후보를 보여 준다', () => {
    const r = parseCrm('#CRM 내일 3시 회의')
    expect(r.place).toBeNull()
    expect(r.title).toBe('#CRM 회의')
    expect(r.dueDate).toBe(day(11, 7))
    expect(r.placeChoices).toEqual(crm)
    expect(r.placeAsked).toBe('#CRM')
    expect(r.warnings).toEqual(['#CRM: 맞는 곳이 4곳이에요 · 아래에서 골라 주세요'])
  })

  it('#후보를 고르면 입력이 띄어쓰기 뺀 정확한 이름으로 바뀌어 그곳에 들어간다', () => {
    const raw = '#CRM 내일 3시 회의'
    const picked = choosePlace(raw, '#CRM', crm31)
    expect(picked).toBe('#CRM)3-1.협력사정산 내일 3시 회의')
    const r = parseCrm(picked)
    expect(r.place).toEqual(crm31)
    expect(r.title).toBe('회의')
    expect(r.warnings).toEqual([])
  })

  it('#좁히다 여럿이 남으면 좁힌 낱말까지 바꾼다', () => {
    const r = parseCrm('#CRM 고객 회의')
    expect(r.placeChoices).toEqual([crm52, crmPay])
    expect(r.placeAsked).toBe('#CRM 고객')
    expect(choosePlace('#CRM  고객 회의', '#CRM 고객', crmPay)).toBe('#CRM)고객정산 회의')
  })

  it('#후보는 5곳까지, 더 많으면 더 적어 달라고 한다', () => {
    const many: Place[] = [1, 2, 3, 4, 5, 6, 7].map((i) => ({ type: 'PROJECT', id: `m${i}`, name: `팀 과제 ${i}` }))
    const r = parseQuick('#팀 회의', friday, many, null)
    expect(r.placeChoices).toEqual(many.slice(0, 5))
    expect(r.warnings).toEqual(['#팀: 맞는 곳이 7곳이에요 · 아래에서 고르거나 더 적어 주세요'])
  })

  it('#을 두 번 쓰면 처음 것만', () => {
    const r = parse('#사고접수 #erp 회의')
    expect(r.place).toEqual(claim)
    expect(r.title).toBe('#erp 회의')
    expect(r.warnings).toHaveLength(1)
  })

  it('#이 없으면 기본 곳', () => {
    expect(parse('회의', { def: accident }).place).toEqual(accident)
    expect(parse('회의').place).toBeNull()
  })

  it('낱말 중간의 #과 @는 건드리지 않는다', () => {
    const r = parse('C# 공부 a@b.com 메일')
    expect(r.title).toBe('C# 공부 a@b.com 메일')
    expect(r.place).toBeNull()
    expect(r.assignee).toBeNull()
  })

  it('오늘 탭은 기한이 없으면 오늘, 모호는 그대로', () => {
    expect(parse('보고서', { noDueMeansToday: true }).dueDate).toBe(friday)
    expect(parse('다음 주 보고서', { noDueMeansToday: true }).dueDate).toBeNull()
    expect(parse('내일 보고서', { noDueMeansToday: true }).dueDate).toBe(day(11, 7))
    expect(parse('보고서').dueDate).toBeNull()
  })

  it('제목은 200자까지', () => {
    const r = parse('가'.repeat(250))
    expect(r.title).toHaveLength(200)
    expect(r.warnings).toHaveLength(1)
  })

  it('빈 입력은 빈 제목', () => {
    expect(parse('   ').title).toBe('')
    expect(parse('내일 3시').title).toBe('')
  })

  // ── DEC-97 시간대 낱말·@ 안내 ──

  it('사용자 줄 모양 - 내일아침9시를 읽고 @가 프로젝트 이름이면 안내', () => {
    const r = parse('@사고접수 박지훈님한테 알림톡 생성 요청 내일아침9시', { noDueMeansToday: true })
    expect(r.title).toBe('박지훈님한테 알림톡 생성 요청')
    expect(r.dueDate).toBe(day(11, 7))
    expect(r.dueTime).toBe('09:00')
    expect(r.assignee).toBe('사고접수')
    expect(r.warnings).toEqual(['@사고접수는 담당자로 들어가요 · 프로젝트면 #사고접수'])
  })

  it('시간대 낱말 + 시각 - 아침 새벽은 오전, 저녁 밤은 오후, 점심은 그냥 시각', () => {
    const at = (raw: string) => {
      const r = parse(raw)
      return [r.dueTime, r.title]
    }
    expect(at('아침 9시 보고')).toEqual(['09:00', '보고'])
    expect(at('아침9시 보고')).toEqual(['09:00', '보고'])
    expect(at('아침 7시 운동')).toEqual(['07:00', '운동'])
    expect(at('새벽 2시 배치 확인')).toEqual(['02:00', '배치 확인'])
    expect(at('저녁 7시 회식')).toEqual(['19:00', '회식'])
    expect(at('저녁 7:30 회식')).toEqual(['19:30', '회식'])
    expect(at('밤 10시 배포')).toEqual(['22:00', '배포'])
    expect(at('점심 1시 약속')).toEqual(['13:00', '약속'])
    expect(at('점심 12시 약속')).toEqual(['12:00', '약속'])
    expect(at('12시 점심')).toEqual(['12:00', '점심'])
  })

  it('날짜와 시간대 낱말은 붙여 써도 띄어 써도 된다', () => {
    for (const raw of ['내일아침9시 보고', '내일 아침 9시 보고', '내일 아침9시 보고', '내일아침 9시 보고']) {
      const r = parse(raw)
      expect(r.dueDate, raw).toBe(day(11, 7))
      expect(r.dueTime, raw).toBe('09:00')
      expect(r.title, raw).toBe('보고')
    }
    const r = parse('금요일저녁7시 회식')
    expect([r.dueDate, r.dueTime]).toEqual([friday, '19:00'])
  })

  it('숫자 없이 날짜 바로 뒤 - 아침 9시 점심 13시 오후 16시 퇴근전 18시', () => {
    const at = (raw: string) => {
      const r = parse(raw)
      return [r.dueDate, r.dueTime, r.title]
    }
    expect(at('내일 아침 보고')).toEqual([day(11, 7), '09:00', '보고'])
    expect(at('내일아침 보고')).toEqual([day(11, 7), '09:00', '보고'])
    expect(at('내일 점심 약속')).toEqual([day(11, 7), '13:00', '약속'])
    expect(at('금요일 오후 보고')).toEqual([friday, '16:00', '보고'])
    expect(at('금요일오후 보고')).toEqual([friday, '16:00', '보고'])
    expect(at('11/12 퇴근전까지 정리')).toEqual([day(11, 12), '18:00', '정리'])
    expect(at('내일 퇴근 전 정리')).toEqual([day(11, 7), '18:00', '정리'])
  })

  it('숫자 없는 시간대 낱말이 날짜 뒤가 아니면 제목의 말', () => {
    const lunch = parse('팀 점심 메뉴 정하기')
    expect(lunch.title).toBe('팀 점심 메뉴 정하기')
    expect(lunch.dueDate).toBeNull()
    expect(lunch.dueTime).toBeNull()
    expect(parse('아침 보고').title).toBe('아침 보고')
    // 정해 둔 시각이 없는 저녁·밤은 날짜만 읽고 낱말은 제목에 둔다
    const dinner = parse('내일 저녁 회식')
    expect(dinner.dueDate).toBe(day(11, 7))
    expect(dinner.dueTime).toBeNull()
    expect(dinner.title).toBe('저녁 회식')
  })

  it('밤 12시는 그날 23시59분, 밤 1~5시는 새벽으로 적게 안내', () => {
    expect(parse('밤 12시 마감').dueTime).toBe('23:59')
    const r = parse('내일 밤 1시 배포')
    expect(r.dueTime).toBeNull()
    expect(r.title).toBe('밤 1시 배포')
    expect(r.warnings).toEqual(["밤 1시: '새벽 1시'로 적어 주세요"])
  })

  it('틀린 시각을 적었으면 정해 둔 시각으로 덮지 않는다', () => {
    const r = parse('내일 오후 25시 회의')
    expect(r.dueTime).toBeNull()
    expect(r.warnings).toEqual(['오후 25시: 없는 시각이에요'])
  })

  it('@가 사람 이름이면 안내 없음, 프로젝트·반복실행 이름이면 안내', () => {
    expect(parse('@박지훈 자료 요청').warnings).toEqual([])
    expect(parse('@심사 자료 요청').warnings).toEqual(['@심사는 담당자로 들어가요 · 프로젝트면 #심사'])
    // 한글이 아닌 이름은 받침을 몰라 '은(는)'
    expect(parse('@ERP 자료 요청').warnings).toEqual(['@ERP은(는) 담당자로 들어가요 · 프로젝트면 #ERP'])
  })
})
