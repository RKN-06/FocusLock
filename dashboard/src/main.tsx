import React, { useEffect, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import QRCode from 'qrcode';
import './styles.css';
import { AdminConsole } from './admin';
import { ScheduleSessions } from './scheduling';
import { ClassesView } from './classes';
import { TeacherReports } from './reports';
import { AlertBanner, PopOutAlerts, androidBridge, armNotifications, notifyOs } from './alerts';
import type { AlertItem } from './alerts';
import { BackupCodes } from './codes';

try {
  const savedTheme = localStorage.getItem('focuslock-theme');
  if (savedTheme === 'light' || savedTheme === 'dark') document.documentElement.setAttribute('data-theme', savedTheme);
} catch { /* Storage can be blocked; the system theme still applies. */ }

type User = { id: number; name: string; email: string; role: 'student' | 'teacher' | 'admin'; kind?: 'regular' | 'reregistered' | 'other' };
type Course = { id: number; code: string; title: string; term: string; groups?: { id: number; name: string; members: number }[]; students?: number };
type Session = { id: number; course_id: number; title: string; room_code: string; starts_at: string; ends_at: string; state: 'ready' | 'checkin_pending' | 'active' | 'paused' | 'ended' };
type Resource = { id: number; session_id: number; title: string; url: string; kind: 'pdf' | 'text'; downloadable: boolean };
type Attendance = { student_id: number; status: string; method: string };
type Pending = { student_id: number; reason: string; created_at: string };
type Audit = { id: number; student_id: number; actor_id: number; action: string; previous_status: string | null; new_status: string; reason: string | null; recorded_at: string };
type Challenge = { qr: string; expires_at: string; rotation_seconds: number };
type ExitRequest = { id: number; session_id: number; student_id: number; kind: 'exit' | 'resume'; status: string; created_at: string };
type FocusEvent = { id: string; student_id: number; action: string; recorded_at: string };
type PrivacyRequest = { id: number; student_id: number; session_id: number; kind: 'correction' | 'deletion'; reason: string };
type Auth = { access_token: string; user: User };
type AuthConfig = { development_login: boolean; college_oidc: boolean; authorization_endpoint: string | null; dashboard_client_id: string | null; dashboard_redirect_uri: string | null };
const date = (value: string) => new Date(value).toLocaleString([], { dateStyle: 'medium', timeStyle: 'short' });
const localInput = (value: Date) => new Date(value.getTime() - value.getTimezoneOffset() * 60_000).toISOString().slice(0, 16);
const sessionDay = (value: string) => localInput(new Date(value)).slice(0, 10);
const shiftDay = (day: string, delta: number) => { const next = new Date(`${day}T12:00:00`); next.setDate(next.getDate() + delta); return localInput(next).slice(0, 10); };
const stateLabel = (value: string) => value === 'checkin_pending' ? 'Check-in pending' : value.replace('_', ' ').replace(/^./, letter => letter.toUpperCase());
const url64 = (bytes: Uint8Array) => btoa(String.fromCharCode(...bytes)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
function playAlertTone(context: AudioContext) {
  const start = context.currentTime;
  [880, 660].forEach((frequency, index) => {
    const oscillator = context.createOscillator();
    const gain = context.createGain();
    oscillator.type = 'sine'; oscillator.frequency.value = frequency;
    oscillator.connect(gain); gain.connect(context.destination);
    const at = start + index * 0.19;
    gain.gain.setValueAtTime(0.0001, at);
    gain.gain.exponentialRampToValueAtTime(0.12, at + 0.015);
    gain.gain.exponentialRampToValueAtTime(0.0001, at + 0.17);
    oscillator.start(at); oscillator.stop(at + 0.18);
  });
}

let sharedAudio: AudioContext | null = null;
function unlockAudio() {
  try {
    if (!sharedAudio) sharedAudio = new AudioContext();
    if (sharedAudio.state !== 'running') void sharedAudio.resume();
  } catch { /* Sound is optional; in-page alerts still work. */ }
}
function alertSound() { if (sharedAudio?.state === 'running') playAlertTone(sharedAudio); }
window.addEventListener('pointerdown', unlockAudio);
window.addEventListener('keydown', unlockAudio);
// iOS Safari only unlocks audio from touchend or click, not pointerdown.
window.addEventListener('touchend', unlockAudio, { passive: true });
window.addEventListener('click', unlockAudio);

async function api<T>(path: string, token?: string, options: RequestInit = {}): Promise<T> {
  const response = await fetch('/api' + path, { ...options, headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}), ...options.headers } });
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new Error(response.status === 401 ? 'Sign-in expired or credentials are invalid. Please sign in again.' : typeof body.detail === 'string' ? body.detail : Array.isArray(body.detail) ? body.detail.map((item: { msg?: string }) => item.msg ?? 'Invalid input').join('; ') : `Request failed (${response.status}).`);
  }
  return response.status === 204 ? undefined as T : response.json();
}

type ThemeMode = 'system' | 'light' | 'dark';
function ThemeToggle() {
  const [mode, setMode] = useState<ThemeMode>(() => {
    try { const saved = localStorage.getItem('focuslock-theme'); return saved === 'light' || saved === 'dark' ? saved : 'system'; } catch { return 'system'; }
  });
  useEffect(() => {
    if (mode === 'system') document.documentElement.removeAttribute('data-theme'); else document.documentElement.setAttribute('data-theme', mode);
    try { localStorage.setItem('focuslock-theme', mode); } catch { /* The theme still applies for this visit. */ }
  }, [mode]);
  const next: ThemeMode = mode === 'system' ? 'light' : mode === 'light' ? 'dark' : 'system';
  const label = mode === 'system' ? 'Theme: follow system' : mode === 'light' ? 'Theme: light' : 'Theme: dark';
  return <button type="button" className="theme-toggle" aria-label={`${label}. Switch to ${next}.`} title={label} onClick={() => setMode(next)}>{mode === 'system' ? '◐' : mode === 'light' ? '☀' : '☾'}</button>;
}

function App() {
  const [auth, setAuth] = useState<Auth | null>(null);
  const [enabled, setEnabled] = useState<boolean | null>(null);
  const [college, setCollege] = useState<AuthConfig | null>(null);
  const callbackStarted = useRef(false);
  const [email, setEmail] = useState('teacher@focuslock.test');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    const token = androidBridge()?.token?.();
    if (token) api<User>('/me', token).then(user => setAuth({ access_token: token, user })).catch(() => { /* Falls back to the normal sign-in form. */ });
  }, []);
  useEffect(() => { api<AuthConfig>('/auth/config').then(c => { setEnabled(c.development_login); setCollege(c); }).catch(e => setError(e.message)); }, []);
  useEffect(() => {
    if (!college || callbackStarted.current) return;
    const params = new URLSearchParams(window.location.search);
    if (!params.has('code') && !params.has('error')) return;
    callbackStarted.current = true;
    const expected = sessionStorage.getItem('focuslock-oidc-state');
    const verifier = sessionStorage.getItem('focuslock-oidc-verifier');
    sessionStorage.removeItem('focuslock-oidc-state'); sessionStorage.removeItem('focuslock-oidc-verifier');
    window.history.replaceState(null, '', window.location.pathname);
    if (params.has('error') || !expected || !verifier || params.get('state') !== expected || !params.get('code')) {
      setError('College sign-in was cancelled or its state did not match.'); return;
    }
    setBusy(true);
    api<Auth>('/auth/oidc/exchange', undefined, { method: 'POST', body: JSON.stringify({ client: 'dashboard', code: params.get('code'), verifier }) })
      .then(setAuth).catch(e => setError((e as Error).message)).finally(() => setBusy(false));
  }, [college]);
  async function collegeSignIn() {
    if (!college?.college_oidc || !college.authorization_endpoint || !college.dashboard_client_id || !college.dashboard_redirect_uri) return;
    const verifier = url64(crypto.getRandomValues(new Uint8Array(48)));
    const state = url64(crypto.getRandomValues(new Uint8Array(32)));
    const challenge = url64(new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier))));
    sessionStorage.setItem('focuslock-oidc-state', state);
    sessionStorage.setItem('focuslock-oidc-verifier', verifier);
    const url = new URL(college.authorization_endpoint);
    url.searchParams.set('response_type', 'code'); url.searchParams.set('client_id', college.dashboard_client_id);
    url.searchParams.set('redirect_uri', college.dashboard_redirect_uri); url.searchParams.set('scope', 'openid profile email');
    url.searchParams.set('state', state); url.searchParams.set('code_challenge', challenge);
    url.searchParams.set('code_challenge_method', 'S256');
    window.location.assign(url.toString());
  }
  async function signIn(event: React.FormEvent) {
    event.preventDefault(); setBusy(true); setError('');
    try {
      const result = await api<Auth>('/auth/dev-login', undefined, { method: 'POST', body: JSON.stringify({ email, password }) });
      if (result.user.role !== 'teacher' && result.user.role !== 'admin') {
        await api('/auth/logout', result.access_token, { method: 'POST' });
        throw new Error('Use a teacher or administrator account here. Students use the Android app.');
      }
      setPassword(''); setAuth(result);
    } catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  }
  async function signOut() {
    try { if (auth) await api('/auth/logout', auth.access_token, { method: 'POST' }); }
    catch { /* Local credentials are always cleared; server tokens expire after eight hours. */ }
    setAuth(null);
    androidBridge()?.signOut?.();
  }
  return <><header><div className="brand">FocusLock<span>{auth?.user.role === 'admin' ? 'ADMINISTRATION' : 'TEACHER WORKSPACE'}</span></div><div className="account"><ThemeToggle/>{auth && <>{auth.user.name}<button className="secondary" onClick={signOut}>Sign out</button></>}</div></header>
    {enabled && <div className="dev-banner">Demo · sample data only</div>}
    {!auth ? <main className="login"><div><p className="eyebrow">Smart classroom management</p><h1>Smarter classrooms<br/>start here.</h1><p>Attendance, focus sessions and course resources, managed from a single workspace.</p></div>
      <div className="panel"><h2>Teacher sign-in</h2>{enabled ? <form onSubmit={signIn}><label>Email<input type="email" autoComplete="username" required value={email} onChange={e => setEmail(e.target.value)}/></label><label>Password<input type="password" autoComplete="current-password" required value={password} onChange={e => setPassword(e.target.value)}/></label><button disabled={busy}>{busy ? 'Signing in…' : 'Sign in'}</button></form> : <><p>Use your college account.</p><button disabled={!college?.college_oidc || !college.authorization_endpoint || busy} onClick={() => void collegeSignIn()}>Sign in with college</button>{!college?.college_oidc && <p role="status">College sign-in isn't set up yet.</p>}</>}{error && <p className="error" role="alert">{error}</p>}</div></main>
      : auth.user.role === 'admin' ? <AdminConsole request={<T,>(path: string, options?: RequestInit) => api<T>(path, auth.access_token, options)}/> : <Dashboard auth={auth}/>}
    <footer>A QR check-in is evidence of participation, not proof of physical presence.</footer></>;
}

function Dashboard({ auth }: { auth: Auth }) {
  const [courses, setCourses] = useState<Course[]>([]);
  const [course, setCourse] = useState<Course | null>(null);
  const [view, setView] = useState<'classes' | 'sessions' | 'reports'>('classes');
  const [roster, setRoster] = useState<User[]>([]);
  const [sessions, setSessions] = useState<Session[]>([]);
  const [dayFilter, setDayFilter] = useState('');
  const [deleteTarget, setDeleteTarget] = useState<'one' | 'all' | null>(null);
  const [deleteReason, setDeleteReason] = useState('');
  const [deleteConfirm, setDeleteConfirm] = useState('');
  const [session, setSession] = useState<Session | null>(null);
  const [attendance, setAttendance] = useState<Attendance[]>([]);
  const [pending, setPending] = useState<Pending[]>([]);
  const [audit, setAudit] = useState<Audit[]>([]);
  const [resources, setResources] = useState<Resource[]>([]);
  const [focusEvents, setFocusEvents] = useState<FocusEvent[]>([]);
  const [exitRequests, setExitRequests] = useState<ExitRequest[]>([]);
  const [soundEnabled, setSoundEnabled] = useState(true);
  const soundRef = useRef(true);
  soundRef.current = soundEnabled;
  const shownSession = useRef<number | null>(null);
  const [focusAlert, setFocusAlertState] = useState('');
  const [alertLog, setAlertLog] = useState<AlertItem[]>([]);
  useEffect(() => { armNotifications(); }, []);
  function setFocusAlert(text: string) {
    setFocusAlertState(text);
    if (text) { setAlertLog(log => [{ id: Date.now() + Math.random(), text, at: Date.now() }, ...log].slice(0, 12)); notifyOs(text); }
  }
  const [privacyRequests, setPrivacyRequests] = useState<PrivacyRequest[]>([]);
  const [resourceTitle, setResourceTitle] = useState('');
  const [resourceUrl, setResourceUrl] = useState('');
  const [resourceKind, setResourceKind] = useState<'pdf' | 'text'>('pdf');
  const [downloadable, setDownloadable] = useState(false);
  const [challenge, setChallenge] = useState<Challenge | null>(null);
  const [qrImage, setQrImage] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [loadingAttendance, setLoadingAttendance] = useState(false);
  const [saving, setSaving] = useState<number | null>(null);
  const [notice, setNotice] = useState('');
  const [section, setSection] = useState<'overview' | 'roster' | 'resources' | 'activity' | 'analytics'>('overview');
  const [rosterQuery, setRosterQuery] = useState('');
  const [rosterFilter, setRosterFilter] = useState<'all' | 'present' | 'absent' | 'unrecorded' | 'review'>('all');
  const [createOpen, setCreateOpen] = useState(false);
  const [privacyReview, setPrivacyReview] = useState<{ id: number; status: 'accepted' | 'declined' } | null>(null);
  const [privacyResolution, setPrivacyResolution] = useState('');
  const [confirmation, setConfirmation] = useState<{ studentId: number; status: string } | null>(null);
  const [confirmationReason, setConfirmationReason] = useState('');
  const selection = useRef(0);
  const token = auth.access_token;
  useEffect(() => {
    let active = true;
    api<Course[]>('/courses', token).then(rows => { if (active) { setCourses(rows); setCourse(rows[0] ?? null); } }).catch(e => { if (active) setError(e.message); }).finally(() => { if (active) setLoading(false); });
    api<PrivacyRequest[]>('/teacher/privacy/requests', token).then(rows => { if (active) setPrivacyRequests(rows); }).catch(() => {});
    return () => { active = false; };
  }, [token]);
  useEffect(() => {
    let active = true; selection.current++;
    setSession(null); setDayFilter(''); setDeleteTarget(null); setAttendance([]); setRoster([]); setSessions([]); setPending([]); setAudit([]); setChallenge(null); setNotice(''); setConfirmation(null); setSection('overview'); setCreateOpen(false);
    if (!course) return;
    setLoading(true); setError('');
    Promise.all([api<User[]>(`/courses/${course.id}/roster`, token), api<Session[]>(`/sessions?course_id=${course.id}`, token)])
      .then(([people, classes]) => { if (active) { setRoster(people); setSessions(classes); setSession(classes.at(-1) ?? null); } })
      .catch(e => { if (active) setError(e.message); }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [course, token]);
  useEffect(() => {
    let active = true; let seenFocusIds: Set<string> | null = null; let seenRequestIds: Set<number> | null = null; let seenPendingStudents: Set<number> | null = null;
    const switched = shownSession.current !== (session?.id ?? null);
    shownSession.current = session?.id ?? null;
    if (switched) { selection.current++; setAttendance([]); setPending([]); setAudit([]); setResources([]); setFocusEvents([]); setExitRequests([]); setFocusAlert(''); setChallenge(null); setQrImage(''); setConfirmation(null); }
    if (!session) { setLoadingAttendance(false); return; }
    if (switched) setLoadingAttendance(true);
    const refresh = async () => {
      try {
        const [rows, requests, events, files, focus, exits] = await Promise.all([
          api<Attendance[]>(`/sessions/${session.id}/attendance`, token),
          api<Pending[]>(`/sessions/${session.id}/pending`, token),
          api<Audit[]>(`/sessions/${session.id}/audit`, token),
          api<Resource[]>(`/sessions/${session.id}/resources`, token),
          api<FocusEvent[]>(`/sessions/${session.id}/focus-events`, token),
          api<ExitRequest[]>(`/sessions/${session.id}/focus-exit-requests`, token)
        ]);
        if (active) {
          const newlyBackgrounded = seenFocusIds === null ? [] : focus.filter(event => event.action === 'backgrounded' && !seenFocusIds!.has(event.id));
          seenFocusIds = new Set(focus.map(event => event.id));
          setAttendance(rows); setPending(requests); setAudit(events); setResources(files); setFocusEvents(focus); setExitRequests(exits);
          const newRequests = seenRequestIds === null ? [] : exits.filter(request => !seenRequestIds!.has(request.id));
          seenRequestIds = new Set(exits.map(request => request.id));
          const newPending = seenPendingStudents === null ? [] : requests.filter(request => !seenPendingStudents!.has(request.student_id));
          seenPendingStudents = new Set(requests.map(request => request.student_id));
          if (newPending.length) {
            setFocusAlert(`${newPending.length} student${newPending.length === 1 ? '' : 's'} asked you to review attendance.`);
            if (soundRef.current) alertSound();
          }
          if (newRequests.length) {
            setFocusAlert(`${newRequests.length} student${newRequests.length === 1 ? '' : 's'} need${newRequests.length === 1 ? 's' : ''} your approval: ${newRequests.map(request => request.kind === 'resume' ? 'left the app and wants to continue' : 'asks to end focus early').join(', ')}.`);
            if (soundRef.current) alertSound();
          }
          if (newlyBackgrounded.length) {
            setFocusAlert(`${newlyBackgrounded.length} new focus interruption${newlyBackgrounded.length === 1 ? '' : 's'} for review. The reason and destination app are unknown, this is not a misconduct finding, and attendance is unchanged.`);
            if (soundRef.current) alertSound();
            if (soundRef.current && 'Notification' in window && Notification.permission === 'granted') {
              try { new Notification('FocusLock · focus event for review', { body: `${newlyBackgrounded.length} new app background event${newlyBackgrounded.length === 1 ? '' : 's'}. Cause unknown; attendance unchanged.`, tag: `focuslock-${session.id}` }); } catch { /* In-page alert remains available. */ }
            }
          }
        }
        if (session.state === 'active' || session.state === 'checkin_pending') {
          const next = await api<Challenge>(`/sessions/${session.id}/qr`, token);
          if (active) setChallenge(next);
        }
      } catch (e) { if (active) { setChallenge(null); setError((e as Error).message); } }
      finally { if (active) setLoadingAttendance(false); }
    };
    void refresh();
    const poll = window.setInterval(refresh, 5000);
    return () => { active = false; window.clearInterval(poll); };
  }, [session?.id, session?.state, token]);
  async function toggleSound() {
    if (soundEnabled) { setSoundEnabled(false); return; }
    unlockAudio();
    try {
      await sharedAudio?.resume();
      alertSound();
      if ('Notification' in window && Notification.permission === 'default') {
        try { await Notification.requestPermission(); } catch { /* Sound and in-page alerts still work. */ }
      }
      setSoundEnabled(true);
      setNotice('Alert sounds are on.');
    } catch { setError('This browser could not enable sound. Check its audio settings.'); }
  }
  useEffect(() => {
    let active = true;
    if (!challenge) { setQrImage(''); return; }
    QRCode.toDataURL(challenge.qr, { width: 280, margin: 2, errorCorrectionLevel: 'M' })
      .then(image => { if (active) setQrImage(image); }).catch(e => { if (active) setError((e as Error).message); });
    return () => { active = false; };
  }, [challenge?.qr]);
  async function resolveExit(request: ExitRequest, decision: 'approve' | 'deny') {
    if (!session) return;
    setSaving(request.id);
    try {
      await api(`/sessions/${session.id}/focus-exit-requests/${request.id}`, token, { method: 'POST', body: JSON.stringify({ decision }) });
      setExitRequests(rows => rows.filter(row => row.id !== request.id));
      setNotice(decision === 'approve' ? (request.kind === 'resume' ? 'Approved. The student can continue.' : 'Approved. Focus will end on that phone shortly.') : 'Denied.');
    } catch (e) { setError((e as Error).message); }
    finally { setSaving(null); }
  }
  async function changeState(action: 'start' | 'activate' | 'pause' | 'resume' | 'end') {
    if (!session) return;
    setError(''); setNotice(''); setSaving(-1);
    try {
      await api(`/sessions/${session.id}/${action}`, token, { method: 'POST' });
      const updated = await api<Session>(`/sessions/${session.id}`, token);
      setSessions(rows => rows.map(row => row.id === updated.id ? updated : row));
      setSession(updated); setNotice(action === 'start' ? 'Check-in is open. The QR rotates every 20 seconds.' : `Session ${updated.state.replace('_', ' ')}.`);
    } catch (e) { setError((e as Error).message); } finally { setSaving(null); }
  }
  async function refreshSession() {
    if (!session) return;
    setError(''); setSaving(-1);
    try {
      const [updated, requests] = await Promise.all([
        api<Session>(`/sessions/${session.id}`, token),
        api<PrivacyRequest[]>('/teacher/privacy/requests', token)
      ]);
      setSessions(rows => rows.map(row => row.id === updated.id ? updated : row));
      setSession({ ...updated });
      setPrivacyRequests(requests);
      setNotice('Session and check-ins refreshed.');
    } catch (e) { setError((e as Error).message); } finally { setSaving(null); }
  }
  function askDelete(target: 'one' | 'all') {
    setDeleteTarget(target); setDeleteReason(''); setDeleteConfirm(''); setCreateOpen(false); setError(''); setNotice('');
  }
  async function deleteSessions(event: React.FormEvent) {
    event.preventDefault(); if (!course || !deleteTarget) return;
    const reason = deleteReason.trim();
    if (reason.length < 10) { setError('Give a reason of at least 10 characters.'); return; }
    if (deleteTarget === 'all' && deleteConfirm.trim() !== 'DELETE') { setError('Type DELETE to confirm deleting every session.'); return; }
    setSaving(-1); setError('');
    try {
      if (deleteTarget === 'one' && session) {
        await api(`/sessions/${session.id}`, token, { method: 'DELETE', body: JSON.stringify({ reason }) });
        const remaining = sessions.filter(row => row.id !== session.id);
        setSessions(remaining); setSession(remaining.at(-1) ?? null); setNotice('Session deleted.');
      } else {
        await api(`/courses/${course.id}/sessions`, token, { method: 'DELETE', body: JSON.stringify({ reason }) });
        setSessions([]); setSession(null); setNotice('All sessions in this course were deleted.');
      }
      setDayFilter(''); setDeleteTarget(null); setDeleteReason(''); setDeleteConfirm('');
    } catch (e) { setError((e as Error).message); } finally { setSaving(null); }
  }
  async function addResource(event: React.FormEvent) {
    event.preventDefault(); if (!session) return;
    setError(''); setSaving(-1);
    try {
      const row = await api<Resource>(`/sessions/${session.id}/resources`, token, { method: 'POST', body: JSON.stringify({ title: resourceTitle.trim(), url: resourceUrl.trim(), kind: resourceKind, downloadable }) });
      setResources(rows => [...rows, row]); setResourceTitle(''); setResourceUrl(''); setDownloadable(false);
      setNotice('Course resource attached. Students can open it in the native viewer.');
    } catch (e) { setError((e as Error).message); } finally { setSaving(null); }
  }
  async function removeResource(id: number) {
    if (!session) return; setSaving(-1); setError('');
    try { await api(`/sessions/${session.id}/resources/${id}`, token, { method: 'DELETE' }); setResources(rows => rows.filter(r => r.id !== id)); setNotice('Resource link removed from this session.'); }
    catch (e) { setError((e as Error).message); } finally { setSaving(null); }
  }
  function confirm(studentId: number, status: string) {
    setConfirmation({ studentId, status }); setConfirmationReason(''); setError('');
  }
  async function saveConfirmation(event: React.FormEvent) {
    event.preventDefault(); if (!session || !confirmation) return;
    const { studentId, status } = confirmation;
    const previous = attendance.find(row => row.student_id === studentId);
    const review = pending.some(row => row.student_id === studentId);
    const reason = confirmationReason.trim();
    if ((previous || review) && reason.length < 10) { setError('A correction or pending-review reason of at least 10 characters is required.'); return; }
    const version = selection.current;
    setSaving(studentId); setError(''); setNotice('');
    try {
      const row = await api<Attendance>(`/sessions/${session.id}/attendance/${studentId}`, token, { method: 'PUT', body: JSON.stringify({ status, reason: reason || null }) });
      if (version === selection.current) {
        setAttendance(rows => [...rows.filter(r => r.student_id !== studentId), row]);
        setPending(rows => rows.filter(r => r.student_id !== studentId));
        setAudit(await api<Audit[]>(`/sessions/${session.id}/audit`, token));
        setNotice(previous ? 'Correction and reason saved to the audit trail.' : 'Teacher confirmation saved.');
        setConfirmation(null); setConfirmationReason('');
      }
    } catch (e) { if (version === selection.current) setError((e as Error).message); } finally { setSaving(null); }
  }
  async function reviewPrivacyRequest(event: React.FormEvent) {
    event.preventDefault(); if (!privacyReview) return;
    if (privacyResolution.trim().length < 10) { setError('Give a review reason of at least 10 characters.'); return; }
    setSaving(-1); setError('');
    try {
      await api(`/teacher/privacy/requests/${privacyReview.id}`, token, { method: 'PUT', body: JSON.stringify({ status: privacyReview.status, resolution: privacyResolution.trim() }) });
      setPrivacyRequests(rows => rows.filter(row => row.id !== privacyReview.id));
      setPrivacyReview(null); setPrivacyResolution('');
      setNotice('Request review and reason saved to the audit trail. Apply attendance corrections through the roster.');
    } catch (e) { setError((e as Error).message); } finally { setSaving(null); }
  }
  const presentCount = attendance.filter(row => row.status === 'present').length;
  const unrecordedCount = Math.max(0, roster.length - attendance.length);
  const reviewedCount = pending.length;
  const filteredRoster = roster.filter(person => {
    const row = attendance.find(record => record.student_id === person.id);
    const matchesText = `${person.name} ${person.email}`.toLowerCase().includes(rosterQuery.toLowerCase().trim());
    const matchesFilter = rosterFilter === 'all' ||
      (rosterFilter === 'present' && row?.status === 'present') ||
      (rosterFilter === 'absent' && (row?.status === 'absent' || !row)) ||
      (rosterFilter === 'unrecorded' && !row) ||
      (rosterFilter === 'review' && pending.some(request => request.student_id === person.id));
    return matchesText && matchesFilter;
  });
  const canOpen = session?.state === 'ready' && new Date(session.starts_at) <= new Date() && new Date(session.ends_at) > new Date();
  const sessionExpired = session && new Date(session.ends_at) <= new Date();
  const recentFocus = focusEvents.filter(event => event.action === 'backgrounded').slice(-5).reverse();
  const selectedPrivacyRequest = privacyRequests.find(request => request.id === privacyReview?.id);
  const visibleSessions = dayFilter ? sessions.filter(s => sessionDay(s.starts_at) === dayFilter) : sessions;
  const today = localInput(new Date()).slice(0, 10);
  function changeDay(day: string) {
    setDayFilter(day);
    const list = day ? sessions.filter(s => sessionDay(s.starts_at) === day) : sessions;
    if (list.length && !(session && list.some(s => s.id === session.id))) { setSession(list.at(-1) ?? null); setNotice(''); }
  }
  const alertCount = exitRequests.length + pending.length + privacyRequests.length + recentFocus.length;

  return <main className="dashboard-shell">
    <div className="page-heading">
      <div><p className="eyebrow">Teacher workspace</p><h1>Class management</h1></div>
      <span className="pill">{courses.length} assigned course{courses.length === 1 ? '' : 's'}</span>
    </div>
    {error && <p className="error" role="alert">{error}</p>}
    {notice && <p className="success" role="status">{notice}</p>}
      {(exitRequests.length > 0 || pending.length > 0 || privacyRequests.length > 0 || recentFocus.length > 0) && <section className="notification-card alerts-section" aria-label="Alerts"><div className="section-heading"><div><p className="eyebrow">Alerts</p><h3><span className="alert-badge">{alertCount}</span>{alertCount === 1 ? 'needs' : 'need'} your attention</h3></div></div>
        {exitRequests.map(request => <div className="request-row" key={`request-${request.id}`}><div><strong>{roster.find(person => person.id === request.student_id)?.name ?? `Student ${request.student_id}`}</strong> {request.kind === 'resume' ? 'left the app and needs approval to continue' : 'asks to end focus early'}<small>Asked {date(request.created_at)} · attendance is unchanged</small></div><div className="actions"><button disabled={saving !== null} onClick={() => resolveExit(request, 'approve')}>Approve</button><button className="secondary" disabled={saving !== null} onClick={() => resolveExit(request, 'deny')}>Deny</button></div></div>)}
        {pending.length > 0 && <div className="request-row"><div><strong>{pending.length} attendance review request{pending.length === 1 ? '' : 's'}</strong><small>Students asked you to confirm attendance</small></div><div className="actions"><button className="secondary" onClick={() => setSection('roster')}>Open roster</button></div></div>}
        {privacyRequests.length > 0 && <div className="request-row"><div><strong>{privacyRequests.length} student privacy request{privacyRequests.length === 1 ? '' : 's'}</strong><small>Corrections or deletions waiting for your review</small></div><div className="actions"><button className="secondary" onClick={() => setSection('activity')}>Review</button></div></div>}
        {recentFocus.map(event => <div className="request-row" key={`event-${event.id}`}><div><strong>{roster.find(person => person.id === event.student_id)?.name ?? `Student ${event.student_id}`}</strong> left the app during focus<small>{date(event.recorded_at)} · reason unknown · review only</small></div></div>)}
      </section>}
    <nav className="view-tabs" aria-label="Teacher workspace">
      <button type="button" className={view === 'classes' ? 'active' : ''} aria-current={view === 'classes' ? 'page' : undefined} onClick={() => setView('classes')}>Classes <small>{courses.length}</small></button>
      <button type="button" className={view === 'sessions' ? 'active' : ''} aria-current={view === 'sessions' ? 'page' : undefined} onClick={() => setView('sessions')}>Sessions <small>{sessions.length}</small></button>
      <button type="button" className={view === 'reports' ? 'active' : ''} aria-current={view === 'reports' ? 'page' : undefined} onClick={() => setView('reports')}>Reports</button>
    </nav>
    {view === 'reports' ? <TeacherReports request={<T,>(path: string, options?: RequestInit) => api<T>(path, token, options)}/> : view === 'classes' ? <ClassesView courses={courses} selectedId={course?.id ?? null} loading={loading} onSelect={setCourse} onOpenSessions={() => setView('sessions')} request={<T,>(path: string, options?: RequestInit) => api<T>(path, token, options)}/> : <div className="workspace single">
      <section className="panel workspace-panel">
        <div className="course-header"><div>{courses.length > 1 && <label className="course-picker">Class<select value={course?.id ?? ''} disabled={saving !== null} onChange={e => setCourse(courses.find(row => row.id === Number(e.target.value)) ?? null)}>{courses.map(row => <option key={row.id} value={row.id}>{row.code} · {row.title}</option>)}</select></label>}<p className="eyebrow">{course?.code ?? 'Course'}</p><h2>{course?.title ?? 'Your course workspace'}</h2><p>{course?.term ?? 'Choose a course to begin'}</p>{course && <div className="course-for">{course.groups && course.groups.length > 0 ? <>{course.groups.map(group => <span key={group.id} className="chip">{group.name}</span>)}<span className="muted-note">{course.students ?? 0} student{course.students === 1 ? '' : 's'}</span></> : <span className="muted-note">No student group linked yet. Ask an administrator to link one.</span>}</div>}</div>{course && <div className="header-actions"><button className="secondary" onClick={() => { setCreateOpen(open => !open); setDeleteTarget(null); setError(''); }} disabled={saving !== null}>{createOpen ? 'Close form' : '+ Schedule sessions'}</button>{sessions.length > 0 && <button className="secondary danger" onClick={() => askDelete('all')} disabled={saving !== null}>Delete all sessions</button>}</div>}</div>
        {loading ? <p role="status">Loading course…</p> : <>
          {deleteTarget && course && <form className="create-session danger-zone" onSubmit={deleteSessions}>
            <div><h3>{deleteTarget === 'all' ? `Delete all ${sessions.length} session${sessions.length === 1 ? '' : 's'} in ${course.code}?` : `Delete session "${session?.title ?? ''}"?`}</h3><p>This permanently removes attendance records, resources and focus events for {deleteTarget === 'all' ? 'these sessions' : 'this session'}. It cannot be undone.</p></div>
            <label>Reason (at least 10 characters)<input required minLength={10} maxLength={400} autoFocus value={deleteReason} onChange={e => setDeleteReason(e.target.value)} placeholder="Why are you deleting this?"/></label>
            {deleteTarget === 'all' && <label>Type DELETE to confirm<input required autoComplete="off" value={deleteConfirm} onChange={e => setDeleteConfirm(e.target.value)}/></label>}
            <div className="actions"><button className="danger-solid" disabled={saving !== null}>Delete permanently</button><button type="button" className="secondary" onClick={() => setDeleteTarget(null)}>Cancel</button></div>
          </form>}
          {createOpen && course && <ScheduleSessions course={course} request={<T,>(path: string, options?: RequestInit) => api<T>(path, token, options)} onClose={() => setCreateOpen(false)} onCreated={added => {
            const ordered = [...added].sort((x, y) => new Date(x.starts_at).getTime() - new Date(y.starts_at).getTime());
            setSessions(rows => [...rows, ...ordered].sort((x, y) => new Date(x.starts_at).getTime() - new Date(y.starts_at).getTime()));
            setSession(ordered[0] ?? null); setDayFilter(''); setSection('overview'); setError('');
            setNotice(`${ordered.length} session${ordered.length === 1 ? '' : 's'} scheduled. Open check-in when each start time arrives.`);
          }}/>}
          <div className="date-bar" role="group" aria-label="Find sessions by date">
            <button type="button" className="secondary" aria-label="Previous day" disabled={saving !== null} onClick={() => changeDay(shiftDay(dayFilter || today, -1))}>‹</button>
            <input type="date" aria-label="Session date" value={dayFilter} onChange={e => changeDay(e.target.value)}/>
            <button type="button" className="secondary" aria-label="Next day" disabled={saving !== null} onClick={() => changeDay(shiftDay(dayFilter || today, 1))}>›</button>
            <button type="button" className={`secondary ${dayFilter === today ? 'selected-filter' : ''}`} onClick={() => changeDay(today)}>Today</button>
            <button type="button" className={`secondary ${dayFilter ? '' : 'selected-filter'}`} aria-pressed={!dayFilter} onClick={() => changeDay('')}>All dates</button>
            <span className="date-count">{dayFilter ? `${visibleSessions.length} session${visibleSessions.length === 1 ? '' : 's'} on this date` : `${sessions.length} session${sessions.length === 1 ? '' : 's'}`}</span>
          </div>
          <div className="session-select"><label>Selected session<select value={session && visibleSessions.some(s => s.id === session.id) ? session.id : ''} disabled={!visibleSessions.length || saving !== null} onChange={e => { setSession(sessions.find(s => s.id === Number(e.target.value)) ?? null); setNotice(''); }}>{!visibleSessions.length && <option value="">{dayFilter ? 'No sessions on this date' : 'No sessions yet'}</option>}{visibleSessions.length > 0 && !(session && visibleSessions.some(s => s.id === session.id)) && <option value="">Choose a session</option>}{visibleSessions.map(s => <option key={s.id} value={s.id}>{s.title} · {date(s.starts_at)} · {stateLabel(s.state)}</option>)}</select></label>{session && <button className="secondary" onClick={refreshSession} disabled={saving !== null}>Refresh data</button>}</div>
          {!session ? <div className="empty-state"><h3>No session selected</h3><p>Create a session for this course to show a live QR and begin taking attendance.</p><button onClick={() => setCreateOpen(true)} disabled={!course}>Create session</button></div> : <>
            <div className="session-banner"><div><span className={`state-badge state-${session.state}`}>{stateLabel(session.state)}</span><h3>{session.title}</h3><p>{date(session.starts_at)} — {date(session.ends_at)} · Room {session.room_code ?? 'UNASSIGNED'}</p></div><div className="session-actions">
              {canOpen && <button onClick={() => changeState('start')} disabled={saving !== null}>Open check-in</button>}
              {session.state === 'checkin_pending' && !sessionExpired && <button onClick={() => changeState('activate')} disabled={saving !== null}>Begin active session</button>}
              {session.state === 'active' && !sessionExpired && <button onClick={() => changeState('pause')} disabled={saving !== null}>Pause session</button>}
              {session.state === 'paused' && !sessionExpired && <button onClick={() => changeState('resume')} disabled={saving !== null}>Resume session</button>}
              {['checkin_pending', 'active', 'paused'].includes(session.state) && <button className="secondary" onClick={() => changeState('end')} disabled={saving !== null}>End session</button>}
              {['ready', 'ended'].includes(session.state) && <button className="secondary danger" onClick={() => askDelete('one')} disabled={saving !== null}>Delete session</button>}
            </div></div>
            <div className="focus-alert-controls">
              <button className="secondary" type="button" aria-pressed={soundEnabled} onClick={() => void toggleSound()}>{soundEnabled ? 'Mute alert sounds' : 'Turn on alert sounds'}</button>
              <PopOutAlerts items={alertLog} onDismissAll={() => { setAlertLog([]); setFocusAlertState(''); }}/>
            </div>
            {focusAlert && <AlertBanner text={focusAlert} earlier={Math.max(0, alertLog.length - 1)} onView={() => { setSection('activity'); setFocusAlertState(''); }} onDismiss={() => setFocusAlertState('')}/>}
            {session.state === 'ready' && !canOpen && <p className="inline-hint">{sessionExpired ? 'This session window has passed. Create a new session to open check-in.' : 'Check-in can open at the scheduled start time.'}</p>}
            <div className="stat-grid" aria-label="Session attendance summary"><div className="stat"><strong>{presentCount}</strong><span>Present</span></div><div className="stat"><strong>{unrecordedCount}</strong><span>Not recorded</span></div><div className="stat"><strong>{reviewedCount}</strong><span>Pending review</span></div><div className="stat"><strong>{resources.length}</strong><span>Resources</span></div></div>
            <nav className="section-tabs" aria-label="Session sections">{(['overview', 'roster', 'resources', 'activity'] as const).map(tab => <button key={tab} type="button" className={section === tab ? 'active' : ''} aria-current={section === tab ? 'page' : undefined} onClick={() => setSection(tab)}>{tab === 'overview' ? 'Overview' : tab === 'roster' ? 'Roster' : tab === 'resources' ? 'Resources' : tab === 'activity' ? 'Activity' : 'Analytics'}</button>)}</nav>
            {section === 'overview' && <div className="overview-grid"><div className="overview-main">
              {(session.state === 'active' || session.state === 'checkin_pending') && !sessionExpired ? <div className="qr-panel"><div className="section-heading"><div><p className="eyebrow">Live check-in</p><h3>Scan this QR in class</h3></div><span className="live-dot">Rotates every 20 sec</span></div>{qrImage ? <img src={qrImage} alt="Rotating attendance QR challenge" width="280" height="280"/> : <p role="status">Loading QR…</p>}<p className="hint">Expires {challenge ? date(challenge.expires_at) : 'soon'}</p></div> : <div className="empty-state"><h3>No live QR right now</h3><p>{session.state === 'paused' ? 'The session is paused. Resume it to continue check-in.' : session.state === 'ended' ? 'This session has ended. Attendance records remain available.' : 'Open check-in when this session begins.'}</p></div>}
            </div><div className="overview-side">{session.state !== 'ended' && <BackupCodes sessionId={session.id} ended={false} request={<T,>(path: string, options?: RequestInit) => api<T>(path, token, options)}/>}<div className="mini-card"><h3>Roster</h3><p>Confirm attendance for students who can't scan.</p><button className="secondary" onClick={() => setSection('roster')}>Open roster →</button></div><div className="mini-card"><h3>Resources</h3><p>Attach documents for students.</p><button className="secondary" onClick={() => setSection('resources')}>Manage resources →</button></div></div></div>}
            {section === 'roster' && <section className="content-section"><div className="section-heading"><div><p className="eyebrow">Attendance</p><h3>Class roster</h3></div><span>{roster.length} students</span></div><div className="roster-toolbar"><label>Find student<input type="search" placeholder="Search name or email" value={rosterQuery} onChange={e => setRosterQuery(e.target.value)}/></label><div className="filter-buttons" aria-label="Filter roster">{(['all', 'present', 'absent', 'unrecorded', 'review'] as const).map(filter => <button type="button" key={filter} className={rosterFilter === filter ? 'selected-filter' : ''} aria-pressed={rosterFilter === filter} onClick={() => setRosterFilter(filter)}>{filter === 'all' ? 'Attended or not' : filter === 'present' ? 'Attended' : filter === 'absent' ? 'Not attended' : filter === 'unrecorded' ? 'Not recorded' : 'Needs review'}</button>)}</div></div>
              {loadingAttendance && <p role="status">Updating attendance…</p>}
              <div className="table-wrap"><table><thead><tr><th>Student</th><th>Attendance</th><th>Teacher action</th></tr></thead><tbody>{filteredRoster.map(person => { const row = attendance.find(a => a.student_id === person.id); const request = pending.find(p => p.student_id === person.id); return <tr key={person.id}><td><strong>{person.name}</strong>{person.kind && person.kind !== 'regular' && <span className="chip kind-chip">{person.kind}</span>}<small>{person.email}</small>{request && <small className="pending-note">Pending review: {request.reason}</small>}</td><td><span className={`status ${row?.status ?? ''}`}>{loadingAttendance ? 'Updating…' : row?.status ?? 'Not recorded'}</span>{row && <small>{row.method === 'qr' ? 'QR check-in' : row.method === 'code' ? 'Class code' : 'Teacher-confirmed'}</small>}</td><td><div className="actions">{(['present', 'absent', 'excused'] as const).map(status => <button className="secondary" key={status} disabled={loadingAttendance || saving !== null || row?.status === status || new Date(session.starts_at) > new Date()} aria-label={`Mark ${person.name} ${status}`} onClick={() => confirm(person.id, status)}>{status}</button>)}</div></td></tr>; })}</tbody></table></div>{!filteredRoster.length && <p className="empty-inline">No students match this filter.</p>}
              {confirmation && <form className="confirmation" onSubmit={saveConfirmation}><h4>Confirm {roster.find(p => p.id === confirmation.studentId)?.name} as {confirmation.status}</h4><label>{attendance.some(row => row.student_id === confirmation.studentId) || pending.some(row => row.student_id === confirmation.studentId) ? 'Reason (at least 10 characters)' : 'Note (optional)'}<input autoFocus maxLength={500} minLength={attendance.some(row => row.student_id === confirmation.studentId) || pending.some(row => row.student_id === confirmation.studentId) ? 10 : undefined} required={attendance.some(row => row.student_id === confirmation.studentId) || pending.some(row => row.student_id === confirmation.studentId)} value={confirmationReason} onChange={e => setConfirmationReason(e.target.value)} placeholder="Camera failure, unsupported device, or correction reason"/></label><div className="actions"><button type="submit" disabled={saving !== null}>Save attendance</button><button type="button" className="secondary" onClick={() => setConfirmation(null)}>Cancel</button></div></form>}
            </section>}
            {section === 'resources' && <section className="content-section resources"><div className="section-heading"><div><p className="eyebrow">Learning materials</p><h3>Course resources</h3></div><span>{resources.length} attached</span></div><p className="note">College HTTPS hosts only. PDF or plain text.</p><form className="resource-form" onSubmit={addResource}><label>Title<input required maxLength={150} value={resourceTitle} onChange={e => setResourceTitle(e.target.value)} placeholder="Lecture notes"/></label><label>College document URL<input required type="url" value={resourceUrl} onChange={e => setResourceUrl(e.target.value)} placeholder="https://docs.college.example.edu/course/file.pdf"/></label><label>Format<select value={resourceKind} onChange={e => setResourceKind(e.target.value as 'pdf' | 'text')}><option value="pdf">PDF</option><option value="text">Plain text</option></select></label><label className="check-label"><input type="checkbox" checked={downloadable} onChange={e => setDownloadable(e.target.checked)}/> Allow private offline copy</label><button disabled={saving !== null || session.state === 'ended'}>Attach resource</button></form>{session.state === 'ended' && <p className="inline-hint">Ended sessions cannot accept new resources.</p>}{resources.length === 0 && <p className="empty-inline">No resources attached to this session yet.</p>}{resources.map(file => <div className="resource-card" key={file.id}><div><strong>{file.title}</strong><small>{file.kind.toUpperCase()} · {file.downloadable ? 'Private offline copy allowed' : 'View only'}</small><small className="resource-url">{file.url}</small></div><button className="secondary" disabled={saving !== null} onClick={() => removeResource(file.id)}>Remove</button></div>)}</section>}
            {section === 'activity' && <section className="content-section"><div className="section-heading"><div><p className="eyebrow">Records</p><h3>Activity & review</h3></div></div><div className="activity-grid"><div className="mini-card"><h4>Attendance audit trail · {audit.length}</h4>{audit.length === 0 && <p>No attendance changes recorded.</p>}{audit.map(event => <p className="event-row" key={event.id}><strong>{roster.find(p => p.id === event.student_id)?.name ?? event.student_id}</strong> · {event.previous_status ?? 'not recorded'} → {event.new_status}<small>{date(event.recorded_at)} · {event.action}{event.reason ? ` · ${event.reason}` : ''}</small></p>)}</div><div className="mini-card"><h4>Voluntary focus events · {focusEvents.length}</h4><p>Review only. The cause of a background event is unknown, and attendance is unchanged.</p>{focusEvents.map(event => <p className="event-row" key={event.id}>{roster.find(p => p.id === event.student_id)?.name ?? event.student_id} · {event.action === 'backgrounded' ? 'App left foreground · review only' : event.action}<small>{date(event.recorded_at)}</small></p>)}</div></div><div className="mini-card privacy-card"><h4>Student privacy requests · {privacyRequests.length}</h4><p>Accepting a correction doesn't change attendance. Use the roster for that.</p>{privacyRequests.length === 0 && <p>No pending requests for your assigned courses.</p>}{privacyRequests.map(request => <div className="request-row" key={request.id}><div><strong>{roster.find(person => person.id === request.student_id)?.name ?? `Student ${request.student_id}`}</strong> · {request.kind}<small>Session #{request.session_id} · {request.reason}</small></div><div className="actions"><button className="secondary" onClick={() => { setPrivacyReview({ id: request.id, status: 'accepted' }); setPrivacyResolution(''); }}>Accept</button><button className="secondary" onClick={() => { setPrivacyReview({ id: request.id, status: 'declined' }); setPrivacyResolution(''); }}>Decline</button></div></div>)}{privacyReview && <form className="confirmation" onSubmit={reviewPrivacyRequest}><h4>{stateLabel(privacyReview.status)} {selectedPrivacyRequest?.kind} request #{privacyReview.id}</h4><label>Review reason (at least 10 characters)<input autoFocus required minLength={10} maxLength={500} value={privacyResolution} onChange={e => setPrivacyResolution(e.target.value)} placeholder="Explain the review decision"/></label><div className="actions"><button disabled={saving !== null}>Save review</button><button type="button" className="secondary" onClick={() => setPrivacyReview(null)}>Cancel</button></div></form>}</div></section>}
          </>}
        </>}
      </section>
    </div>}
  </main>;
}

createRoot(document.getElementById('root')!).render(<React.StrictMode><App/></React.StrictMode>);
