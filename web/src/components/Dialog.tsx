import type { ReactNode } from 'react'
import { cn } from '../lib/cn'

/**
 * 가운데 작은 창(목업 .modal): 어두운 바탕을 누르거나 Esc면 닫힌다. Esc는 이 창만 닫고 뒤 화면(선택 모드 등)까지 번지지 않게 한다.
 * body는 부르는 쪽이 여백까지 정하고(목록 창은 줄이 끝까지 닿게), actions는 아래 단추 줄
 */
export function Dialog({
  title,
  onDismiss,
  children,
  actions,
  bodyClassName = 'px-5',
}: {
  title: string
  onDismiss: () => void
  children: ReactNode
  actions?: ReactNode
  bodyClassName?: string
}) {
  return (
    <div className="fixed inset-0 z-50 grid place-items-center bg-[rgba(9,20,30,.4)] p-4" onMouseDown={(e) => e.target === e.currentTarget && onDismiss()}>
      <div
        role="dialog"
        aria-modal="true"
        aria-label={title}
        onKeyDown={(e) => {
          if (e.key === 'Escape') {
            e.stopPropagation()
            onDismiss()
          }
        }}
        className="flex max-h-[80vh] w-full max-w-[440px] flex-col rounded-xl border border-border bg-card py-4 shadow-lg"
      >
        <h2 className="px-5 pb-2 text-[17px] font-bold">{title}</h2>
        {/* 위아래 여백(py-1): 스크롤 칸이 맨 위·아래 입력 칸의 파란 포커스 테두리를 자르지 않게(11/13 사용자 제보) */}
        <div className={cn('overflow-y-auto py-1', bodyClassName)}>{children}</div>
        {actions && <div className="mt-3 flex items-center justify-end gap-2 border-t border-border px-4 pt-3">{actions}</div>}
      </div>
    </div>
  )
}

export const TEXT_BUTTON = 'rounded-full px-4 py-2 text-[15px] font-semibold text-link hover:bg-surface disabled:text-muted'
export const PRIMARY_BUTTON = 'h-10 rounded-full bg-primary px-5 text-sm font-semibold text-on-primary hover:bg-primary-hover disabled:opacity-50'
export const SECONDARY_BUTTON = 'h-10 rounded-full border border-border-strong bg-card px-4 text-sm font-semibold text-body hover:bg-surface disabled:opacity-50'

/** 한 번 더 묻는 창(버리기 등). danger면 확인 단추가 빨강 */
export function ConfirmDialog({
  title,
  text,
  confirmLabel,
  cancelLabel = '취소',
  danger = false,
  onConfirm,
  onDismiss,
}: {
  title: string
  text: ReactNode
  confirmLabel: string
  cancelLabel?: string
  danger?: boolean
  onConfirm: () => void
  onDismiss: () => void
}) {
  return (
    <Dialog
      title={title}
      onDismiss={onDismiss}
      actions={
        <>
          <button type="button" onClick={onDismiss} className={TEXT_BUTTON}>
            {cancelLabel}
          </button>
          <button type="button" autoFocus onClick={onConfirm} className={cn(TEXT_BUTTON, danger && 'text-crit')}>
            {confirmLabel}
          </button>
        </>
      }
    >
      <p className="text-sm leading-relaxed text-body">{text}</p>
    </Dialog>
  )
}
