import React, { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';

export type AlertItem = { id: number; text: string; at: number };

/** Functions the Android app exposes to the dashboard when it hosts it. */
export type AndroidBridge = { alert?: (title: string, text: string) => void; token?: () => string; signOut?: () => void };
export const androidBridge = (): AndroidBridge | undefined => (window as unknown as { FocusLockAndroid?: AndroidBridge }).FocusLockAndroid;

let armed = false;
/** Called once a teacher is signed in, so the first click can ask for permission to show system notifications. */
export function armNotifications() {
  if (armed) return;
  armed = true;
  const ask = () => {
    window.removeEventListener('pointerdown', ask);
    if ('Notification' in window && Notification.permission === 'default') { try { void Notification.requestPermission(); } catch { /* In-page banners still work. */ } }
  };
  window.addEventListener('pointerdown', ask);
}

/** A system notification that stays until dismissed (drawn by the OS, so it can appear over other apps), and a heads-up notification in the Android app. */
export function notifyOs(text: string) {
  try { androidBridge()?.alert?.('FocusLock alert', text); } catch { /* The page banner still shows. */ }
  if ('Notification' in window && Notification.permission === 'granted') {
    try { new Notification('FocusLock alert', { body: text, tag: `focuslock-${Date.now()}`, requireInteraction: true } as NotificationOptions & { requireInteraction?: boolean }); } catch { /* Ignored. */ }
  }
}

const clock = (value: number) => new Date(value).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });

/** A banner pinned to the top of the page, so an alert is seen even when the teacher is busy elsewhere on the screen. */
export function AlertBanner({ text, earlier, onView, onDismiss }: { text: string; earlier: number; onView: () => void; onDismiss: () => void }) {
  return <div className="alert-banner" role="alert" aria-live="assertive">
    <span className="alert-banner-icon" aria-hidden="true">!</span>
    <div className="alert-banner-text"><strong>Needs your attention</strong><span>{text}</span>{earlier > 0 && <small>{earlier} earlier alert{earlier === 1 ? '' : 's'} are in the pop-out list</small>}</div>
    <div className="actions"><button type="button" onClick={onView}>View activity</button><button type="button" className="secondary" onClick={onDismiss}>Dismiss</button></div>
  </div>;
}

type PipWindow = Window;
type PipApi = { requestWindow: (options: { width: number; height: number }) => Promise<PipWindow> };
const pipApi = (): PipApi | undefined => (window as unknown as { documentPictureInPicture?: PipApi }).documentPictureInPicture;

/** Opens the alerts in a small always-on-top window (Chrome and Edge), so they stay visible above a slideshow or any other app. */
export function PopOutAlerts({ items, onDismissAll }: { items: AlertItem[]; onDismissAll: () => void }) {
  const [pip, setPip] = useState<PipWindow | null>(null);
  const [unsupported, setUnsupported] = useState(false);
  const closing = useRef(false);
  useEffect(() => () => { if (pip && !closing.current) pip.close(); }, [pip]);
  async function toggle() {
    if (pip) { closing.current = true; pip.close(); setPip(null); return; }
    const api = pipApi();
    if (!api) { setUnsupported(true); return; }
    try {
      const popup = await api.requestWindow({ width: 380, height: 260 });
      document.querySelectorAll('link[rel="stylesheet"]').forEach(link => popup.document.head.appendChild(link.cloneNode(true)));
      popup.document.title = 'FocusLock alerts';
      const theme = document.documentElement.getAttribute('data-theme');
      if (theme) popup.document.documentElement.setAttribute('data-theme', theme);
      closing.current = false;
      popup.addEventListener('pagehide', () => { closing.current = true; setPip(null); });
      setPip(popup);
    } catch { setUnsupported(true); }
  }
  return <>
    <button className="secondary" type="button" aria-pressed={pip !== null} onClick={() => void toggle()}>{pip ? 'Close pop-out alerts' : 'Pop out alerts'}</button>
    {unsupported && <small className="muted-note">Pop-out windows need Chrome or Edge. System notifications still work.</small>}
    {pip && createPortal(<div className="popout">
      <div className="popout-head"><strong>FocusLock alerts</strong><button type="button" className="secondary" onClick={onDismissAll} disabled={!items.length}>Clear</button></div>
      {!items.length && <p className="popout-empty">No alerts. This window stays on top while you present.</p>}
      {items.slice(0, 6).map((item, index) => <div key={item.id} className={`popout-item ${index === 0 ? 'new' : ''}`}><span>{clock(item.at)}</span><p>{item.text}</p></div>)}
    </div>, pip.document.body)}
  </>;
}
