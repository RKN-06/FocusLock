import React, { useEffect, useState } from 'react';
import type { Request } from './scheduling';

type Status = { enter_set: boolean; exit_set: boolean; updated_at: string | null };

/** Backup codes for a class where the teacher will have no phone or laptop. They are fixed, not rotating, so they are lower assurance than the QR. */
export function BackupCodes({ sessionId, ended, request }: { sessionId: number; ended: boolean; request: Request }) {
  const [status, setStatus] = useState<Status | null>(null);
  const [enter, setEnter] = useState('');
  const [exit, setExit] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  async function load() { try { setStatus(await request<Status>(`/sessions/${sessionId}/access-codes`)); } catch (e) { setError((e as Error).message); } }
  useEffect(() => { setEnter(''); setExit(''); setError(''); setNotice(''); void load(); }, [sessionId]);
  async function save(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true); setError(''); setNotice('');
    try {
      await request(`/sessions/${sessionId}/access-codes`, { method: 'PUT', body: JSON.stringify({ ...(enter.trim() ? { enter_code: enter.trim() } : {}), ...(exit.trim() ? { exit_code: exit.trim() } : {}) }) });
      setEnter(''); setExit(''); setNotice('Codes saved. Tell your students the codes in class.'); await load();
    } catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  }
  async function clear() {
    setBusy(true); setError(''); setNotice('');
    try { await request(`/sessions/${sessionId}/access-codes`, { method: 'DELETE' }); setNotice('Codes cleared.'); await load(); }
    catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  }
  return <div className="mini-card backup-codes">
    <h3>Backup class codes</h3>
    <p>For a class without your phone or laptop. Students type the enter code to check in, and the exit code to leave focus early.</p>
    {status && <div className="code-status"><span className={`flag ${status.enter_set ? 'ok' : 'low'}`}>Enter code {status.enter_set ? 'set' : 'not set'}</span><span className={`flag ${status.exit_set ? 'ok' : 'low'}`}>Exit code {status.exit_set ? 'set' : 'not set'}</span></div>}
    {error && <p className="error" role="alert">{error}</p>}
    {notice && <p className="success" role="status">{notice}</p>}
    {!ended && <form onSubmit={save}>
      <label>Enter code<input value={enter} onChange={e => setEnter(e.target.value)} minLength={6} maxLength={40} autoComplete="off" spellCheck={false} placeholder="At least 6 characters"/></label>
      <label>Exit code<input value={exit} onChange={e => setExit(e.target.value)} minLength={6} maxLength={40} autoComplete="off" spellCheck={false} placeholder="Different from the enter code"/></label>
      <div className="actions"><button disabled={busy || (!enter.trim() && !exit.trim())}>Save codes</button>{(status?.enter_set || status?.exit_set) && <button type="button" className="secondary" disabled={busy} onClick={() => void clear()}>Clear</button>}</div>
    </form>}
    <small className="muted-note">These codes do not change, so anyone told one outside the room could use it. Check-ins made this way are marked "Class code" and counted separately in your analytics.</small>
  </div>;
}
