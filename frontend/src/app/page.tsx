"use client";

import { useCallback, useEffect, useState } from "react";
import BatchPage from "@/components/BatchPage";
import FilePage from "@/components/FilePage";
import HistoryPage from "@/components/HistoryPage";
import MigratePage from "@/components/MigratePage";
import SchedulePage from "@/components/SchedulePage";
import SettingsPage from "@/components/SettingsPage";
import { Logo, ThemeToggle, TipLayer } from "@/components/ui";
import { demoMode } from "@/lib/api";
import { useSavedConnections, useServerInfo } from "@/lib/connections";

const pages = [
  { id: "migrate", label: "DB 이관", tip: "원본 DB의 데이터를 대상 DB로 이관합니다." },
  { id: "batch", label: "일괄 이관", tip: "여러 테이블 또는 스키마 전체를 한 번에 이관합니다. 외래 키 순서는 자동으로 정해집니다." },
  { id: "file", label: "파일 변환", tip: "CSV, Excel, PDF, 이미지를 표로 변환해 SQL, Excel, CSV로 출력하거나 DB에 적재합니다." },
  { id: "schedule", label: "스케줄", tip: "저장해 둔 이관 설정을 정해진 시각이나 간격으로 자동 실행합니다." },
  { id: "history", label: "작업 이력", tip: "수행한 작업을 조회합니다." },
  { id: "settings", label: "설정", tip: "서버 기능 상태, 분할 이관 단위, 저장된 연결을 관리합니다." },
] as const;
type PageId = typeof pages[number]["id"];

export default function Home() {
  const [page, setPage] = useState<PageId>("migrate");
  const [theme, setTheme] = useState<"light" | "dark">("light");
  const [historyKey, setHistoryKey] = useState(0);
  const { info, error, reload: reloadServer } = useServerInfo();
  const { items: saved, reload: reloadSaved } = useSavedConnections();
  const onHistoryChanged = useCallback(() => setHistoryKey(key => key + 1), []);

  useEffect(() => { // 주소의 #이름으로 메뉴를 열고, 저장된 화면 모드를 읽는다.
    const hash = window.location.hash.slice(1); if (pages.some(p => p.id === hash)) setPage(hash as PageId);
    setTheme(document.documentElement.getAttribute("data-theme") === "dark" ? "dark" : "light");
  }, []);
  function go(next: PageId) { setPage(next); try { history.replaceState(null, "", `#${next}`); } catch { /* 주소 변경이 불가능한 환경 */ } if (next === "settings") { void reloadServer(); reloadSaved(); } }
  function changeTheme(next: "light" | "dark") {
    setTheme(next); document.documentElement.setAttribute("data-theme", next);
    try { localStorage.setItem("tf-theme", next); } catch { /* 저장소를 못 쓰면 이번 방문에만 적용 */ }
  }

  return <>
    <header className="topbar"><div className="topbar-inner gut">
      <a href="#migrate" className="logo" onClick={() => go("migrate")}><Logo />TableFlow</a>
      <nav className="nav" aria-label="메뉴">{pages.map(p => <button key={p.id} type="button" aria-current={page === p.id ? "page" : undefined} data-tip={p.tip} onClick={() => go(p.id)}>{p.label}</button>)}</nav>
      <ThemeToggle theme={theme} onChange={changeTheme} />
    </div></header>
    {demoMode && <div className="gut"><div className="alert warning" role="status" style={{ margin: "12px auto 0", maxWidth: 1200 }}><b>포트폴리오 소개용 화면입니다 — 실제로는 동작하지 않습니다.</b> 이 배포에는 백엔드(Spring Boot) 서버가 없어 파일 변환·DB 이관 등 모든 기능이 실패합니다. 화면 구성만 둘러보세요. 기능과 검증 내용, 직접 실행하는 방법은 <a href="https://github.com/orca305/TableFlow" target="_blank" rel="noreferrer">GitHub 저장소(README)</a>에 있습니다.</div></div>}
    <main className="page gut">
      {/* 화면을 바꿔도 입력 중인 내용이 사라지지 않도록 모두 마운트해 두고 숨긴다. */}
      <div hidden={page !== "migrate"}><MigratePage server={info} saved={saved} reloadSaved={reloadSaved} onHistoryChanged={onHistoryChanged} /></div>
      <div hidden={page !== "batch"}><BatchPage server={info} saved={saved} reloadSaved={reloadSaved} onHistoryChanged={onHistoryChanged} /></div>
      <div hidden={page !== "file"}><FilePage server={info} saved={saved} reloadSaved={reloadSaved} onHistoryChanged={onHistoryChanged} /></div>
      <div hidden={page !== "schedule"}><SchedulePage visible={page === "schedule"} /></div>
      <div hidden={page !== "history"}><HistoryPage refreshKey={historyKey} /></div>
      <div hidden={page !== "settings"}><SettingsPage server={info} error={error} saved={saved} reloadSaved={reloadSaved} /></div>
    </main>
    <TipLayer />
  </>;
}
