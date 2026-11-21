"use client";

import { useCallback, useEffect, useState } from "react";
import { remove, request } from "@/lib/api";
import { Help, Pager } from "./ui";

type Entry = { id: string; kind: "load" | "migrate"; requestId: string; startedAt: string; durationMs: number;
  status: "success" | "replayed" | "failed"; source: string; target: string; rows: number; errorCode: string | null; message: string | null };
type JobStatus = { id: string; state: "queued" | "running" | "succeeded" | "failed" | "cancelled"; totalRows: number; doneRows: number; totalChunks: number; doneChunks: number;
  replayedChunks: number; errorCode: string | null; message: string | null; startedAt: string; finishedAt: string | null };
type JobInfo = { status: JobStatus; source: string; target: string; resumable: boolean; sourceNeedsPassword: boolean; targetNeedsPassword: boolean };
const kinds = { load: "파일 → DB 적재", migrate: "DB 이관" };
const statuses = { success: "성공", replayed: "재시도(중복 적재 없음)", failed: "실패" };
const stateLabel: Record<JobStatus["state"], string> = { queued: "대기 중", running: "진행 중", succeeded: "완료", failed: "중단·실패", cancelled: "중지됨" };
const fmt = (n: number) => n.toLocaleString("ko-KR");

/** 서버가 기록한 이관 작업 목록(서버를 다시 시작해도 유지)과 최근 작업 이력. 비밀번호·호스트·행 데이터는 포함되지 않는다. */
export default function HistoryPage({ refreshKey = 0 }: { refreshKey?: number }) {
  const [items, setItems] = useState<Entry[] | null>(null);
  const [jobs, setJobs] = useState<JobInfo[]>([]);
  const [enabled, setEnabled] = useState(true);
  const [error, setError] = useState(""); const [notice, setNotice] = useState("");
  const [page, setPage] = useState(0); const [size, setSize] = useState(10);
  const [resuming, setResuming] = useState<string | null>(null);
  const [sourcePw, setSourcePw] = useState(""); const [targetPw, setTargetPw] = useState("");
  const [busy, setBusy] = useState(false);
  const load = useCallback(async () => {
    try {
      const result = await (await request("/connections/history?limit=200")).json(); setItems(result.items); setEnabled(result.enabled); setPage(0);
      const j = await (await request("/connections/migrate/jobs")).json(); setJobs(j.items); setError("");
    } catch (reason) { setError(reason instanceof Error ? reason.message : "작업 이력을 불러오지 못했습니다."); }
  }, []);
  useEffect(() => { void load(); }, [load, refreshKey]);
  const active = jobs.some(j => j.status.state === "queued" || j.status.state === "running");
  useEffect(() => { // 진행 중인 이관이 있으면 목록을 주기적으로 갱신한다.
    if (!active) return;
    const timer = setInterval(() => { request("/connections/migrate/jobs").then(r => r.json()).then(j => setJobs(j.items)).catch(() => undefined); }, 1500);
    return () => clearInterval(timer);
  }, [active]);

  async function resume(info: JobInfo) {
    setBusy(true); setError(""); setNotice("");
    try {
      await request(`/connections/migrate/jobs/${info.status.id}/resume`, { sourcePassword: info.sourceNeedsPassword ? sourcePw : undefined, targetPassword: info.targetNeedsPassword ? targetPw : undefined });
      setResuming(null); setSourcePw(""); setTargetPw(""); setNotice("이관을 재개했습니다. 완료된 분량은 건너뛰고 이어서 진행합니다."); await load();
    } catch (reason) { setError(reason instanceof Error ? reason.message : "재개하지 못했습니다."); } finally { setBusy(false); }
  }
  async function stop(id: string) {
    try { await remove(`/connections/migrate/jobs/${id}`); await load(); } catch (reason) { setError(reason instanceof Error ? reason.message : "중지하지 못했습니다."); }
  }
  const rows = items?.slice(page * size, (page + 1) * size) ?? [];
  return <div className="grid">
    <div className="page-head"><h1>작업 이력</h1><p className="sub">수행한 이관·적재 작업 목록입니다. 비밀번호와 데이터 본문은 기록하지 않습니다.</p></div>
    {error && <div role="alert" className="alert error">{error}</div>}
    {notice && <div role="status" className="alert success">{notice}</div>}
    {!enabled && <div className="alert warning">서버에서 작업 이력이 비활성화되어 있습니다.</div>}

    <section className="panel" aria-label="이관 작업"><h2>이관 작업<Help text="대용량 이관은 분할 단위로 저장되어, 중단되거나 서버가 다시 시작돼도 '재개'로 완료된 분량을 건너뛰고 이어서 진행할 수 있습니다. 저장된 비밀번호가 없는 연결은 재개할 때 비밀번호를 입력합니다." /><span className="hs">서버를 다시 시작해도 목록이 유지됩니다</span></h2>
      {jobs.length === 0 ? <p className="mut">아직 이관 작업이 없습니다. 10,000행을 넘는 이관을 실행하면 여기에 남습니다.</p> :
        <div className="table-scroll"><table><thead><tr><th>시작</th><th>원본 → 대상</th><th>진행</th><th>상태</th><th></th></tr></thead><tbody>
          {jobs.map(info => { const st = info.status; const pct = st.totalRows ? Math.round(st.doneRows / st.totalRows * 100) : 0;
            return <tr key={st.id}><td>{new Date(st.startedAt).toLocaleString("ko-KR")}</td><td>{info.source} → {info.target}</td>
              <td className="wrap"><div className="prog" style={{ minWidth: 120 }}><i style={{ width: `${pct}%` }} /></div><small>{fmt(st.doneRows)} / {fmt(st.totalRows)}행 · {st.doneChunks} / {st.totalChunks}회</small></td>
              <td className="wrap"><span className={`pill ${st.state === "succeeded" ? "ok" : st.state === "failed" ? "bad" : st.state === "cancelled" ? "warn" : "run"}`}>{stateLabel[st.state]}</span>{st.message && <small>{st.message}</small>}</td>
              <td className="wrap">{(st.state === "queued" || st.state === "running") && <button type="button" className="btn sm" onClick={() => void stop(st.id)} data-tip="다음 분할 단위를 시작하기 전에 멈춥니다. 완료된 분량은 유지됩니다.">중지</button>}
                {info.resumable && resuming !== st.id && <button type="button" className="btn sm p" onClick={() => { setResuming(st.id); setSourcePw(""); setTargetPw(""); }} data-tip="같은 설정으로 이어서 이관합니다. 완료된 분량은 건너뜁니다.">재개</button>}
                {info.resumable && resuming === st.id && <div className="grid" style={{ minWidth: 240 }}>
                  {info.sourceNeedsPassword && <label className="f">원본 DB 비밀번호<input type="password" aria-label="원본 DB 비밀번호" autoComplete="new-password" value={sourcePw} onChange={e => setSourcePw(e.target.value)} /></label>}
                  {info.targetNeedsPassword && <label className="f">대상 DB 비밀번호<input type="password" aria-label="대상 DB 비밀번호" autoComplete="new-password" value={targetPw} onChange={e => setTargetPw(e.target.value)} /></label>}
                  {!info.sourceNeedsPassword && !info.targetNeedsPassword && <span className="mut">저장된 비밀번호로 연결합니다.</span>}
                  <div className="row"><button type="button" className="btn sm p" disabled={busy || (info.sourceNeedsPassword && !sourcePw) || (info.targetNeedsPassword && !targetPw)} onClick={() => void resume(info)}>재개 시작</button>
                    <button type="button" className="btn sm" onClick={() => setResuming(null)}>취소</button></div></div>}</td></tr>; })}</tbody></table></div>}
    </section>

    <section className="panel" aria-label="작업 이력"><div className="row" style={{ justifyContent: "space-between", marginBottom: 8 }}><h2 style={{ margin: 0, fontSize: 15 }}>최근 작업</h2><button type="button" className="btn sm" onClick={() => void load()}>새로고침</button></div>
      {enabled && items?.length === 0 && <p className="mut">아직 기록된 작업이 없습니다. 이관이나 적재를 실행하면 여기에 남습니다.</p>}
      {!!items?.length && <><div className="table-scroll"><table><thead><tr><th>일시</th><th>작업</th><th>원본 → 대상</th><th style={{ textAlign: "right" }}>행 수</th><th>결과</th><th style={{ textAlign: "right" }}>소요</th></tr></thead><tbody>
        {rows.map(item => <tr key={item.id}><td>{new Date(item.startedAt).toLocaleString("ko-KR")}</td><td>{kinds[item.kind] ?? item.kind}</td><td>{item.source} → {item.target}</td><td className="num">{item.rows.toLocaleString("ko-KR")}</td>
          <td className="wrap" title={item.message ?? ""}><span className={`pill ${item.status === "failed" ? "bad" : "ok"}`}>{statuses[item.status] ?? item.status}</span>{item.errorCode ? <small>{item.errorCode}</small> : null}{item.message ? <small>{item.message}</small> : null}</td>
          <td className="num">{item.durationMs.toLocaleString("ko-KR")}ms</td></tr>)}</tbody></table></div>
        <Pager total={items.length} page={page} size={size} onPage={setPage} onSize={s => { setSize(s); setPage(0); }} /></>}
      <p className="mut" style={{ marginTop: 12 }}>이 서버에 최근 작업만 보관합니다. 비밀번호·호스트·데이터 값은 저장하지 않습니다.</p>
    </section>
  </div>;
}
