import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  // 로그인 뒤 돌아오는 주소가 Supabase 허용 목록(http://localhost:5173)과 같아야 해서 포트를 고정한다
  server: { port: 5173, strictPort: true },
  test: { include: ['src/**/*.test.ts'] },
})
