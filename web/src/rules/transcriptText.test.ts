import { describe, expect, it } from 'vitest'
import { decodeText, parseTranscriptText } from './transcriptText'

// 예시는 모두 지어낸 것
const CLOVA = ['청구 화면 회의', '2026.10.08 목 오후 3:06 ・ 53분 38초', '작성자', '', '참석자 1 00:00', '시작할게요.', '오늘은 흐름도요.', '', '홍가상 01:02:03', '네, 제가 맡을게요.'].join('\n')

describe('받아쓴 글 가져오기(앱 TranscriptText)', () => {
  it('클로바노트: 제목·시작 시각·길이, 본문은 말한 문장만, 화자·시각은 따로', () => {
    const p = parseTranscriptText(CLOVA)
    expect(p.title).toBe('청구 화면 회의')
    expect(p.startedAt?.toISOString()).toBe('2026-10-08T06:06:00.000Z')
    expect(p.durationMs).toBe((53 * 60 + 38) * 1000)
    expect(p.body).toBe('시작할게요.\n오늘은 흐름도요.\n네, 제가 맡을게요.')
    expect(p.turns).toEqual([
      { speaker: '참석자 1', offsetMs: 0, text: '시작할게요.\n오늘은 흐름도요.' },
      { speaker: '홍가상', offsetMs: 3723000, text: '네, 제가 맡을게요.' },
    ])
  })

  it('삼성 음성 녹음: 안내 문구 뒤 첫 글 줄이 제목, 날짜·길이 없음, (00:03) 시각', () => {
    const p = parseTranscriptText(['AI로 생성된 콘텐츠입니다', '', '주간 점검', '발화자 1  (00:03)', '점검 시작합니다.'].join('\r\n'))
    expect(p.title).toBe('주간 점검')
    expect(p.startedAt).toBeNull()
    expect(p.turns).toEqual([{ speaker: '발화자 1', offsetMs: 3000, text: '점검 시작합니다.' }])
  })

  it('형식을 모르면 빈 줄만 빼고 글 그대로, 화자 없음', () => {
    const p = parseTranscriptText('﻿그냥 메모\n\n둘째 줄')
    expect(p).toEqual({ title: null, startedAt: null, durationMs: null, body: '그냥 메모\n둘째 줄', turns: [] })
  })

  it('글자 읽기: UTF-8(BOM 있음·없음)·UTF-16 LE(BOM 없음도 0바이트로 가림)', () => {
    const utf8 = new TextEncoder().encode('회의 12:00')
    expect(decodeText(utf8)).toBe('회의 12:00')
    expect(decodeText(new Uint8Array([0xef, 0xbb, 0xbf, ...utf8]))).toBe('회의 12:00')
    const le = (s: string) => new Uint8Array([...s].flatMap((c) => [c.charCodeAt(0) & 0xff, c.charCodeAt(0) >> 8]))
    expect(decodeText(new Uint8Array([0xff, 0xfe, ...le('발화자 1')]))).toBe('발화자 1')
    expect(decodeText(le('A 1 (00:03)'))).toBe('A 1 (00:03)')
  })
})
