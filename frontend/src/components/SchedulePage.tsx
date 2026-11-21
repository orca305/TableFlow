"use client";

import { useCallback, useEffect, useState } from "react";
import { remove, request } from "@/lib/api";
import { Help } from "./ui";

type Schedule = { id: string; name: string; kind: "single" | "batch"; mode: "interval" | "daily"; intervalMinutes: number | null; dailyAt: string | null; refresh: "append" | "replace";
  enabled: boolean; lastRunAt: string | null; lastState: string | null; lastMessage: string | null; nextRunAt: string | null; source: string; target: string };
const when = (iso: string | null) => (iso ? new Date(iso).toLocaleString("ko-KR") : "-");
const stateLabel: Record<string, string> = { running: "실행 중", succeeded: "성공", failed: "실패", cancelled: "중지됨" };

/** 등록한 반복 실행(스케줄) 목록. 지금 실행·사용/중지·삭제를 할 수 있고 마지막 결과를 보여준다. 이관 결과는 '작업 이력'에도 남는다. */
export default function SchedulePage({ visible }: { visible: boolean }) {
  const [items, setItems] = useState<Schedule[] | null>(null); const [enabled, setEnabled] = useState(true);
  const [error, setError] = useState(""); const [notice, setNotice] = useState(""); const [busy, setBusy] = useState(false);
  const load = useCallback(async () => {
    try { const r = await (await request("/schedules")).json(); setItems(r.items); setEnabled(r.enabled); setError(""); }
    catch (reason) { setError(reason instanceof Error ? reason.message : "스케줄을 불러오지 못했습니다."); }
  }, []);
  useEffect(() => { if (visible) void load(); }, [visible, load]);
  const running = items?.some(s => s.lastState === "running") ?? false;
  useEffect(() => { // 화면이 보이는 동안 주기적으로 갱신한다(실행 중이면 더 자주)
    if (!visible) return;
    const timer = setInterval(() => void load(), running ? 2000 : 10000);
    return () => clearInterval(timer);
  }, [visible, running, load]);
  async function act(work: () => Promise<void>) {
    setBusy(true); setError(""); setNotice("");
    try { await work(); await load(); } catch (reason) { setError(reason instanceof Error ? reason.message : "요청을 처리하지 못했습니다."); } finally { setBusy(false); }
  }
  const repeat = (s: Schedule) => (s.mode === "daily" ? `매일 ${s.dailyAt}` : `${s.intervalMinutes}분마다`);

  return <div className="grid">
    <div className="page-head"><h1>스케줄</h1><p className="sub">저장해 둔 이관 설정을 정해진 시각이나 간격으로 자동 실행합니다. 서버가 켜져 있는 동안에만 실행됩니다.</p></div>
    {!enabled && <div className="alert warning" role="status">서버에서 스케줄 기능이 꺼져 있습니다.</div>}
    {error && <div className="alert warning" role="alert">{error}</div>}
    {notice && <div className="alert success" role="status">{notice}</div>}
    <div role="region" className="panel" aria-label="스케줄 목록"><h2>등록된 스케줄<Help text="스케줄은 'DB 이관' 또는 '일괄 이관' 화면의 '스케줄로 등록'에서 만듭니다. 비밀번호를 저장한 연결만 사용할 수 있으며, 비밀번호는 스케줄에 저장되지 않습니다." /></h2>
      {items === null ? <p className="mut">불러오는 중…</p> : items.length === 0 ? <p className="mut">등록된 스케줄이 없습니다. &apos;DB 이관&apos; 또는 &apos;일괄 이관&apos; 화면에서 이관을 설정한 뒤 &apos;스케줄로 등록&apos;을 사용하세요.</p>
        : <div className="table-scroll"><table className="fit" aria-label="스케줄 표"><thead><tr><th>이름</th><th>대상</th><th>반복</th><th>처리 방식</th><th>다음 실행</th><th>마지막 실행</th><th>작업</th></tr></thead><tbody>
          {items.map(s => <tr key={s.id}>
            <td className="wrap"><b>{s.name}</b><small>{s.kind === "batch" ? "일괄 이관" : "DB 이관"}{s.enabled ? "" : " · 중지됨"}</small></td>
            <td className="wrap"><small>{s.source}</small><small>→ {s.target}</small></td>
            <td>{repeat(s)}</td><td>{s.refresh === "replace" ? "비우고 다시 적재" : "추가 적재"}</td>
            <td>{s.enabled ? when(s.nextRunAt) : "-"}</td>
            <td className="wrap">{s.lastState ? <span className={`pill ${s.lastState === "succeeded" ? "ok" : s.lastState === "running" ? "" : "bad"}`}>{stateLabel[s.lastState] ?? s.lastState}</span> : <span className="mut">아직 없음</span>}
              <small>{when(s.lastRunAt)}</small>{s.lastMessage && <small>{s.lastMessage}</small>}</td>
            <td><div className="row">
              <button type="button" className="btn sm" disabled={busy || s.lastState === "running"} aria-label={`${s.name} 지금 실행`} onClick={() => void act(async () => { await request(`/schedules/${s.id}/run`, {}); setNotice(`'${s.name}'을(를) 실행했습니다.`); })}>지금 실행</button>
              <button type="button" className="btn sm" disabled={busy} aria-label={`${s.name} ${s.enabled ? "중지" : "사용"}`} onClick={() => void act(async () => { await request(`/schedules/${s.id}/enabled`, { enabled: !s.enabled }); })}>{s.enabled ? "중지" : "사용"}</button>
              <button type="button" className="btn sm" disabled={busy} aria-label={`${s.name} 삭제`} onClick={() => { if (window.confirm(`'${s.name}' 스케줄을 삭제할까요? 이미 이관된 데이터는 그대로 유지됩니다.`)) void act(async () => { await remove(`/schedules/${s.id}`); }); }}>삭제</button></div></td>
          </tr>)}</tbody></table></div>}
    </div>
  </div>;
}
