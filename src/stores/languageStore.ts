import { create } from 'zustand'
import { getLocale, setLocale, type Locale } from '../i18n'

export const useLanguageStore = create<{ locale: Locale; changeLocale: (locale: Locale) => void }>((set) => ({
  locale: getLocale(),
  changeLocale: (locale) => { setLocale(locale); set({ locale }) },
}))
