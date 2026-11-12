import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './App'
import './index.css'

const root = document.getElementById('root')
if (!root) throw new Error('index.html에 #root가 없어요')

createRoot(root).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
