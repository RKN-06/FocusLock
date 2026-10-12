import React, { useEffect, useState } from 'react';

export type Request = <T>(path: string, options?: RequestInit) => Promise<T>;
export type Slot = { id: number; label: string; starts: string; ends: string; active: boolean };
type Slots = { timezone: string; slots: Slot[] };
export type CreatedSession = { id: number; course_id: number; title: string; room_code: string; starts_at: string; ends_at: string; state: 'ready' | 'checkin_pending' | 'active' | 'paused' | 'ended' };
type Skipped = { date: string; slot: string; reason: string };

const MAX_SESSIONS = 100;
const MAX_DATES = 62;
const WEEKDAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
const pad = (value: number) => String(value).padStart(2, '0');
const ymd = (value: Date) => `${value.getFullYear()}-${pad(value.getMonth() + 1)}-${pad(value.getDate())}`;
const parseDay = (day: string) => new Date(`${day}T12:00:00`);
const dayLabel = (day: string) => parseDay(day).toLocaleDateString([], { weekday: 'short', day: 'numeric', month: 'short' });

/** Month grid where any number of future dates can be switched on and off. */
function MonthPicker({ view, onView, selected, onToggle, minDay }: {
  view: { y: number; m: number }; onView: (view: { y: number; m: number }) => void;
  selected: Set<string>; onToggle: (day: string) => void; minDay: string;
}) {
  const first = new Date(view.y, view.m, 1);
  const lead = (first.getDay() + 6) % 7;
  const length = new Date(view.y, view.m + 1, 0).getDate();
  const move = (delta: number) => { const next = new Date(view.y, view.m + delta, 1); onView({ y: next.getFullYear(), m: next.getMonth() }); };
  const cells: (string | null)[] = [...Array<null>(lead).fill(null), ...Array.from({ length }, (_, index) => `${view.y}-${pad(view.m + 1)}-${pad(index + 1)}`)];
  return <div className="month-picker">
    <div className="month-head">
      <button type="button" className="secondary" aria-label="Previous month" onClick={() => move(-1)}>‹</button>
      <strong aria-live="polite">{first.toLocaleDateString([], { month: 'long', year: 'numeric' })}</strong>
      <button type="button" className="secondary" aria-label="Next month" onClick={() => move(1)}>›</button>
    </div>
    <div className="month-grid" role="grid">
      {WEEKDAYS.map(name => <span key={name} className="month-dow" role="columnheader">{name}</span>)}
      {cells.map((day, index) => day === null ? <span key={`blank-${index}`} /> :
        <button type="button" key={day} className={`month-day ${selected.has(day) ? 'on' : ''} ${day === minDay ? 'today' : ''}`} aria-pressed={selected.has(day)}
          disabled={day < minDay} aria-label={dayLabel(day)} onClick={() => onToggle(day)}>{Number(day.slice(8))}</button>)}
    </div>
  </div>;
}

/** Teachers pick many dates and any of the administrator's time slots, then create every combination at once. */
export function ScheduleSessions({ course, request, onCreated, onClose }: {
  course: { id: number; code: string }; request: Request; onCreated: (rows: CreatedSession[]) => void; onClose: () => void;
}) {
  const today = ymd(new Date());
  const [data, setData] = useState<Slots | null>(null);
  const [loadError, setLoadError] = useState('');
  const [title, setTitle] = useState('');
  const [room, setRoom] = useState('UNASSIGNED');
  const [dates, setDates] = useState<Set<string>>(new Set());
  const [slotIds, setSlotIds] = useState<Set<number>>(new Set());
  const [view, setView] = useState(() => { const now = new Date(); return { y: now.getFullYear(), m: now.getMonth() }; });
  const [weekdays, setWeekdays] = useState<Set<number>>(new Set());
  const [weeks, setWeeks] = useState(4);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [skipped, setSkipped] = useState<Skipped[]>([]);
  const [made, setMade] = useState<number | null>(null);
  useEffect(() => {
    let active = true;
    request<Slots>('/slots').then(result => { if (active) setData(result); }).catch(e => { if (active) setLoadError((e as Error).message); });
    return () => { active = false; };
  }, []);
  const slots = data?.slots ?? [];
  const chosen = slots.filter(slot => slotIds.has(slot.id));
  const total = dates.size * chosen.length;
  const toggleDate = (day: string) => setDates(current => { const next = new Set(current); if (next.has(day)) next.delete(day); else if (next.size < MAX_DATES) next.add(day); return next; });
  const toggleSlot = (id: number) => setSlotIds(current => { const next = new Set(current); if (next.has(id)) next.delete(id); else next.add(id); return next; });
  const toggleWeekday = (index: number) => setWeekdays(current => { const next = new Set(current); if (next.has(index)) next.delete(index); else next.add(index); return next; });
  function addRepeats() {
    const next = new Set(dates);
    const start = new Date(); start.setHours(12, 0, 0, 0);
    for (let offset = 0; offset < weeks * 7 && next.size < MAX_DATES; offset++) {
      const day = new Date(start); day.setDate(start.getDate() + offset);
      if (weekdays.has((day.getDay() + 6) % 7)) next.add(ymd(day));
    }
    setDates(next);
  }
  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (!dates.size || !chosen.length) { setError('Pick at least one date and one time slot.'); return; }
    if (total > MAX_SESSIONS) { setError(`That is ${total} sessions. Schedule at most ${MAX_SESSIONS} at a time.`); return; }
    setBusy(true); setError(''); setSkipped([]); setMade(null);
    try {
      const result = await request<{ created: CreatedSession[]; skipped: Skipped[] }>('/sessions/bulk', { method: 'POST', body: JSON.stringify({
        course_id: course.id, title: title.trim(), room_code: room.trim() || 'UNASSIGNED', dates: [...dates].sort(), slot_ids: [...slotIds] }) });
      setMade(result.created.length); setSkipped(result.skipped);
      if (result.created.length) onCreated(result.created);
      if (!result.skipped.length) onClose();
    } catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  }
  const preview = [...dates].sort().flatMap(day => chosen.map(slot => `${dayLabel(day)} · ${slot.label}`));
  return <form className="create-session schedule" onSubmit={submit}>
    <div><h3>Schedule sessions for {course.code}</h3><p>Pick the dates, pick the slots, and every combination is created at once.{data && <> Times are in {data.timezone}.</>}</p></div>
    {error && <p className="error" role="alert">{error}</p>}
    {loadError && <p className="error" role="alert">{loadError}</p>}
    <div className="form-grid">
      <label>Session title<input required minLength={3} maxLength={150} value={title} onChange={e => setTitle(e.target.value)} placeholder="Week 4 · Workshop"/></label>
      <label>Room code<input required maxLength={40} pattern="[A-Za-z0-9._-]+" value={room} onChange={e => setRoom(e.target.value)} placeholder="CS-101"/></label>
    </div>
    <fieldset className="schedule-step"><legend><span className="step-no">1</span> Dates <small>{dates.size} selected</small></legend>
      <div className="date-step">
        <MonthPicker view={view} onView={setView} selected={dates} onToggle={toggleDate} minDay={today}/>
        <div className="repeat-box">
          <strong>Repeat weekly</strong>
          <p>Choose weekdays and how many weeks, then add them all.</p>
          <div className="weekday-row" role="group" aria-label="Weekdays">{WEEKDAYS.map((name, index) => <button type="button" key={name} className={`chip ${weekdays.has(index) ? 'on' : ''}`} aria-pressed={weekdays.has(index)} onClick={() => toggleWeekday(index)}>{name}</button>)}</div>
          <label className="weeks">For <input type="number" min={1} max={16} value={weeks} onChange={e => setWeeks(Math.max(1, Math.min(16, Number(e.target.value) || 1)))}/> weeks</label>
          <div className="actions"><button type="button" className="secondary" disabled={!weekdays.size} onClick={addRepeats}>Add these dates</button><button type="button" className="secondary" disabled={!dates.size} onClick={() => setDates(new Set())}>Clear dates</button></div>
        </div>
      </div>
    </fieldset>
    <fieldset className="schedule-step"><legend><span className="step-no">2</span> Time slots <small>{chosen.length} selected</small></legend>
      {!data && !loadError && <p role="status">Loading slots…</p>}
      {data && !slots.length && <p className="empty-inline">No time slots are available yet. Ask an administrator to add some.</p>}
      <div className="slot-grid">{slots.map(slot => <button type="button" key={slot.id} className={`slot-tile ${slotIds.has(slot.id) ? 'on' : ''}`} aria-pressed={slotIds.has(slot.id)} onClick={() => toggleSlot(slot.id)}>
        <span className="slot-name">{slot.label}</span><span className="slot-time">{slot.starts} – {slot.ends}</span><span className="slot-tick" aria-hidden="true">✓</span></button>)}</div>
      {slots.length > 1 && <div className="actions"><button type="button" className="secondary" onClick={() => setSlotIds(new Set(slots.map(slot => slot.id)))}>Select all</button><button type="button" className="secondary" disabled={!slotIds.size} onClick={() => setSlotIds(new Set())}>Clear</button></div>}
    </fieldset>
    <div className={`schedule-summary ${total > MAX_SESSIONS ? 'over' : ''}`} role="status">
      <strong>{total}</strong> session{total === 1 ? '' : 's'} <span>({dates.size} date{dates.size === 1 ? '' : 's'} × {chosen.length} slot{chosen.length === 1 ? '' : 's'})</span>
      {preview.length > 0 && <div className="preview-list">{preview.slice(0, 8).map(text => <span key={text} className="chip">{text}</span>)}{preview.length > 8 && <span className="chip">+{preview.length - 8} more</span>}</div>}
    </div>
    {made !== null && skipped.length > 0 && <div className="inline-hint" role="status"><strong>{made} created, {skipped.length} skipped.</strong>
      <ul>{skipped.map(item => <li key={`${item.date}-${item.slot}`}>{dayLabel(item.date)} · {item.slot}: {item.reason}</li>)}</ul></div>}
    <div className="actions">
      {made !== null && skipped.length > 0 ? <button type="button" onClick={onClose}>Done</button> : <button disabled={busy || !total || total > MAX_SESSIONS}>{busy ? 'Creating…' : total ? `Create ${total} session${total === 1 ? '' : 's'}` : 'Create sessions'}</button>}
      <button type="button" className="secondary" onClick={onClose}>{made !== null && skipped.length > 0 ? 'Close' : 'Cancel'}</button>
    </div>
  </form>;
}

type SlotForm = { id: number | null; label: string; starts: string; ends: string; active: boolean };

/** Administrators define the time slots teachers pick from. */
export function AdminSlots({ request }: { request: Request }) {
  const [data, setData] = useState<Slots | null>(null);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [form, setForm] = useState<SlotForm | null>(null);
  const [confirmDelete, setConfirmDelete] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  async function load() { try { setData(await request<Slots>('/admin/slots')); } catch (e) { setError((e as Error).message); } }
  useEffect(() => { void load(); }, []);
  async function save(event: React.FormEvent) {
    event.preventDefault(); if (!form) return;
    setBusy(true); setError(''); setNotice('');
    try {
      const body = JSON.stringify({ label: form.label.trim(), starts: form.starts, ends: form.ends, active: form.active });
      if (form.id === null) await request('/admin/slots', { method: 'POST', body }); else await request(`/admin/slots/${form.id}`, { method: 'PUT', body });
      setNotice(form.id === null ? 'Slot added.' : 'Slot updated.'); setForm(null); await load();
    } catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  }
  async function setActive(slot: Slot, active: boolean) {
    setBusy(true); setError(''); setNotice('');
    try { await request(`/admin/slots/${slot.id}`, { method: 'PUT', body: JSON.stringify({ label: slot.label, starts: slot.starts, ends: slot.ends, active }) }); setNotice(active ? 'Slot is available to teachers again.' : 'Slot hidden from teachers.'); await load(); }
    catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  }
  async function remove(slot: Slot) {
    setBusy(true); setError(''); setNotice('');
    try { await request(`/admin/slots/${slot.id}`, { method: 'DELETE' }); setNotice('Slot deleted. Sessions already scheduled keep their times.'); setConfirmDelete(null); await load(); }
    catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  }
  return <section className="admin-panel">
    <div className="admin-head"><div><h2>Class time slots</h2><p>Only administrators can change these. Teachers choose from the visible slots when they schedule sessions. {data && <>Times are in {data.timezone}.</>}</p></div><button onClick={() => { setForm({ id: null, label: '', starts: '09:00', ends: '09:50', active: true }); setError(''); setNotice(''); }} disabled={busy}>+ Add a slot</button></div>
    {error && <p className="error" role="alert">{error}</p>}
    {notice && <p className="success" role="status">{notice}</p>}
    {form && <form className="create-session slot-form" onSubmit={save}>
      <div><h3>{form.id === null ? 'Add a slot' : 'Edit slot'}</h3></div>
      <div className="form-grid">
        <label>Name<input required maxLength={60} value={form.label} onChange={e => setForm({ ...form, label: e.target.value })} placeholder="Period 1"/></label>
        <label className="check-label"><input type="checkbox" checked={form.active} onChange={e => setForm({ ...form, active: e.target.checked })}/> Available to teachers</label>
        <label>Starts<input required type="time" value={form.starts} onChange={e => setForm({ ...form, starts: e.target.value })}/></label>
        <label>Ends<input required type="time" value={form.ends} onChange={e => setForm({ ...form, ends: e.target.value })}/></label>
      </div>
      <div className="actions"><button disabled={busy}>{form.id === null ? 'Add slot' : 'Save changes'}</button><button type="button" className="secondary" onClick={() => setForm(null)}>Cancel</button></div>
    </form>}
    {!data && !error && <p role="status">Loading slots…</p>}
    <div className="slot-admin-grid">{data?.slots.map(slot => <div key={slot.id} className={`slot-card ${slot.active ? '' : 'off'}`}>
      <div className="slot-card-top"><span className="slot-name">{slot.label}</span><span className={`status ${slot.active ? 'present' : ''}`}>{slot.active ? 'Visible' : 'Hidden'}</span></div>
      <span className="slot-time">{slot.starts} – {slot.ends}</span>
      {confirmDelete === slot.id
        ? <div className="actions"><span>Delete this slot?</span><button className="danger-solid" disabled={busy} onClick={() => void remove(slot)}>Delete</button><button className="secondary" onClick={() => setConfirmDelete(null)}>Keep</button></div>
        : <div className="actions"><button className="secondary" disabled={busy} onClick={() => { setForm({ id: slot.id, label: slot.label, starts: slot.starts, ends: slot.ends, active: slot.active }); setError(''); setNotice(''); }}>Edit</button>
          <button className="secondary" disabled={busy} onClick={() => void setActive(slot, !slot.active)}>{slot.active ? 'Hide' : 'Show'}</button>
          <button className="secondary danger" disabled={busy} onClick={() => setConfirmDelete(slot.id)}>Delete</button></div>}
    </div>)}</div>
    {data && !data.slots.length && <p className="empty-inline">No slots yet. Add the first one.</p>}
  </section>;
}
