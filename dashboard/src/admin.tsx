import React, { useEffect, useState } from 'react';
import { AdminSlots } from './scheduling';
import type { Request } from './scheduling';

type Group = { id: number; name: string; members: number; courses: number };
type GroupDetail = { id: number; name: string; members: { id: number; name: string; email: string }[]; courses: { id: number; code: string; title: string; term: string; teacher: string }[] };
type StudentRow = { id: number; name: string; email: string; groups: string[] };
type Teacher = { id: number; name: string; email: string; courses: number };
type CourseOut = { id: number; code: string; title: string; term: string; teacher_id: number; students: number; groups: { id: number; name: string; members: number }[] };
type AddResult = { added: string[]; already: string[]; unknown: string[]; not_student: string[]; enrolled: number };

/** Box-style toggles for choosing one or more student groups. */
function GroupChips({ groups, selected, onToggle, disabled }: { groups: Group[]; selected: Set<number>; onToggle: (id: number) => void; disabled?: boolean }) {
  if (!groups.length) return <p className="empty-inline">No student groups yet. Create them on the Student groups tab first.</p>;
  return <div className="group-chips" role="group" aria-label="Student groups">{groups.map(group => <button type="button" key={group.id} disabled={disabled}
    className={`group-chip ${selected.has(group.id) ? 'on' : ''}`} aria-pressed={selected.has(group.id)} onClick={() => onToggle(group.id)}>
    <span className="group-name">{group.name}</span><span className="group-count">{group.members} student{group.members === 1 ? '' : 's'}</span><span className="slot-tick" aria-hidden="true">✓</span></button>)}</div>;
}

const toggled = (current: Set<number>, id: number) => { const next = new Set(current); if (next.has(id)) next.delete(id); else next.add(id); return next; };

/** Administrators group students into cohorts such as "CSE · Year 3" and manage who is in each. */
export function AdminGroups({ request }: { request: Request }) {
  const [groups, setGroups] = useState<Group[] | null>(null);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [detail, setDetail] = useState<GroupDetail | null>(null);
  const [students, setStudents] = useState<StudentRow[]>([]);
  const [query, setQuery] = useState('');
  const [picked, setPicked] = useState<Set<number>>(new Set());
  const [pasted, setPasted] = useState('');
  const [newName, setNewName] = useState('');
  const [renameTo, setRenameTo] = useState<string | null>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [result, setResult] = useState<AddResult | null>(null);
  async function loadGroups(keep?: number | null) {
    try {
      const rows = await request<Group[]>('/admin/groups');
      setGroups(rows);
      setSelectedId(current => { const want = keep === undefined ? current : keep; return want !== null && rows.some(row => row.id === want) ? want : rows[0]?.id ?? null; });
    } catch (e) { setError((e as Error).message); }
  }
  async function loadDetail(id: number) { try { setDetail(await request<GroupDetail>(`/admin/groups/${id}`)); } catch (e) { setError((e as Error).message); } }
  async function loadStudents() { try { setStudents(await request<StudentRow[]>('/admin/students')); } catch (e) { setError((e as Error).message); } }
  useEffect(() => { void loadGroups(); void loadStudents(); }, []);
  useEffect(() => { if (selectedId === null) { setDetail(null); return; } setPicked(new Set()); setResult(null); setRenameTo(null); setConfirmDelete(false); void loadDetail(selectedId); }, [selectedId]);
  const memberIds = new Set(detail?.members.map(member => member.id));
  const candidates = students.filter(student => !memberIds.has(student.id) && `${student.name} ${student.email}`.toLowerCase().includes(query.toLowerCase().trim()));
  async function run(task: () => Promise<void>) { setBusy(true); setError(''); setNotice(''); try { await task(); } catch (e) { setError((e as Error).message); } finally { setBusy(false); } }
  const createGroup = (event: React.FormEvent) => { event.preventDefault(); void run(async () => {
    const group = await request<Group>('/admin/groups', { method: 'POST', body: JSON.stringify({ name: newName.trim() }) });
    setNewName(''); setNotice(`Group "${group.name}" created.`); await loadGroups(group.id); }); };
  const addMembers = (emails: string[]) => run(async () => {
    if (selectedId === null || !emails.length) return;
    const outcome = await request<AddResult>(`/admin/groups/${selectedId}/members`, { method: 'POST', body: JSON.stringify({ emails }) });
    setResult(outcome); setPicked(new Set()); setPasted('');
    setNotice(`${outcome.added.length} student${outcome.added.length === 1 ? '' : 's'} added.`);
    await Promise.all([loadGroups(selectedId), loadDetail(selectedId), loadStudents()]);
  });
  const removeMember = (id: number) => run(async () => {
    if (selectedId === null) return;
    const outcome = await request<{ removed: number; kept: number }>(`/admin/groups/${selectedId}/members/${id}`, { method: 'DELETE' });
    setNotice(outcome.kept ? `Removed from the group. ${outcome.kept} course enrolment${outcome.kept === 1 ? ' was' : 's were'} kept because attendance is already recorded.` : 'Removed from the group.');
    await Promise.all([loadGroups(selectedId), loadDetail(selectedId), loadStudents()]);
  });
  const rename = (event: React.FormEvent) => { event.preventDefault(); void run(async () => {
    if (selectedId === null || renameTo === null) return;
    await request(`/admin/groups/${selectedId}`, { method: 'PUT', body: JSON.stringify({ name: renameTo.trim() }) });
    setRenameTo(null); setNotice('Group renamed.'); await Promise.all([loadGroups(selectedId), loadDetail(selectedId), loadStudents()]); }); };
  const removeGroup = () => run(async () => {
    if (selectedId === null) return;
    const outcome = await request<{ removed: number; kept: number }>(`/admin/groups/${selectedId}`, { method: 'DELETE' });
    setNotice(`Group deleted.${outcome.removed ? ` ${outcome.removed} enrolment${outcome.removed === 1 ? '' : 's'} removed.` : ''}${outcome.kept ? ` ${outcome.kept} kept because attendance is recorded.` : ''}`);
    setConfirmDelete(false); await Promise.all([loadGroups(null), loadStudents()]);
  });
  const pastedEmails = pasted.split(/[\s,;]+/).map(item => item.trim()).filter(Boolean);
  return <section className="admin-panel">
    <div className="admin-head"><div><h2>Student groups</h2><p>Group students the way your college does, for example "CSE · Year 3 · A". When you link a group to a course, every student in it is enrolled automatically.</p></div></div>
    {error && <p className="error" role="alert">{error}</p>}
    {notice && <p className="success" role="status">{notice}</p>}
    <div className="admin-grid groups-layout">
      <div className="group-column">
        <form className="group-new" onSubmit={createGroup}>
          <label>New group<input required maxLength={80} value={newName} onChange={e => setNewName(e.target.value)} placeholder="CSE · Year 3 · A"/></label>
          <button disabled={busy || !newName.trim()}>Create group</button>
        </form>
        {!groups && !error && <p role="status">Loading groups…</p>}
        {groups && !groups.length && <p className="empty-inline">No groups yet. Create the first one above.</p>}
        <div className="group-list">{groups?.map(group => <button type="button" key={group.id} className={`group-card ${group.id === selectedId ? 'on' : ''}`} aria-pressed={group.id === selectedId} onClick={() => setSelectedId(group.id)}>
          <strong>{group.name}</strong><span>{group.members} student{group.members === 1 ? '' : 's'} · {group.courses} course{group.courses === 1 ? '' : 's'}</span></button>)}</div>
      </div>
      {detail && <div className="group-detail">
        <div className="group-detail-head">
          {renameTo === null ? <h3>{detail.name}</h3> : <form className="rename-form" onSubmit={rename}><input required maxLength={80} autoFocus value={renameTo} onChange={e => setRenameTo(e.target.value)}/><button disabled={busy}>Save</button><button type="button" className="secondary" onClick={() => setRenameTo(null)}>Cancel</button></form>}
          {renameTo === null && <div className="actions">{confirmDelete
            ? <><span>Delete this group?</span><button className="danger-solid" disabled={busy} onClick={() => void removeGroup()}>Delete</button><button className="secondary" onClick={() => setConfirmDelete(false)}>Keep</button></>
            : <><button className="secondary" onClick={() => setRenameTo(detail.name)}>Rename</button><button className="secondary danger" onClick={() => setConfirmDelete(true)}>Delete</button></>}</div>}
        </div>
        <h4>Members <small>{detail.members.length}</small></h4>
        {!detail.members.length && <p className="empty-inline">No students in this group yet.</p>}
        <div className="member-chips">{detail.members.map(member => <span key={member.id} className="member-chip" title={member.email}>{member.name}<button type="button" aria-label={`Remove ${member.name}`} disabled={busy} onClick={() => void removeMember(member.id)}>×</button></span>)}</div>
        <h4>Add students</h4>
        <label>Search students<input type="search" value={query} onChange={e => setQuery(e.target.value)} placeholder="Name or email"/></label>
        <div className="student-pick" role="group" aria-label="Students you can add">{candidates.slice(0, 200).map(student => <label key={student.id} className={`pick-row ${picked.has(student.id) ? 'on' : ''}`}>
          <input type="checkbox" checked={picked.has(student.id)} onChange={() => setPicked(current => toggled(current, student.id))}/>
          <span className="pick-name">{student.name}</span><span className="pick-email">{student.email}</span>{student.groups.map(name => <span key={name} className="chip">{name}</span>)}</label>)}
          {!candidates.length && <p className="empty-inline">{students.length ? 'No matching students outside this group.' : 'No student accounts exist yet.'}</p>}</div>
        <div className="actions"><button disabled={busy || !picked.size} onClick={() => void addMembers(students.filter(student => picked.has(student.id)).map(student => student.email))}>{picked.size ? `Add ${picked.size} selected` : 'Add selected'}</button>
          <button type="button" className="secondary" disabled={!candidates.length} onClick={() => setPicked(new Set(candidates.slice(0, 200).map(student => student.id)))}>Select all shown</button>
          <button type="button" className="secondary" disabled={!picked.size} onClick={() => setPicked(new Set())}>Clear</button></div>
        <label>Or paste email addresses <small>(one per line, or separated by commas)</small><textarea rows={4} value={pasted} onChange={e => setPasted(e.target.value)} spellCheck={false} placeholder={'asha@college.edu\nravi@college.edu'}/></label>
        <div className="actions"><button className="secondary" disabled={busy || !pastedEmails.length} onClick={() => void addMembers(pastedEmails)}>{pastedEmails.length ? `Add ${pastedEmails.length} pasted address${pastedEmails.length === 1 ? '' : 'es'}` : 'Add pasted addresses'}</button></div>
        {result && (result.unknown.length > 0 || result.not_student.length > 0 || result.already.length > 0) && <div className="inline-hint" role="status"><strong>Some addresses were not added</strong><ul>
          {result.already.length > 0 && <li>Already in the group: {result.already.join(', ')}</li>}
          {result.unknown.length > 0 && <li>No account found (they must sign up or be provisioned first): {result.unknown.join(', ')}</li>}
          {result.not_student.length > 0 && <li>Not student accounts: {result.not_student.join(', ')}</li>}</ul></div>}
        <h4>Courses using this group <small>{detail.courses.length}</small></h4>
        {!detail.courses.length && <p className="empty-inline">Not linked to a course yet. Link it on the Courses tab.</p>}
        {detail.courses.map(course => <div key={course.id} className="course-line"><strong>{course.code}</strong><span>{course.title}</span><small>{course.teacher} · {course.term}</small></div>)}
      </div>}
    </div>
  </section>;
}

type CourseLine = { code: string; title: string; problems: string[] };

/** One course per line: "CODE, Title" or two spreadsheet columns pasted with a tab. */
function parseCourses(text: string): CourseLine[] {
  const seen = new Set<string>();
  return text.split(/\r?\n/).map(line => line.trim()).filter(Boolean).map(line => {
    const tab = line.indexOf('\t');
    const cut = tab >= 0 ? tab : line.indexOf(',');
    const code = (cut >= 0 ? line.slice(0, cut) : line).trim();
    const title = cut >= 0 ? line.slice(cut + 1).trim() : '';
    const problems: string[] = [];
    if (!code) problems.push('Missing code'); else if (code.length > 32) problems.push('Code is over 32 characters');
    if (!title) problems.push('Missing title'); else if (title.length > 150) problems.push('Title is over 150 characters');
    if (code && seen.has(code.toLowerCase())) problems.push('Repeated in this list');
    if (code) seen.add(code.toLowerCase());
    return { code, title, problems };
  });
}

/** Administrators give a teacher many courses at once and choose which student groups attend them. */
export function AdminCourses({ request }: { request: Request }) {
  const [teachers, setTeachers] = useState<Teacher[] | null>(null);
  const [teacherId, setTeacherId] = useState<number | null>(null);
  const [courses, setCourses] = useState<CourseOut[]>([]);
  const [groups, setGroups] = useState<Group[]>([]);
  const [chosen, setChosen] = useState<Set<number>>(new Set());
  const [editing, setEditing] = useState<{ id: number; groups: Set<number> } | null>(null);
  const [term, setTerm] = useState('');
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [skipped, setSkipped] = useState<{ code: string; reason: string }[]>([]);
  const lines = parseCourses(text);
  const bad = lines.some(line => line.problems.length > 0);
  const teacher = teachers?.find(row => row.id === teacherId) ?? null;
  async function loadTeachers(keep?: number | null) {
    try {
      const rows = await request<Teacher[]>('/admin/teachers');
      setTeachers(rows);
      setTeacherId(current => keep ?? current ?? rows[0]?.id ?? null);
    } catch (e) { setError((e as Error).message); }
  }
  async function loadGroups() { try { setGroups(await request<Group[]>('/admin/groups')); } catch (e) { setError((e as Error).message); } }
  async function loadCourses(id: number) { setCourses(await request<CourseOut[]>(`/admin/teachers/${id}/courses`)); }
  useEffect(() => { void loadTeachers(); void loadGroups(); }, []);
  useEffect(() => {
    if (teacherId === null) { setCourses([]); return; }
    let active = true;
    request<CourseOut[]>(`/admin/teachers/${teacherId}/courses`).then(rows => { if (active) setCourses(rows); }).catch(e => { if (active) setError((e as Error).message); });
    return () => { active = false; };
  }, [teacherId]);
  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (teacherId === null || !lines.length || bad) return;
    setBusy(true); setError(''); setNotice(''); setSkipped([]);
    try {
      const result = await request<{ created: CourseOut[]; skipped: { code: string; reason: string }[]; enrolled: number }>('/admin/courses/bulk', { method: 'POST',
        body: JSON.stringify({ teacher_id: teacherId, term: term.trim(), group_ids: [...chosen], courses: lines.map(line => ({ code: line.code, title: line.title })) }) });
      setSkipped(result.skipped);
      setNotice(`${result.created.length} course${result.created.length === 1 ? '' : 's'} added to ${teacher?.name ?? 'the teacher'}${result.enrolled ? `, ${result.enrolled} student enrolments created` : ''}${result.skipped.length ? `, ${result.skipped.length} skipped` : ''}.`);
      if (result.created.length) setText('');
      await Promise.all([loadTeachers(teacherId), loadGroups(), loadCourses(teacherId)]);
    } catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  }
  async function saveGroups() {
    if (!editing || teacherId === null) return;
    setBusy(true); setError(''); setNotice('');
    try {
      const result = await request<{ sync: { added: number; removed: number; kept: number } }>(`/admin/courses/${editing.id}/groups`, { method: 'PUT', body: JSON.stringify({ group_ids: [...editing.groups] }) });
      setNotice(`Groups updated: ${result.sync.added} enrolled, ${result.sync.removed} removed${result.sync.kept ? `, ${result.sync.kept} kept because attendance is recorded` : ''}.`);
      setEditing(null); await Promise.all([loadCourses(teacherId), loadGroups()]);
    } catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  }
  return <section className="admin-panel">
    <div className="admin-head"><div><h2>Courses for a teacher</h2><p>Choose a teacher, paste all their courses at once, and pick the student groups who attend them.</p></div></div>
    {error && <p className="error" role="alert">{error}</p>}
    {notice && <p className="success" role="status">{notice}</p>}
    {!teachers && !error && <p role="status">Loading teachers…</p>}
    {teachers && !teachers.length && <p className="empty-inline">There are no teacher accounts yet.</p>}
    {teachers && teachers.length > 0 && <div className="admin-grid">
      <form className="create-session" onSubmit={submit}>
        <div className="form-grid">
          <label>Teacher<select value={teacherId ?? ''} onChange={e => { setTeacherId(Number(e.target.value)); setSkipped([]); setNotice(''); setEditing(null); }}>{teachers.map(row => <option key={row.id} value={row.id}>{row.name} · {row.courses} course{row.courses === 1 ? '' : 's'}</option>)}</select></label>
          <label>Term<input required maxLength={40} value={term} onChange={e => setTerm(e.target.value)} placeholder="Autumn 2026"/></label>
        </div>
        <fieldset className="schedule-step"><legend>Student groups attending <small>{chosen.size} selected</small></legend>
          <GroupChips groups={groups} selected={chosen} onToggle={id => setChosen(current => toggled(current, id))}/>
          <p className="hint">Everyone in the chosen groups is enrolled in each course below. You can change this later.</p></fieldset>
        <label>Courses <small>(CODE, Title — one per line; a pasted spreadsheet works too)</small>
          <textarea rows={6} value={text} onChange={e => setText(e.target.value)} placeholder={'CS201, Data Structures\nCS202, Algorithms\nMA110, Linear Algebra'} spellCheck={false}/></label>
        {lines.length > 0 && <div className="parse-list" aria-label="Preview">{lines.map((line, index) => <div key={index} className={`parse-row ${line.problems.length ? 'bad' : ''}`}>
          <span className="parse-code">{line.code || '—'}</span><span className="parse-title">{line.title || '—'}</span><span className="parse-state">{line.problems.length ? line.problems.join(', ') : 'Ready'}</span></div>)}</div>}
        {skipped.length > 0 && <div className="inline-hint" role="status"><strong>Skipped</strong><ul>{skipped.map(item => <li key={item.code}>{item.code}: {item.reason}</li>)}</ul></div>}
        <div className="actions"><button disabled={busy || !lines.length || bad || !term.trim() || teacherId === null}>{busy ? 'Adding…' : lines.length ? `Add ${lines.length} course${lines.length === 1 ? '' : 's'}${teacher ? ` to ${teacher.name}` : ''}` : 'Add courses'}</button>
          <button type="button" className="secondary" disabled={!text} onClick={() => setText('')}>Clear</button></div>
      </form>
      <aside className="teacher-courses"><h3>{teacher?.name ?? 'Teacher'}'s courses <small>{courses.length}</small></h3>
        {!courses.length && <p className="empty-inline">No courses yet.</p>}
        {courses.map(course => <div key={course.id} className="course-line wide">
          <strong>{course.code}</strong><span>{course.title}</span><small>{course.term} · {course.students} student{course.students === 1 ? '' : 's'}</small>
          {editing?.id === course.id
            ? <div className="course-edit"><GroupChips groups={groups} selected={editing.groups} onToggle={id => setEditing({ id: editing.id, groups: toggled(editing.groups, id) })} disabled={busy}/>
              <div className="actions"><button disabled={busy} onClick={() => void saveGroups()}>Save groups</button><button className="secondary" onClick={() => setEditing(null)}>Cancel</button></div></div>
            : <div className="course-groups">{course.groups.length ? course.groups.map(group => <span key={group.id} className="chip">{group.name}</span>) : <span className="muted-note">No group linked</span>}
              <button className="secondary" onClick={() => setEditing({ id: course.id, groups: new Set(course.groups.map(group => group.id)) })}>Change groups</button></div>}
        </div>)}
      </aside>
    </div>}
  </section>;
}

/** The administrator's home: student groups, teacher courses and time slots. */
export function AdminConsole({ request }: { request: Request }) {
  const [tab, setTab] = useState<'groups' | 'courses' | 'slots'>('groups');
  const tabs: ['groups' | 'courses' | 'slots', string][] = [['groups', 'Student groups'], ['courses', 'Courses'], ['slots', 'Time slots']];
  return <main className="dashboard-shell">
    <div className="page-heading"><div><p className="eyebrow">Administration</p><h1>Campus setup</h1></div></div>
    <nav className="section-tabs admin-tabs three" aria-label="Administration sections">
      {tabs.map(([key, label]) => <button type="button" key={key} className={tab === key ? 'active' : ''} aria-current={tab === key ? 'page' : undefined} onClick={() => setTab(key)}>{label}</button>)}
    </nav>
    {tab === 'groups' ? <AdminGroups request={request}/> : tab === 'courses' ? <AdminCourses request={request}/> : <AdminSlots request={request}/>}
  </main>;
}
