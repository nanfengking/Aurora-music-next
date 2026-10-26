import { createRoot } from 'react-dom/client'
import { startMobile } from './bootstrap'
import './style.css'

startMobile(createRoot(document.getElementById('root')!))
