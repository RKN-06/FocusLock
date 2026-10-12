import React, { useEffect, useState } from 'react';
import type { Request } from './scheduling';

type Flag = 'ok' | 'low' | 'stopped' | 'never';
type SessionRow = { id: number; title: string; starts_at: string; room_code: string; state: string; held: boolean; present: number; absent: number; excused: number;
  not_recorded: number | null; rate: number | null; via_qr: number; via_code: number; pending_review: number; joined_focus: number; quit_focus: number; interrupted_students: number; interruptions: number };
type StudentRowData = { id: number; name: string; email: string; present: number; absent: number; excused: number; not_recorded: number; rate: number | null;
  quit_focus: number; interruptions: number; last_status: string | null; flag: Flag };
type Analytics = {
  course: { id: number; code: string; title: string; term: string };
  summary: { students: number; sessions: number; held: number; present_total: number; attendance_rate: number | null; average_present: number | null; joined_focus: number;
    quit_focus_events: number; via_code: number; students_who_quit_focus: number; interruptions: number; stopped_attending: number; never_attended: number; low_attendance: number };
  bands: Record<string, number>; sessions: SessionRow[]; students: StudentRowData[]; low_rate_threshold: number; recent_window: number;
};

const shortDate = (value: string) => new Date(value).toLocaleDateString([], { day: 'numeric', month: 'short' });
const fullDate = (value: string) => new Date(value).toLocaleString([], { dateStyle: 'medium', timeStyle: 'short' });
const level = (value: number | null) => value === null ? '' : value >= 90 ? 'good' : value >= 75 ? 'ok' : 'low';
const FLAGS: Record<Flag, string> = { ok: 'On track', low: 'Low attendance', stopped: 'Stopped attending', never: 'Never attended' };
const ORDER: Record<Flag, number> = { never: 0, stopped: 1, low: 2, ok: 3 };

function RateBar({ value }: { value: number | null }) {
  return <svg className="ratebar" viewBox="0 0 100 8" preserveAspectRatio="none" role="img" aria-label={value === null ? 'No data' : `${value} percent`}>
    <rect className="rb-back" x="0" y="0" width="100" height="8" rx="4"/>
    {value !== null && <rect className={`rb-fill ${level(value)}`} x="0" y="0" width={Math.max(2, value)} height="8" rx="4"/>}
  </svg>;
}

/** Attendance rate for every class that was held, newest on the right. */
function Trend({ rows }: { rows: SessionRow[] }) {
  const held = rows.filter(row => row.held && row.rate !== null).slice(-24);
  if (!held.length) return null;
  const step = 28, width = held.length * step + 8;
  return <svg className="trend" viewBox={`0 0 ${width} 150`} role="img" aria-label="Attendance rate by class">
    {[25, 50, 75, 100].map(mark => <g key={mark}><line className="trend-grid" x1="0" x2={width} y1={120 - mark} y2={120 - mark}/><text className="trend-axis" x="2" y={116 - mark}>{mark}%</text></g>)}
    {held.map((row, index) => {
      const value = row.rate ?? 0, height = Math.max(2, value * 1);
      return <g key={row.id}><title>{`${row.title}, ${fullDate(row.starts_at)}: ${value}% (${row.present} present)`}</title>
        <rect className={`trend-bar ${level(value)}`} x={index * step + 8} y={120 - height} width="18" height={height} rx="3"/>
        <text className="trend-label" x={index * step + 17} y="138" textAnchor="middle">{shortDate(row.starts_at)}</text></g>;
    })}
  </svg>;
}

/** What a teacher wants to know about a course: who attends, who quit focus, and who has drifted away. */
export function CourseAnalytics({ courseId, request }: { courseId: number; request: Request }) {
  const [data, setData] = useState<Analytics | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [onlyFlagged, setOnlyFlagged] = useState(true);
  async function load() {
    setLoading(true); setError('');
    try { setData(await request<Analytics>(`/courses/${courseId}/analytics`)); } catch (e) { setError((e as Error).message); } finally { setLoading(false); }
  }
  useEffect(() => { setData(null); void load(); }, [courseId]);
  if (error) return <section className="content-section"><p className="error" role="alert">{error}</p></section>;
  if (!data) return <section className="content-section"><p role="status">Working out the numbers…</p></section>;
  const { summary } = data;
  const people = [...data.students].sort((a, b) => ORDER[a.flag] - ORDER[b.flag] || (a.rate ?? 101) - (b.rate ?? 101) || a.name.localeCompare(b.name));
  const flagged = people.filter(person => person.flag !== 'ok');
  const shown = onlyFlagged && flagged.length ? flagged : people;
  const held = data.sessions.filter(row => row.held);
  const maxBand = Math.max(1, ...Object.values(data.bands));
  return <section className="content-section analytics">
    <div className="section-heading"><div><p className="eyebrow">Insights</p><h3>{data.course.code} · attendance and focus</h3></div><button className="secondary" onClick={() => void load()} disabled={loading}>{loading ? 'Refreshing…' : 'Refresh'}</button></div>
    {!summary.held && <div className="empty-state"><h3>No classes held yet</h3><p>Numbers appear here after you open check-in for your first session.</p></div>}
    {summary.held > 0 && <>
      <div className="tile-grid">
        <div className={`tile big ${level(summary.attendance_rate)}`}><strong>{summary.attendance_rate === null ? '—' : `${summary.attendance_rate}%`}</strong><span>Overall attendance</span><RateBar value={summary.attendance_rate}/></div>
        <div className="tile"><strong>{summary.held}<small> of {summary.sessions}</small></strong><span>Classes held</span></div>
        <div className="tile"><strong>{summary.average_present ?? '—'}<small> / {summary.students}</small></strong><span>Average present per class</span></div>
        <div className="tile warn"><strong>{summary.students_who_quit_focus}</strong><span>Students who quit focus<small>{summary.quit_focus_events} early exit{summary.quit_focus_events === 1 ? '' : 's'}</small></span></div>
        <div className="tile bad"><strong>{summary.stopped_attending}</strong><span>Stopped attending<small>missed the last {data.recent_window} classes</small></span></div>
        <div className="tile bad"><strong>{summary.never_attended}</strong><span>Never attended</span></div>
        <div className="tile warn"><strong>{summary.low_attendance}</strong><span>Below {data.low_rate_threshold}%</span></div>
        <div className="tile"><strong>{summary.via_code}</strong><span>Check-ins by class code<small>lower assurance than QR</small></span></div>
        <div className="tile"><strong>{summary.interruptions}</strong><span>Focus interruptions<small>{summary.joined_focus} focus sessions joined</small></span></div>
      </div>
      <div className="analytics-pair">
        <div className="mini-card"><h4>Attendance by class</h4><Trend rows={data.sessions}/></div>
        <div className="mini-card"><h4>How students are doing</h4>
          {Object.entries(data.bands).map(([label, count]) => <div key={label} className="band-row"><span>{label}%</span>
            <svg className="ratebar band" viewBox="0 0 100 8" preserveAspectRatio="none" aria-hidden="true"><rect className="rb-back" x="0" y="0" width="100" height="8" rx="4"/><rect className={`rb-fill ${label === '90+' ? 'good' : label === '75-89' ? 'ok' : 'low'}`} x="0" y="0" width={count ? Math.max(3, 100 * count / maxBand) : 0} height="8" rx="4"/></svg>
            <strong>{count}</strong></div>)}</div>
      </div>
      <div className="section-heading"><div><h3>Students</h3></div>{flagged.length > 0 && <label className="check-label inline"><input type="checkbox" checked={onlyFlagged} onChange={e => setOnlyFlagged(e.target.checked)}/> Only students needing attention ({flagged.length})</label>}</div>
      <div className="table-wrap"><table><thead><tr><th>Student</th><th>Attendance</th><th>Present</th><th>Absent</th><th>Excused</th><th>Quit focus</th><th>Interruptions</th><th>Status</th></tr></thead>
        <tbody>{shown.map(person => <tr key={person.id}><td><strong>{person.name}</strong><small>{person.email}</small></td>
          <td className="rate-cell"><span>{person.rate === null ? '—' : `${person.rate}%`}</span><RateBar value={person.rate}/></td>
          <td>{person.present}</td><td>{person.absent}</td><td>{person.excused}</td><td>{person.quit_focus || '—'}</td><td>{person.interruptions || '—'}</td>
          <td><span className={`flag ${person.flag}`}>{FLAGS[person.flag]}</span></td></tr>)}</tbody></table></div>
      <div className="section-heading"><div><h3>Class by class</h3></div></div>
      <div className="table-wrap"><table><thead><tr><th>Class</th><th>Room</th><th>Present</th><th>Absent</th><th>Excused</th><th>Not recorded</th><th>Rate</th><th>Joined focus</th><th>Quit focus</th><th>Interruptions</th></tr></thead>
        <tbody>{[...held].reverse().map(row => <tr key={row.id}><td><strong>{row.title}</strong><small>{fullDate(row.starts_at)}</small></td><td>{row.room_code}</td>
          <td>{row.present}</td><td>{row.absent}</td><td>{row.excused}</td><td>{row.not_recorded}{row.pending_review ? <small>{row.pending_review} awaiting review</small> : null}</td>
          <td className="rate-cell"><span>{row.rate === null ? '—' : `${row.rate}%`}</span><RateBar value={row.rate}/></td><td>{row.joined_focus || '—'}</td><td>{row.quit_focus || '—'}</td><td>{row.interruptions || '—'}</td></tr>)}</tbody></table></div>
    </>}
  </section>;
}
