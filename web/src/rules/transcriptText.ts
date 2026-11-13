/*
 * 받아쓴 글 가져오기(DEC-53, 앱 record/TranscriptText.kt·FileImporter.decodeText를 옮김 — 고치면 두 곳을 같이, DEC-102).
 * 클로바노트 형식: 1줄 제목, 2줄 "2026.10.08 목 오후 3:06 ・ 53분 38초", 3줄 작성자, 이후 "참석자 1 00:00" 줄 + 말한 문장 줄.
 * 삼성 음성 녹음 형식: 1줄 "AI로 생성된 콘텐츠입니다", 다음 글 줄이 제목, 이후 "발화자 1  (00:03)" 줄 + 말한 문장 줄(날짜·길이 없음).
 * 본문(body)에서는 화자 표시·시각 줄을 뗀다(익명 화자 번호로 담당을 추정하지 않게, NFR-02·DEC-27). 화자·시각은 전문 보기용 turns로 따로(DEC-54)
 */

/** 한 사람이 이어서 말한 차례. text는 여러 줄일 수 있다(서버 p_turns와 같은 모양) */
export interface Turn {
  speaker: string | null
  offsetMs: number | null
  text: string
}

export interface ParsedText {
  title: string | null
  /** 회의 시작 시각(서울, 클로바만) */
  startedAt: Date | null
  durationMs: number | null
  body: string
  turns: Turn[]
}

// "참석자 1 00:23", "김가상 1:02:03", 삼성 "발화자 1  (00:03)"
const SPEAKER_LINE = /^(.{1,30}?)\s+\(?((?:\d{1,2}:)?\d{1,2}:\d{2})\)?$/
const DATE_LINE = /^(\d{4})\.(\d{1,2})\.(\d{1,2})\s+\S+\s+(오전|오후)\s+(\d{1,2}):(\d{2})(?:\s*[・·]\s*(?:(\d+)시간\s*)?(?:(\d+)분\s*)?(?:(\d+)초)?)?/
const SAMSUNG_HEADER = 'AI로 생성된 콘텐츠입니다'

/** "00:23" → 23초, "1:02:03" → 1시간 2분 3초 */
const clockToMs = (clock: string) => clock.split(':').reduce((acc, part) => acc * 60 + Number(part), 0) * 1000

export function parseTranscriptText(raw: string): ParsedText {
  const lines = raw.replace(/^﻿/, '').split(/\r?\n/).map((l) => l.trim())
  const firstSpeaker = lines.findIndex((l) => SPEAKER_LINE.test(l))

  // 삼성은 안내 문구와 제목 사이에 빈 줄을 두기도 해서, 안내 문구 뒤 첫 글 줄을 제목으로 쓴다
  if (lines[0] === SAMSUNG_HEADER && firstSpeaker > 1) {
    const title = lines.slice(1, firstSpeaker).find((l) => l !== '') ?? null
    return spoken(lines.slice(firstSpeaker), title, null, null)
  }

  const date = lines[1] ? DATE_LINE.exec(lines[1]) : null
  if (!date || firstSpeaker <= 0) return { title: null, startedAt: null, durationMs: null, body: lines.filter((l) => l !== '').join('\n'), turns: [] }

  const [, y, mo, d, ampm, h, mi, hh, mm, ss] = date
  const hour = (Number(h) % 12) + (ampm === '오후' ? 12 : 0)
  // 서울 시각 → 실제 시각(서울은 서머타임이 없어 +09:00 고정)
  const started = new Date(`${y}-${mo!.padStart(2, '0')}-${d!.padStart(2, '0')}T${String(hour).padStart(2, '0')}:${mi}:00+09:00`)
  const seconds = Number(hh ?? 0) * 3600 + Number(mm ?? 0) * 60 + Number(ss ?? 0)
  return spoken(lines.slice(firstSpeaker), lines[0] || null, Number.isNaN(started.getTime()) ? null : started, seconds > 0 ? seconds * 1000 : null)
}

/** 첫 화자 줄부터: 화자 줄마다 차례를 새로 열고, 그 아래 글 줄을 그 차례에 담는다 */
function spoken(fromFirstSpeaker: string[], title: string | null, startedAt: Date | null, durationMs: number | null): ParsedText {
  const turns: Turn[] = []
  for (const line of fromFirstSpeaker) {
    if (!line) continue
    const m = SPEAKER_LINE.exec(line)
    if (m) turns.push({ speaker: m[1]!.trim(), offsetMs: clockToMs(m[2]!), text: '' })
    else {
      const last = turns[turns.length - 1]!
      last.text = last.text ? `${last.text}\n${line}` : line
    }
  }
  const said = turns.filter((t) => t.text !== '')
  return { title, startedAt, durationMs, body: said.map((t) => t.text).join('\n'), turns: said }
}

/**
 * 클로바는 UTF-8, 삼성 음성 녹음은 UTF-16(리틀 엔디언)으로 내보낸다. BOM을 먼저 보고, 없으면 0바이트 위치로 가린다:
 * UTF-8 글에는 0바이트가 없고, UTF-16은 공백·숫자·줄바꿈의 위쪽 바이트가 0이라 LE면 홀수 자리, BE면 짝수 자리에 생긴다
 */
export function decodeText(raw: Uint8Array): string {
  const starts = (...b: number[]) => raw.length >= b.length && b.every((x, i) => raw[i] === x)
  let zeroOdd = 0
  let zeroEven = 0
  raw.forEach((x, i) => {
    if (x === 0) i % 2 === 1 ? zeroOdd++ : zeroEven++
  })
  if (starts(0xef, 0xbb, 0xbf)) return new TextDecoder('utf-8').decode(raw.subarray(3))
  if (starts(0xff, 0xfe)) return new TextDecoder('utf-16le').decode(raw.subarray(2))
  if (starts(0xfe, 0xff)) return new TextDecoder('utf-16be').decode(raw.subarray(2))
  if (zeroOdd > zeroEven) return new TextDecoder('utf-16le').decode(raw)
  if (zeroEven > zeroOdd) return new TextDecoder('utf-16be').decode(raw)
  return new TextDecoder('utf-8').decode(raw)
}
