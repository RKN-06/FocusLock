import React, { useEffect, useState } from 'react';
import type { Request } from './scheduling';

type Flag = 'ok' | 'low' | 'stopped' | 'never';
type SessionRow = { id: number; title: string; starts_at: string; room_code: string; state: string; held: boolean; present: number; absent: number; excused: number;
  not_recorded: number | null; rate: number | null; via_qr: number; via_code: number; pending_review: number; joined_focus: number; quit_focus: number; interrupted_students: number; interruptions: number; breaks: number };
type StudentRowData = { id: number; name: string; email: string; present: number; absent: number; excused: number; not_recorded: number; rate: number | null;
  quit_focus: number; interruptions: number; breaks: number; last_status: string | null; flag: Flag };
type Period = { period: string; label: string; sessions: number; present: number; expected: number; rate: number | null; quit_focus: number; breaks: number };
type Range = 'weekly' | 'monthly' | 'overall';
type Analytics = {
  course: { id: number; code: string; title: string; term: string };
  summary: { students: number; sessions: number; held: number; present_total: number; attendance_rate: number | null; average_present: number | null; joined_focus: number;
    quit_focus_events: number; breaks: number; via_code: number; students_who_quit_focus: number; interruptions: number; stopped_attending: number; never_attended: number; low_attendance: number };
  weekly: Period[]; monthly: Period[]; bands: Record<string, number>; sessions: SessionRow[]; students: StudentRowData[]; low_rate_threshold: number; recent_window: number;
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

const RANGES: [Range, string][] = [['weekly', 'Weekly'], ['monthly', 'Monthly'], ['overall', 'Complete']];

function RangeTabs({ value, onChange }: { value: Range; onChange: (range: Range) => void }) {
  return <div className="range-tabs" role="group" aria-label="Time range">{RANGES.map(([key, label]) => <button type="button" key={key} className={`secondary ${value === key ? 'on' : ''}`} aria-pressed={value === key} onClick={() => onChange(key)}>{label}</button>)}</div>;
}

/** Attendance rate for each week or month. */
function PeriodChart({ rows }: { rows: Period[] }) {
  const shown = rows.slice(-18);
  if (!shown.length) return <p className="empty-inline">Nothing held in this range yet.</p>;
  const step = 64, width = shown.length * step + 8;
  return <svg className="trend" viewBox={`0 0 ${width} 150`} role="img" aria-label="Attendance rate by period">
    {[25, 50, 75, 100].map(mark => <g key={mark}><line className="trend-grid" x1="0" x2={width} y1={120 - mark} y2={120 - mark}/><text className="trend-axis" x="2" y={116 - mark}>{mark}%</text></g>)}
    {shown.map((row, index) => {
      const value = row.rate ?? 0, height = Math.max(2, value);
      return <g key={row.period}><title>{`${row.label}: ${value}% (${row.present} of ${row.expected} across ${row.sessions} class${row.sessions === 1 ? '' : 'es'})`}</title>
        <rect className={`trend-bar ${level(value)}`} x={index * step + 12} y={120 - height} width="40" height={height} rx="4"/>
        <text className="trend-label" x={index * step + 32} y="138" textAnchor="middle">{row.label.replace('Week of ', '')}</text></g>;
    })}
  </svg>;
}

function PeriodTable({ rows, who }: { rows: Period[]; who: string }) {
  if (!rows.length) return null;
  return <div className="table-wrap"><table><thead><tr><th>Period</th><th>Classes</th><th>{who}</th><th>Attendance</th><th>Breaks asked</th><th>Quit focus</th></tr></thead>
    <tbody>{[...rows].reverse().map(row => <tr key={row.period}><td><strong>{row.label}</strong></td><td>{row.sessions}</td><td>{row.present} of {row.expected}</td>
      <td className="rate-cell"><span>{row.rate === null ? '—' : `${row.rate}%`}</span><RateBar value={row.rate}/></td><td>{row.breaks || '—'}</td><td>{row.quit_focus || '—'}</td></tr>)}</tbody></table></div>;
}

type StudentData = {
  course: { id: number; code: string; title: string }; student: { id: number; name: string; email: string; kind: string };
  totals: { held: number; present: number; absent: number; excused: number; not_recorded: number; rate: number | null; breaks: number; breaks_approved: number; resume_requests: number; quit_focus: number; interruptions: number; via_qr: number; via_code: number };
  weekly: Period[]; monthly: Period[];
  history: { id: number; title: string; starts_at: string; room_code: string; status: string | null; method: string | null; breaks: number; quit_focus: number; interruptions: number }[];
};

/** One student's record in a course, by week, by month and overall. */
function StudentAnalytics({ courseId, studentId, request, onClose }: { courseId: number; studentId: number; request: Request; onClose: () => void }) {
  const [data, setData] = useState<StudentData | null>(null);
  const [error, setError] = useState('');
  const [range, setRange] = useState<Range>('weekly');
  const [filter, setFilter] = useState<'all' | 'attended' | 'missed'>('all');
  const [query, setQuery] = useState('');
  useEffect(() => {
    let active = true;
    setData(null); setError('');
    request<StudentData>(`/courses/${courseId}/students/${studentId}/analytics`).then(row => { if (active) setData(row); }).catch(e => { if (active) setError((e as Error).message); });
    return () => { active = false; };
  }, [courseId, studentId]);
  if (error) return <div className="student-detail"><p className="error" role="alert">{error}</p><button className="secondary" onClick={onClose}>Close</button></div>;
  if (!data) return <div className="student-detail"><p role="status">Loading this student…</p></div>;
  const t = data.totals;
  const rows = data.history.filter(row => (filter === 'all' || (filter === 'attended' ? row.status === 'present' : row.status !== 'present'))
    && `${row.title} ${row.room_code}`.toLowerCase().includes(query.toLowerCase().trim())).reverse();
  return <div className="student-detail">
    <div className="section-heading"><div><p className="eyebrow">Student</p><h3>{data.student.name}{data.student.kind !== 'regular' && <span className="chip kind-chip">{data.student.kind}</span>}</h3><small>{data.student.email}</small></div><button className="secondary" onClick={onClose}>Close</button></div>
    {!t.held && <p className="empty-inline">No classes have been held yet.</p>}
    {t.held > 0 && <>
      <div className="tile-grid">
        <div className={`tile big ${level(t.rate)}`}><strong>{t.rate === null ? '—' : `${t.rate}%`}</strong><span>Attendance so far</span><RateBar value={t.rate}/></div>
        <div className="tile"><strong>{t.present}<small> of {t.held}</small></strong><span>Classes attended</span></div>
        <div className="tile bad"><strong>{t.absent + t.not_recorded}</strong><span>Classes missed<small>{t.excused ? `${t.excused} excused` : 'none excused'}</small></span></div>
        <div className="tile warn"><strong>{t.breaks}</strong><span>Breaks asked for<small>{t.breaks_approved} approved</small></span></div>
        <div className="tile warn"><strong>{t.quit_focus}</strong><span>Times they quit focus</span></div>
        <div className="tile"><strong>{t.interruptions}</strong><span>Focus interruptions<small>{t.resume_requests} asked to continue</small></span></div>
        <div className="tile"><strong>{t.via_qr + t.via_code}</strong><span>Checked in themselves<small>{t.via_code} by class code</small></span></div>
      </div>
      <RangeTabs value={range} onChange={setRange}/>
      {range === 'overall'
        ? <div className="mini-card"><h4>Every class</h4><div className="table-wrap"><table><thead><tr><th>Class</th><th>Result</th><th>Breaks asked</th><th>Quit focus</th><th>Interruptions</th></tr></thead>
            <tbody>{[...data.history].reverse().map(row => <tr key={row.id}><td><strong>{row.title}</strong><small>{fullDate(row.starts_at)}</small></td><td><span className={`status ${row.status ?? ''}`}>{row.status ?? 'Not recorded'}</span></td>
              <td>{row.breaks || '—'}</td><td>{row.quit_focus || '—'}</td><td>{row.interruptions || '—'}</td></tr>)}</tbody></table></div></div>
        : <><div className="mini-card"><h4>{range === 'weekly' ? 'Attendance by week' : 'Attendance by month'}</h4><PeriodChart rows={range === 'weekly' ? data.weekly : data.monthly}/></div>
          <PeriodTable rows={range === 'weekly' ? data.weekly : data.monthly} who="Attended"/></>}
      <div className="student-search"><label>Find a class<input type="search" value={query} onChange={e => setQuery(e.target.value)} placeholder="Search by class title or room"/></label>
        <div className="range-tabs" role="group" aria-label="Filter classes">{([['all', 'All'], ['attended', 'Attended'], ['missed', 'Not attended']] as const).map(([key, label]) => <button type="button" key={key} className={`secondary ${filter === key ? 'on' : ''}`} aria-pressed={filter === key} onClick={() => setFilter(key)}>{label}</button>)}</div></div>
      <div className="table-wrap"><table><thead><tr><th>Class</th><th>Result</th><th>How</th></tr></thead>
        <tbody>{rows.map(row => <tr key={row.id}><td><strong>{row.title}</strong><small>{fullDate(row.starts_at)} · {row.room_code}</small></td><td><span className={`status ${row.status ?? ''}`}>{row.status ?? 'Not recorded'}</span></td>
          <td>{row.method === 'qr' ? 'QR check-in' : row.method === 'code' ? 'Class code' : row.method ? 'Teacher-confirmed' : '—'}</td></tr>)}</tbody></table></div>
      {!rows.length && <p className="empty-inline">No classes match.</p>}
    </>}
  </div>;
}

/** What a teacher wants to know about a course: who attends, who quit focus, and who has drifted away. */
export function CourseAnalytics({ courseId, request }: { courseId: number; request: Request }) {
  const [data, setData] = useState<Analytics | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [onlyFlagged, setOnlyFlagged] = useState(true);
  const [range, setRange] = useState<Range>('weekly');
  const [find, setFind] = useState('');
  const [studentId, setStudentId] = useState<number | null>(null);
  async function load() {
    setLoading(true); setError('');
    try { setData(await request<Analytics>(`/courses/${courseId}/analytics`)); } catch (e) { setError((e as Error).message); } finally { setLoading(false); }
  }
  useEffect(() => { setData(null); setStudentId(null); setFind(''); void load(); }, [courseId]);
  if (error) return <section className="content-section"><p className="error" role="alert">{error}</p></section>;
  if (!data) return <section className="content-section"><p role="status">Working out the numbers…</p></section>;
  const { summary } = data;
  const people = [...data.students].sort((a, b) => ORDER[a.flag] - ORDER[b.flag] || (a.rate ?? 101) - (b.rate ?? 101) || a.name.localeCompare(b.name));
  const flagged = people.filter(person => person.flag !== 'ok');
  const text = find.toLowerCase().trim();
  const shown = (text ? people : onlyFlagged && flagged.length ? flagged : people).filter(person => `${person.name} ${person.email}`.toLowerCase().includes(text));
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
        <div className="tile warn"><strong>{summary.breaks}</strong><span>Breaks asked for<small>early-exit requests</small></span></div>
      </div>
      <div className="analytics-pair">
        <div className="mini-card"><div className="section-heading"><h4>{range === 'overall' ? 'Attendance by class' : range === 'weekly' ? 'Attendance by week' : 'Attendance by month'}</h4><RangeTabs value={range} onChange={setRange}/></div>
          {range === 'overall' ? <Trend rows={data.sessions}/> : <PeriodChart rows={range === 'weekly' ? data.weekly : data.monthly}/>}</div>
        <div className="mini-card"><h4>How students are doing</h4>
          {Object.entries(data.bands).map(([label, count]) => <div key={label} className="band-row"><span>{label}%</span>
            <svg className="ratebar band" viewBox="0 0 100 8" preserveAspectRatio="none" aria-hidden="true"><rect className="rb-back" x="0" y="0" width="100" height="8" rx="4"/><rect className={`rb-fill ${label === '90+' ? 'good' : label === '75-89' ? 'ok' : 'low'}`} x="0" y="0" width={count ? Math.max(3, 100 * count / maxBand) : 0} height="8" rx="4"/></svg>
            <strong>{count}</strong></div>)}</div>
      </div>
      {range !== 'overall' && <PeriodTable rows={range === 'weekly' ? data.weekly : data.monthly} who="Present"/>}
      <div className="section-heading"><div><h3>Students</h3></div>{!text && flagged.length > 0 && <label className="check-label inline"><input type="checkbox" checked={onlyFlagged} onChange={e => setOnlyFlagged(e.target.checked)}/> Only students needing attention ({flagged.length})</label>}</div>
      <div className="student-search"><label>Find a student<input type="search" value={find} onChange={e => setFind(e.target.value)} placeholder="Search by name or email, then open their record"/></label></div>
      {studentId !== null && <StudentAnalytics courseId={courseId} studentId={studentId} request={request} onClose={() => setStudentId(null)}/>}
      <div className="table-wrap"><table><thead><tr><th>Student</th><th>Attendance</th><th>Present</th><th>Absent</th><th>Excused</th><th>Breaks asked</th><th>Quit focus</th><th>Interruptions</th><th>Status</th></tr></thead>
        <tbody>{shown.map(person => <tr key={person.id}><td><button type="button" className="link-button" onClick={() => setStudentId(person.id)}>{person.name}</button><small>{person.email}</small></td>
          <td className="rate-cell"><span>{person.rate === null ? '—' : `${person.rate}%`}</span><RateBar value={person.rate}/></td>
          <td>{person.present}</td><td>{person.absent}</td><td>{person.excused}</td><td>{person.breaks || '—'}</td><td>{person.quit_focus || '—'}</td><td>{person.interruptions || '—'}</td>
          <td><span className={`flag ${person.flag}`}>{FLAGS[person.flag]}</span></td></tr>)}</tbody></table></div>
      {!shown.length && <p className="empty-inline">No students match.</p>}
      <div className="section-heading"><div><h3>Class by class</h3></div></div>
      <div className="table-wrap"><table><thead><tr><th>Class</th><th>Room</th><th>Present</th><th>Absent</th><th>Excused</th><th>Not recorded</th><th>Rate</th><th>Joined focus</th><th>Quit focus</th><th>Interruptions</th></tr></thead>
        <tbody>{[...held].reverse().map(row => <tr key={row.id}><td><strong>{row.title}</strong><small>{fullDate(row.starts_at)}</small></td><td>{row.room_code}</td>
          <td>{row.present}</td><td>{row.absent}</td><td>{row.excused}</td><td>{row.not_recorded}{row.pending_review ? <small>{row.pending_review} awaiting review</small> : null}</td>
          <td className="rate-cell"><span>{row.rate === null ? '—' : `${row.rate}%`}</span><RateBar value={row.rate}/></td><td>{row.joined_focus || '—'}</td><td>{row.quit_focus || '—'}</td><td>{row.interruptions || '—'}</td></tr>)}</tbody></table></div>
    </>}
  </section>;
}
