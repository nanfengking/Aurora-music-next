import { t } from '../i18n'
import { useEffect, useRef, type ReactNode } from 'react';
import { Icon } from './Icon';
export function Modal({ title, children, onClose, className = '' }: {
    title: string;
    children: ReactNode;
    onClose: () => void;
    className?: string;
}) {
    const ref = useRef<HTMLDialogElement>(null);
    useEffect(() => {
        const dialog = ref.current!;
        dialog.showModal();
        return () => dialog.close();
    }, []);
    return <dialog ref={ref} className={`dialog ${className}`} aria-label={title} onCancel={(event) => { event.preventDefault(); onClose(); }} onClick={(event) => { if (event.target === ref.current)
        onClose(); }}>
    <div className="dialog-surface">
      <header className="dialog-header"><h2>{title}</h2><button className="icon-button" aria-label={t("关闭")} onClick={onClose}><Icon name="close"/></button></header>
      {children}
    </div>
  </dialog>;
}
