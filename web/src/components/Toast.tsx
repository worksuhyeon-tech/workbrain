import { useCallback, useEffect, useRef, useState } from 'react'

interface ToastState {
  text: string
  undo?: () => void
  key: number
}

/** 목업 .toast: 화면 아래 가운데 어두운 알림. 되돌리기가 있으면 오른쪽에 파란 글씨 단추. 2.8초 뒤 사라진다 */
export function useToast() {
  const [toast, setToast] = useState<ToastState | null>(null)
  const timer = useRef<number | undefined>(undefined)

  const show = useCallback((text: string, undo?: () => void) => {
    window.clearTimeout(timer.current)
    setToast({ text, undo, key: Date.now() })
    timer.current = window.setTimeout(() => setToast(null), 2800)
  }, [])

  useEffect(() => () => window.clearTimeout(timer.current), [])

  const element = toast ? (
    <div
      key={toast.key}
      role="status"
      aria-live="polite"
      className="fixed bottom-6 left-1/2 z-50 flex -translate-x-1/2 items-center gap-4 rounded-lg bg-dark-action px-[18px] py-3 text-[15px] font-semibold text-white shadow-lg"
    >
      <span>{toast.text}</span>
      {toast.undo && (
        <button
          type="button"
          className="font-bold text-[#7fd3ff]"
          onClick={() => {
            toast.undo?.()
            setToast(null)
          }}
        >
          되돌리기
        </button>
      )}
    </div>
  ) : null

  return { show, element }
}
