import { create } from 'zustand'
import { persistDesktopPreferences } from './preferencePersistence'
export type Theme = 'light' | 'dark' | 'system'
export const useThemeStore=create<{theme:Theme;setTheme:(theme:Theme)=>void}>(()=>({theme:'light',setTheme:theme=>{void persistDesktopPreferences({theme})}}))
export function applyTheme(theme:Theme) { document.documentElement.dataset.theme=theme==='system'?(matchMedia('(prefers-color-scheme: dark)').matches?'dark':'light'):theme }
applyTheme(useThemeStore.getState().theme)
