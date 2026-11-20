"use client";

import { useCallback, useEffect, useState } from "react";
import { request } from "@/lib/api";
import { Pager } from "./ui";

type Entry = { id: string; kind: "load" | "migrate"; requestId: string; startedAt: string; durationMs: number;
  status: "success" | "replayed" | "failed"; source: string; target: string; rows: number; errorCode: string | null; message: string | null };
const kinds = { load: "파일 → DB 적재", migrate: "DB 이관" };
const statuses = { success: "성공", replayed: "재시도(중복 적재 없음)", failed: "실패" };

/** 서버가 기록한 최근 작업 이력. 비밀번호·호스트·행 데이터는 포함되지 않는다. */
export default function HistoryPage({ refreshKey = 0 }: { refreshKey?: number }) {
  const [items, setItems] = useState<Entry[] | null>(null);
  const [enabled, setEnabled] = useState(true);
  const [error, setError] = useState("");
  const [page, setPage] = useState(0); const [size, setSize] = useState(10);
  const load = useCallback(async () => {
    try { const result = await (await request("/connections/history?limit=200")).json(); setItems(result.items); setEnabled(result.enabled); setError(""); setPage(0); }
    catch (reason) { setError(reason instanceof Error ? reason.message : "작업 이력을 불러오지 못했습니다."); }
  }, []);
  useEffect(() => { void load(); }, [load, refreshKey]);
  const rows = items?.slice(page * size, (page + 1) * size) ?? [];
  return <div className="grid">
    <div className="page-head"><h1>작업 이력</h1><p className="sub">수행한 이관·적재 작업 목록입니다. 비밀번호와 데이터 본문은 기록하지 않습니다.</p></div>
    {error && <div role="alert" className="alert error">{error}</div>}
    {!enabled && <div className="alert warning">서버에서 작업 이력이 비활성화되어 있습니다.</div>}
    <section className="panel" aria-label="작업 이력"><div className="row" style={{ justifyContent: "space-between", marginBottom: 8 }}><h2 style={{ margin: 0, fontSize: 15 }}>최근 작업</h2><button type="button" className="btn sm" onClick={() => void load()}>새로고침</button></div>
      {enabled && items?.length === 0 && <p className="mut">아직 기록된 작업이 없습니다. 이관이나 적재를 실행하면 여기에 남습니다.</p>}
      {!!items?.length && <><div className="table-scroll"><table><thead><tr><th>일시</th><th>작업</th><th>원본 → 대상</th><th style={{ textAlign: "right" }}>행 수</th><th>결과</th><th style={{ textAlign: "right" }}>소요</th></tr></thead><tbody>
        {rows.map(item => <tr key={item.id}><td>{new Date(item.startedAt).toLocaleString("ko-KR")}</td><td>{kinds[item.kind] ?? item.kind}</td><td>{item.source} → {item.target}</td><td className="num">{item.rows.toLocaleString("ko-KR")}</td>
          <td className="wrap" title={item.message ?? ""}><span className={`pill ${item.status === "failed" ? "bad" : "ok"}`}>{statuses[item.status] ?? item.status}</span>{item.errorCode ? <small>{item.errorCode}</small> : null}{item.message ? <small>{item.message}</small> : null}</td>
          <td className="num">{item.durationMs.toLocaleString("ko-KR")}ms</td></tr>)}</tbody></table></div>
        <Pager total={items.length} page={page} size={size} onPage={setPage} onSize={s => { setSize(s); setPage(0); }} /></>}
      <p className="mut" style={{ marginTop: 12 }}>이 서버에 최근 작업만 보관합니다. 실패한 이관은 DB 이관 화면에서 같은 설정으로 실행하면 완료된 분량을 건너뛰고 이어서 진행합니다(같은 화면 세션 안에서만).</p>
    </section>
  </div>;
}
