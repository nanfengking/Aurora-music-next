import { create } from 'zustand'
import { getLocale, type Locale } from '../i18n'
import { persistDesktopPreferences } from './preferencePersistence'

export const useLanguageStore = create<{ locale: Locale; changeLocale: (locale: Locale) => void }>(() => ({
  locale: getLocale(),
  changeLocale: locale => { void persistDesktopPreferences({ locale }) },
}))
