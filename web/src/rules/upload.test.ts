import { describe, expect, it } from 'vitest'
import { checkFile, defaultKind, processNext, titleFromName } from './upload'

describe('올리기 파일 확인(앱 FileImporter)', () => {
  it('음성은 받아쓰기 형식만 25MB까지, mpeg는 mp3로', () => {
    expect(checkFile('회의.M4A', 1000)).toEqual({ kind: 'audio', ext: 'm4a' })
    expect(checkFile('a.mpeg', 1000)).toEqual({ kind: 'audio', ext: 'mp3' })
    expect(checkFile('a.mp3', 26 * 1024 * 1024).kind).toBe('rejected')
    expect(checkFile('a.aac', 10)).toEqual({ kind: 'rejected', reason: expect.stringContaining('이 형식(aac)') })
    expect(checkFile('확장자없음', 10)).toEqual({ kind: 'rejected', reason: expect.stringContaining('알 수 없음') })
  })

  it('받아쓴 글 txt는 1MB까지', () => {
    expect(checkFile('클로바.txt', 500_000)).toEqual({ kind: 'text' })
    expect(checkFile('클로바.txt', 2_000_000).kind).toBe('rejected')
  })

  it('제목·종류 처음 값', () => {
    expect(titleFromName('  주간 회의 1017.m4a')).toBe('주간 회의 1017')
    expect(titleFromName('.m4a')).toBeNull()
    expect(defaultKind('외주사 통화 녹음')).toBe('call')
    expect(defaultKind('주간 회의')).toBe('meeting')
  })
})

describe('처리 진행(앱 UploadManager.processLoop)', () => {
  it('검토 가능이면 끝, 키·예산 대기·더 시도 않는 실패면 멈춤', () => {
    expect(processNext({ stage: 'REVIEWABLE', state: 'DONE', error: null })).toMatchObject({ done: true, error: false })
    expect(processNext({ stage: 'STT', state: 'WAITING_KEY', error: null })).toMatchObject({ done: true, error: true })
    expect(processNext({ stage: 'EXTRACT', state: 'BUDGET_WAIT', error: null })).toMatchObject({ done: true, message: expect.stringContaining('예산') })
    expect(processNext({ stage: 'STT', state: 'FAILED', error: '녹음에서 말소리를 찾지 못했어요' })).toMatchObject({ done: true, message: '녹음에서 말소리를 찾지 못했어요' })
  })

  it('그 밖에는 단계 이름으로 계속, 일시 실패는 다시 시도 중', () => {
    expect(processNext({ stage: 'STT', state: 'RUNNING', error: null })).toEqual({ done: false, label: '받아쓰는 중' })
    expect(processNext({ stage: 'CORRECT', state: 'PENDING', error: null })).toEqual({ done: false, label: '업무 용어 맞추는 중' })
    expect(processNext({ stage: 'EXTRACT', state: 'FAILED', error: '잠시 오류' })).toEqual({ done: false, label: '회의록·할 일 정리 중 · 다시 시도 중' })
  })
})
