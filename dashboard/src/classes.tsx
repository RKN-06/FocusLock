import React from 'react';
import { CourseAnalytics } from './analytics';
import type { Request } from './scheduling';

export type ClassCourse = { id: number; code: string; title: string; term: string; groups?: { id: number; name: string; members: number }[]; students?: number };

/** The teacher's classes (courses): which student groups attend each one, and how each is going. Sessions live on their own tab. */
export function ClassesView({ courses, selectedId, loading, onSelect, onOpenSessions, request }: {
  courses: ClassCourse[]; selectedId: number | null; loading: boolean; onSelect: (course: ClassCourse) => void; onOpenSessions: () => void; request: Request;
}) {
  const selected = courses.find(course => course.id === selectedId) ?? null;
  if (!loading && !courses.length) return <section className="panel"><h2>No classes yet</h2><p>No courses are assigned to this account. Ask an administrator to add them.</p></section>;
  return <div className="classes-view">
    <div className="class-grid" role="group" aria-label="Your classes">{courses.map(course => <button type="button" key={course.id}
      className={`class-card ${course.id === selectedId ? 'on' : ''}`} aria-pressed={course.id === selectedId} onClick={() => onSelect(course)}>
      <span className="eyebrow">{course.code}</span><strong>{course.title}</strong><small>{course.term} · {course.students ?? 0} student{course.students === 1 ? '' : 's'}</small>
      <span className="class-groups">{course.groups && course.groups.length ? course.groups.map(group => <span key={group.id} className="chip">{group.name}</span>) : <span className="muted-note">No student group linked</span>}</span>
    </button>)}</div>
    {selected && <section className="panel class-detail">
      <div className="course-header"><div><p className="eyebrow">{selected.code}</p><h2>{selected.title}</h2><p>{selected.term}</p>
        <div className="course-for">{selected.groups && selected.groups.length ? <>{selected.groups.map(group => <span key={group.id} className="chip">{group.name}</span>)}<span className="muted-note">{selected.students ?? 0} student{selected.students === 1 ? '' : 's'}</span></> : <span className="muted-note">No student group linked yet. Ask an administrator to link one.</span>}</div></div>
        <button onClick={onOpenSessions}>Open sessions →</button></div>
      <CourseAnalytics courseId={selected.id} request={request}/>
    </section>}
  </div>;
}
