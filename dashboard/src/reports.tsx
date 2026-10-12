import React, { useEffect, useState } from 'react';
import type { Request } from './scheduling';

type CourseLine = { id: number; code: string; title: string; term: string; students: number; held: number; present: number; absent: number; rate: number | null };
type DayLine = { date: string; starts_at: string; course_id: number; code: string; session_id: number; title: string; room_code: string; expected: number; present: number; excused: number; absent: number; rate: number | null };
type Report = { terms: string[]; term: string | null; courses: CourseLine[]; daily: DayLine[] };

const dayLabel = (date: string) => new Date(`${date}T12:00:00`).toLocaleDateString([], { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' });
const level = (value: number | null) => value === null ? '' : value >= 90 ? 'good' : value >= 75 ? 'ok' : 'low';

function RateBar({ value }: { value: number | null }) {
  return <svg className="ratebar" viewBox="0 0 100 8" preserveAspectRatio="none" role="img" aria-label={value === null ? 'No data' : `${value} percent`}>
    <rect className="rb-back" x="0" y="0" width="100" height="8" rx="4"/>
    {value !== null && <rect className={`rb-fill ${level(value)}`} x="0" y="0" width={Math.max(2, value)} height="8" rx="4"/>}
  </svg>;
}

/** Term-wise and course-wise attendance for a teacher: how each course is doing, and how many students were absent on each day. */
export function TeacherReports({ request }: { request: Request }) {
  const [report, setReport] = useState<Report | null>(null);
  const [term, setTerm] = useState<string | null>(null);
  const [courseId, setCourseId] = useState<number | 'all'>('all');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  useEffect(() => {
    let active = true;
    setLoading(true); setError('');
    request<Report>(`/reports/teacher${term ? `?term=${encodeURIComponent(term)}` : ''}`)
      .then(row => { if (active) { setReport(row); if (term === null) setTerm(row.term); } })
      .catch(e => { if (active) setError((e as Error).message); }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [term]);
  if (error) return <section className="panel"><p className="error" role="alert">{error}</p></section>;
  if (!report) return <section className="panel"><p role="status">Loading reports…</p></section>;
  if (!report.terms.length) return <section className="panel"><h2>No reports yet</h2><p>Reports appear once you have courses and have held classes.</p></section>;
  const courses = courseId === 'all' ? report.courses : report.courses.filter(row => row.id === courseId);
  const days = report.daily.filter(row => courseId === 'all' || row.course_id === courseId);
  const byDate = new Map<string, DayLine[]>();
  for (const row of days) byDate.set(row.date, [...(byDate.get(row.date) ?? []), row]);
  const dates = [...byDate.keys()].sort().reverse();
  const totals = courses.reduce((sum, row) => ({ held: sum.held + row.held, present: sum.present + row.present, absent: sum.absent + row.absent }), { held: 0, present: 0, absent: 0 });
  return <section className="panel reports-view">
    <div className="section-heading"><div><p className="eyebrow">Reports</p><h2>Attendance by term and course</h2></div></div>
    <div className="student-search">
      <label>Term / semester<select value={term ?? ''} disabled={loading} onChange={e => { setTerm(e.target.value); setCourseId('all'); }}>{report.terms.map(name => <option key={name} value={name}>{name}</option>)}</select></label>
      <label>Course<select value={courseId} onChange={e => setCourseId(e.target.value === 'all' ? 'all' : Number(e.target.value))}><option value="all">All courses this term</option>{report.courses.map(row => <option key={row.id} value={row.id}>{row.code} · {row.title}</option>)}</select></label>
    </div>
    <div className="tile-grid">
      <div className="tile"><strong>{courses.length}</strong><span>Course{courses.length === 1 ? '' : 's'}</span></div>
      <div className="tile"><strong>{totals.held}</strong><span>Classes held</span></div>
      <div className="tile bad"><strong>{totals.absent}</strong><span>Absences<small>students × classes, including not recorded</small></span></div>
    </div>
    <div className="table-wrap"><table><thead><tr><th>Course</th><th>Students</th><th>Classes held</th><th>Present</th><th>Absent</th><th>Attendance</th></tr></thead>
      <tbody>{courses.map(row => <tr key={row.id}><td><strong>{row.code}</strong><small>{row.title}</small></td><td>{row.students}</td><td>{row.held}</td><td>{row.present}</td><td>{row.absent}</td>
        <td className="rate-cell"><span>{row.rate === null ? '—' : `${row.rate}%`}</span><RateBar value={row.rate}/></td></tr>)}</tbody></table></div>
    <div className="section-heading"><div><h3>Day by day</h3><small>How many students were absent in each class</small></div></div>
    {!dates.length && <p className="empty-inline">No classes held in this selection yet.</p>}
    {dates.map(date => <div className="mini-card day-card" key={date}><h4>{dayLabel(date)}</h4>
      <div className="table-wrap"><table><thead><tr><th>Course</th><th>Class</th><th>Present</th><th>Absent</th><th>Excused</th><th>Attendance</th></tr></thead>
        <tbody>{byDate.get(date)!.map(row => <tr key={row.session_id}><td><strong>{row.code}</strong></td><td>{row.title}<small>{row.room_code}</small></td><td>{row.present} of {row.expected}</td>
          <td>{row.absent ? <span className="flag low">{row.absent} absent</span> : '—'}</td><td>{row.excused || '—'}</td>
          <td className="rate-cell"><span>{row.rate === null ? '—' : `${row.rate}%`}</span><RateBar value={row.rate}/></td></tr>)}</tbody></table></div></div>)}
  </section>;
}
