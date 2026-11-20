"use client";

import { useCallback, useEffect, useState } from "react";
import { request } from "@/lib/api";

type Entry = {id: string; kind: "load" | "migrate"; requestId: string; startedAt: string; durationMs: number;
  status: "success" | "replayed" | "failed"; source: string; target: string; rows: number; errorCode: string | null; message: string | null};
const kinds = {load: "파일 → DB 적재", migrate: "DB → DB 이전"};
const statuses = {success: "성공", replayed: "재시도(중복 적재 없음)", failed: "실패"};

/** 서버가 기록한 최근 적재·이전 이력. 비밀번호·호스트·행 데이터는 포함되지 않습니다. */
export default function JobHistory({refreshKey = 0}: {refreshKey?: number}) {
  const [items, setItems] = useState<Entry[] | null>(null);
  const [enabled, setEnabled] = useState(true);
  const [error, setError] = useState("");
  const load = useCallback(async () => {
    try {
      const result = await (await request("/connections/history?limit=20")).json();
      setItems(result.items); setEnabled(result.enabled); setError("");
    } catch (reason) { setError(reason instanceof Error ? reason.message : "작업 이력을 불러오지 못했습니다."); }
  }, []);
  useEffect(() => { void load(); }, [load, refreshKey]);
  return <section className="job-history" aria-label="작업 이력">
    <div className="section-title"><h3>작업 이력</h3><button type="button" className="secondary" onClick={() => void load()}>새로고침</button></div>
    {error && <div role="alert" className="alert error">{error}</div>}
    {!enabled && <p>서버에서 작업 이력이 비활성화되어 있습니다.</p>}
    {enabled && items?.length === 0 && <p>아직 기록된 작업이 없습니다. 적재·이전을 실행하면 여기에 남습니다.</p>}
    {!!items?.length && <div className="table-scroll"><table className="mapping-table"><thead><tr><th>시각</th><th>작업</th><th>원본 → 대상</th><th>행</th><th>결과</th><th>소요</th></tr></thead><tbody>
      {items.map(item => <tr key={item.id}><td>{new Date(item.startedAt).toLocaleString("ko-KR")}</td><td>{kinds[item.kind] ?? item.kind}</td>
        <td>{item.source} → {item.target}</td><td>{item.rows.toLocaleString()}</td>
        <td title={item.message ?? ""}>{statuses[item.status] ?? item.status}{item.errorCode ? ` · ${item.errorCode}` : ""}{item.message ? <small className="issue-solution">{item.message}</small> : null}</td>
        <td>{item.durationMs.toLocaleString()}ms</td></tr>)}
    </tbody></table></div>}
    <small>이 서버에 최근 작업만 보관합니다. 비밀번호·호스트·데이터 값은 저장하지 않습니다.</small>
  </section>;
}
