import { create } from 'zustand'
export type Theme = 'light' | 'dark' | 'system'
function initial():Theme { try { const value=localStorage.getItem('aurora-theme'); return value==='dark'||value==='system'?value:'light' } catch { return 'light' } }
export const useThemeStore=create<{theme:Theme;setTheme:(theme:Theme)=>void}>(set=>({theme:initial(),setTheme:theme=>{try{localStorage.setItem('aurora-theme',theme)}catch{};set({theme})}}))
export function applyTheme(theme:Theme) { document.documentElement.dataset.theme=theme==='system'?(matchMedia('(prefers-color-scheme: dark)').matches?'dark':'light'):theme }
applyTheme(useThemeStore.getState().theme)
