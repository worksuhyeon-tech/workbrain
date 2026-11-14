import type { ReactNode } from 'react'

/** 목업 레일·위 막대의 선 아이콘(24 격자, 굵기 2) */
function Stroke({ size = 18, children }: { size?: number; children: ReactNode }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      {children}
    </svg>
  )
}

export const Icons = {
  today: () => <Stroke><path d="M3 12h4l2 6 4-14 2 8h6" /></Stroke>,
  projects: () => <Stroke><rect x="3" y="4" width="18" height="16" rx="2" /><path d="M3 9h18" /></Stroke>,
  records: () => <Stroke><path d="M12 2a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3z" /><path d="M5 11a7 7 0 0 0 14 0M12 18v3" /></Stroke>,
  review: () => <Stroke><path d="M9 11l3 3 8-8" /><path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11" /></Stroke>,
  search: ({ size }: { size?: number } = {}) => <Stroke size={size}><circle cx="11" cy="11" r="7" /><path d="m21 21-4.3-4.3" /></Stroke>,
  settings: () => (
    <Stroke>
      <circle cx="12" cy="12" r="3" />
      <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" />
    </Stroke>
  ),
  theme: () => <Stroke><circle cx="12" cy="12" r="4" /><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" /></Stroke>,
  /** 속한 곳 표시(프로젝트 = 폴더, 반복실행 = 돌아가는 화살표) — 앱 placeIcon과 같은 구분 */
  folder: () => <Stroke size={13}><path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" /></Stroke>,
  repeat: () => <Stroke size={13}><path d="M17 2l4 4-4 4" /><path d="M3 11V9a3 3 0 0 1 3-3h15M7 22l-4-4 4-4" /><path d="M21 13v2a3 3 0 0 1-3 3H3" /></Stroke>,
  sub: () => <Stroke size={12}><path d="M6 4v8a4 4 0 0 0 4 4h8" /><path d="m15 13 3 3-3 3" /></Stroke>,
  /** 우선순위 높음: 채운 별(OneNote '중요' 태그처럼, 사용자 결정 2026-11-12) */
  star: () => (
    <svg width="15" height="15" viewBox="0 0 24 24" aria-hidden="true">
      <path fill="currentColor" d="M12 2.6l2.9 5.9 6.5.9-4.7 4.6 1.1 6.5L12 17.4l-5.8 3.1 1.1-6.5L2.6 9.4l6.5-.9z" />
    </svg>
  ),
  // PARA 트리·상세(앱 ParaGlyph·WbIcons와 같은 그림)
  folderBig: ({ size = 16 }: { size?: number } = {}) => <Stroke size={size}><path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" /></Stroke>,
  repeatBig: ({ size = 16 }: { size?: number } = {}) => <Stroke size={size}><path d="M17 2l4 4-4 4" /><path d="M3 11V9a3 3 0 0 1 3-3h15M7 22l-4-4 4-4" /><path d="M21 13v2a3 3 0 0 1-3 3H3" /></Stroke>,
  allTasks: ({ size = 16 }: { size?: number } = {}) => <Stroke size={size}><rect x="3" y="3" width="18" height="18" rx="3" /><path d="m8 12 3 3 5-6" /></Stroke>,
  building: () => <Stroke size={17}><rect x="5" y="3" width="14" height="18" rx="1" /><path d="M9 7h2M13 7h2M9 11h2M13 11h2M9 15h2M13 15h2M10 21v-3h4v3" /></Stroke>,
  chevron: ({ open }: { open: boolean }) => (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.2} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" style={{ transform: open ? 'rotate(90deg)' : undefined, transition: 'transform .15s' }}>
      <path d="m9 6 6 6-6 6" />
    </svg>
  ),
  mic: () => <Stroke size={14}><path d="M12 2a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3z" /><path d="M5 11a7 7 0 0 0 14 0M12 18v3" /></Stroke>,
  phone: () => <Stroke size={14}><path d="M5 4h4l2 5-2.5 1.5a11 11 0 0 0 5 5L15 13l5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2" /></Stroke>,
  pencil: () => <Stroke size={16}><path d="M4 20h4L19 9l-4-4L4 16z" /><path d="m13.5 6.5 4 4" /></Stroke>,
  download: () => <Stroke size={15}><path d="M12 4v12M7 11l5 5 5-5M5 20h14" /></Stroke>,
  upload: () => <Stroke size={15}><path d="M12 16V4M7 9l5-5 5 5M5 20h14" /></Stroke>,
  doc: () => <Stroke size={14}><path d="M6 3h8l4 4v14H6z" /><path d="M14 3v4h4M9 12h6M9 16h6" /></Stroke>,
  link: () => <Stroke size={14}><path d="M10 14a4 4 0 0 0 5.66 0l3-3a4 4 0 0 0-5.66-5.66l-1 1" /><path d="M14 10a4 4 0 0 0-5.66 0l-3 3a4 4 0 0 0 5.66 5.66l1-1" /></Stroke>,
  target: () => <Stroke size={15}><circle cx="12" cy="12" r="8" /><circle cx="12" cy="12" r="4" /><circle cx="12" cy="12" r="1" /></Stroke>,
  trash: () => <Stroke size={16}><path d="M4 7h16M10 11v6M14 11v6M5 7l1 13h12l1-13M9 7V4h6v3" /></Stroke>,
  /** 우선순위 낮음: 아래 화살표(Outlook '중요도 낮음'과 같은 모양) */
  low: () => <Stroke size={14}><path d="M12 5v14M6 13l6 6 6-6" /></Stroke>,
  // 할 일 목록 ⋮ 메뉴(앱 TaskListMenu): 더보기 · 정렬 깃발(우선순위순)·달력(기한순)
  more: () => (
    <svg width="20" height="20" viewBox="0 0 24 24" aria-hidden="true">
      <circle cx="12" cy="5" r="1.8" fill="currentColor" />
      <circle cx="12" cy="12" r="1.8" fill="currentColor" />
      <circle cx="12" cy="19" r="1.8" fill="currentColor" />
    </svg>
  ),
  flag: () => <Stroke size={17}><path d="M5 21V4M5 4h11l-2 4 2 4H5" /></Stroke>,
  calendar: ({ size = 17 }: { size?: number } = {}) => <Stroke size={size}><rect x="3" y="5" width="18" height="16" rx="2" /><path d="M3 10h18M8 3v4M16 3v4" /></Stroke>,
  /** 새 탭으로 열기(구글 캘린더 일정) */
  external: () => <Stroke size={13}><path d="M14 4h6v6M20 4l-9 9M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5" /></Stroke>,
}

export type Tint = 'green' | 'purple' | 'orange' | 'pink'

/**
 * 글래스모피즘 아이콘(목업 .si·.logo, docs/02-design/reference.png): 뒤에 색 판, 앞에 반투명 유리판, 그 위에 선 그림.
 * size는 한 변 px, 판이 오른쪽 아래로 offset만큼 비친다
 */
export function GlassIcon({ tint, size = 38, radius = 8, offset = 4, children }: { tint: Tint; size?: number; radius?: number; offset?: number; children: ReactNode }) {
  return (
    <div className="relative flex-none" style={{ width: size, height: size }}>
      <div className="absolute opacity-95" style={{ inset: `${offset}px -${offset}px -${offset}px ${offset}px`, borderRadius: radius, background: `var(--g-${tint})` }} />
      <div
        className="absolute inset-0 grid place-items-center border backdrop-blur-[6px]"
        style={{ borderRadius: radius, background: 'var(--glass-front)', borderColor: 'var(--glass-border)', color: 'var(--glass-ink)', boxShadow: 'inset 0 1px 0 rgba(255,255,255,.6)' }}
      >
        {children}
      </div>
    </div>
  )
}

/**
 * 브랜드 로고: 목업 그대로 초록 판 + 유리 앞판 + 🧠.
 * 아이콘을 이모지로 대신하지 않는 규칙의 예외 — 사용자가 앱 아이콘 그림보다 이게 낫다고 골랐다(2026-11-12)
 */
export function BrandLogo({ size = 34 }: { size?: number }) {
  const big = size >= 44
  return (
    <GlassIcon tint="green" size={size} radius={big ? 14 : 8} offset={big ? 5 : 4}>
      <span aria-hidden="true" style={{ fontSize: big ? 26 : 19, lineHeight: 1 }}>
        🧠
      </span>
    </GlassIcon>
  )
}
