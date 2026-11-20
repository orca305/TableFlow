"use client";

import { useEffect, useRef } from "react";

/** 용어 옆에 붙는 도움말 아이콘. 마우스를 올리거나 키보드로 이동하면 설명이 나온다. */
export function Help({ text }: { text: string }) {
  return <span className="q" tabIndex={0} role="img" aria-label={`도움말: ${text}`} data-tip={text}>?</span>;
}

/** data-tip 속성이 있는 모든 요소의 설명을 한 곳에서 보여준다(overflow에 잘리지 않도록 화면 기준 위치). */
export function TipLayer() {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const tip = ref.current; if (!tip) return;
    const hide = () => { tip.style.display = "none"; };
    const show = (element: Element) => {
      const text = element.getAttribute("data-tip"); if (!text) return;
      tip.textContent = text; tip.style.left = "0px"; tip.style.top = "0px"; tip.style.display = "block";
      element.setAttribute("aria-describedby", "tip");
      const r = element.getBoundingClientRect(), w = tip.offsetWidth, h = tip.offsetHeight;
      const x = Math.min(Math.max(8, r.left + r.width / 2 - w / 2), window.innerWidth - w - 8);
      let y = r.bottom + 8; if (y + h > window.innerHeight - 8) y = r.top - h - 8;
      tip.style.left = `${x}px`; tip.style.top = `${Math.max(8, y)}px`;
    };
    const target = (event: Event) => (event.target as Element | null)?.closest?.("[data-tip]") ?? null;
    const over = (event: Event) => { const el = target(event); if (el) show(el); else hide(); };
    const focus = (event: Event) => { const el = target(event); if (el) show(el); };
    const click = (event: Event) => { const el = (event.target as Element | null)?.closest?.(".q"); if (el) { event.preventDefault(); show(el); } };
    const key = (event: KeyboardEvent) => { if (event.key === "Escape") hide(); };
    document.addEventListener("mouseover", over); document.addEventListener("focusin", focus); document.addEventListener("focusout", hide);
    document.addEventListener("scroll", hide, true); document.addEventListener("keydown", key); document.addEventListener("click", click);
    return () => {
      document.removeEventListener("mouseover", over); document.removeEventListener("focusin", focus); document.removeEventListener("focusout", hide);
      document.removeEventListener("scroll", hide, true); document.removeEventListener("keydown", key); document.removeEventListener("click", click);
    };
  }, []);
  return <div id="tip" role="tooltip" ref={ref} />;
}

export function Logo({ size = 28 }: { size?: number }) {
  return <svg viewBox="0 0 32 32" width={size} height={size} aria-hidden="true">
    <rect x="3" y="5" width="18" height="6" rx="3" fill="var(--logo2)" /><rect x="7" y="13" width="18" height="6" rx="3" fill="var(--logo1)" /><rect x="11" y="21" width="18" height="6" rx="3" fill="var(--logo2)" />
  </svg>;
}

const sun = <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true"><circle cx="12" cy="12" r="4" /><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" /></svg>;
const moon = <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z" /></svg>;

/** 라이트/다크 전환. 선택은 브라우저에 기억한다(실패해도 동작에는 영향 없음). */
export function ThemeToggle({ theme, onChange }: { theme: "light" | "dark"; onChange: (theme: "light" | "dark") => void }) {
  return <div className="theme" role="group" aria-label="화면 모드">
    <button type="button" aria-pressed={theme === "light"} aria-label="라이트 모드" data-tip="라이트 모드" onClick={() => onChange("light")}>{sun}</button>
    <button type="button" aria-pressed={theme === "dark"} aria-label="다크 모드" data-tip="다크 모드" onClick={() => onChange("dark")}>{moon}</button>
  </div>;
}

/** 표 아래 페이지 이동(클라이언트 페이징). 페이지당 행 수를 고를 수 있다. */
export function Pager({ total, page, size, onPage, onSize, note }: { total: number; page: number; size: number; onPage: (page: number) => void; onSize: (size: number) => void; note?: string }) {
  const pages = Math.max(1, Math.ceil(total / size)); const from = total === 0 ? 0 : page * size + 1; const to = Math.min(total, (page + 1) * size);
  return <div className="pager">
    <span>{from.toLocaleString()}–{to.toLocaleString()} / {total.toLocaleString()}행{note ? ` · ${note}` : ""}</span>
    <span className="ctl"><label>페이지당 <select aria-label="페이지당 행 수" value={size} onChange={e => onSize(Number(e.target.value))}>{[10, 50, 100].map(n => <option key={n}>{n}</option>)}</select>행</label>
      <button type="button" className="btn sm" disabled={page === 0} onClick={() => onPage(page - 1)}>이전</button><span>{page + 1} / {pages}</span>
      <button type="button" className="btn sm" disabled={page >= pages - 1} onClick={() => onPage(page + 1)}>다음</button></span>
  </div>;
}
