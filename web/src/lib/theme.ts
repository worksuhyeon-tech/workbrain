import { useEffect, useState } from 'react'

type Theme = 'light' | 'dark' | null

const KEY = 'wb-theme'

function readSaved(): Theme {
  try {
    const v = localStorage.getItem(KEY)
    return v === 'light' || v === 'dark' ? v : null
  } catch {
    return null
  }
}

/**
 * 위 막대 테마 단추(목업 #themeBtn). 고르기 전에는 기기 설정을 따르고(null), 고르면 이 브라우저에 기억한다.
 * 저장소를 못 쓰는 창(비공개 창 등)에서도 화면은 바뀌고 다음에 열 때만 잊는다
 */
export function useTheme(): { toggle: () => void } {
  const [theme, setTheme] = useState<Theme>(readSaved)

  useEffect(() => {
    const root = document.documentElement
    if (theme) root.setAttribute('data-theme', theme)
    else root.removeAttribute('data-theme')
  }, [theme])

  const toggle = () => {
    const nowDark = theme ? theme === 'dark' : window.matchMedia('(prefers-color-scheme: dark)').matches
    const next: Theme = nowDark ? 'light' : 'dark'
    setTheme(next)
    try {
      localStorage.setItem(KEY, next)
    } catch {
      // 저장 못 해도 지금 화면에는 적용된다
    }
  }
  return { toggle }
}
