import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react'
import { Modal } from './components'

export type ChoiceOption = { value: string; label: string }

/** App-owned selection UI: theme, back gesture and focus do not depend on an OEM popup. */
export function Choice({ label, value, options, onChange, disabled = false, setting }: {
  label: string; value: string | number; options: readonly ChoiceOption[]
  onChange: (value: string) => void; disabled?: boolean; setting?: string
}) {
  const [open, setOpen] = useState(false)
  const [focused, setFocused] = useState(0)
  const trigger = useRef<HTMLButtonElement>(null)
  const list = useRef<HTMLDivElement>(null)
  const prefix = useRef({ text: '', time: 0 })
  const id = useId()
  const selected = options.findIndex(option => option.value === String(value))
  const current = options[selected]?.label || String(value)
  useEffect(() => {
    if (!open) return
    const index = Math.max(0, selected)
    setFocused(index); prefix.current = { text: '', time: 0 }
    const frame = requestAnimationFrame(() => list.current?.querySelectorAll<HTMLElement>('[role="option"]')[index]?.focus())
    return () => cancelAnimationFrame(frame)
  }, [open, selected])
  const close = () => { setOpen(false); requestAnimationFrame(() => trigger.current?.focus({ preventScroll: true })) }
  const keyboard = (event: KeyboardEvent<HTMLDivElement>) => {
    const buttons = list.current?.querySelectorAll<HTMLButtonElement>('[role="option"]')
    if (!buttons?.length) return
    let next = focused
    if (event.key === 'ArrowDown') next = Math.min(buttons.length - 1, focused + 1)
    else if (event.key === 'ArrowUp') next = Math.max(0, focused - 1)
    else if (event.key === 'Home') next = 0
    else if (event.key === 'End') next = buttons.length - 1
    else if (event.key.length === 1 && event.key !== ' ' && !event.ctrlKey && !event.metaKey && !event.altKey) {
      const now = Date.now()
      prefix.current = { text: (now - prefix.current.time < 700 ? prefix.current.text : '') + event.key.toLocaleLowerCase(), time: now }
      const match = options.findIndex(option => option.label.toLocaleLowerCase().startsWith(prefix.current.text))
      if (match < 0) return
      next = match
    } else return
    event.preventDefault(); setFocused(next); buttons[next].focus()
  }
  return <div className="select-row">
    <span id={id + '-label'}>{label}</span>
    <button ref={trigger} type="button" className="choice-trigger" disabled={disabled} data-setting={setting}
      aria-labelledby={id + '-label'} aria-describedby={id + '-value'} aria-haspopup="dialog" aria-expanded={open}
      onClick={() => setOpen(true)}>
      <span id={id + '-value'}>{current}</span><svg width="18" height="18" viewBox="0 0 24 24" aria-hidden="true"><path d="m8 10 4 4 4-4" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"/></svg>
    </button>
    {open && <Modal title={label} onClose={close} className="choice-sheet">
      <div ref={list} role="listbox" aria-label={label} className="choice-list" onKeyDown={keyboard}>
        {options.map((option, index) => <button key={option.value} type="button" role="option" data-value={option.value}
          aria-selected={index === selected} tabIndex={index === focused ? 0 : -1} onFocus={() => setFocused(index)}
          onClick={() => { close(); onChange(option.value) }}>
          <span>{option.label}</span><svg width="20" height="20" viewBox="0 0 24 24" aria-hidden="true"><path d="m5 12 4 4L19 6" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/></svg>
        </button>)}
      </div>
    </Modal>}
  </div>
}
