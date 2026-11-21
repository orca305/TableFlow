"use client";

import { useState } from "react";
import { request } from "@/lib/api";
import { Help } from "./ui";

/** 지금 설정한 이관을 주기적으로 반복 실행하도록 등록한다. 비밀번호를 저장한 연결만 쓸 수 있다. */
export default function ScheduleForm({ kind, build, canSchedule, defaultName, disabled }: { kind: "single" | "batch"; build: () => object; canSchedule: boolean; defaultName: string; disabled: boolean }) {
  const [name, setName] = useState(""); const [mode, setMode] = useState<"interval" | "daily">("daily");
  const [minutes, setMinutes] = useState("60"); const [time, setTime] = useState("03:00");
  const [refresh, setRefresh] = useState<"append" | "replace">("replace"); const [confirmReplace, setConfirmReplace] = useState(false);
  const [busy, setBusy] = useState(false); const [error, setError] = useState(""); const [done, setDone] = useState("");
  const id = kind === "batch" ? "sb" : "ss";

  async function submit() {
    setBusy(true); setError(""); setDone("");
    try {
      const created = await (await request("/schedules", { name: name.trim() || defaultName, kind, request: build(), mode,
        intervalMinutes: mode === "interval" ? Number(minutes.replace(/\D/g, "")) : undefined, dailyAt: mode === "daily" ? time : undefined, refresh, confirmReplace })).json();
      setDone(`스케줄을 등록했습니다. 다음 실행: ${new Date(created.nextRunAt).toLocaleString("ko-KR")}. 관리는 '스케줄' 메뉴에서 합니다.`);
    } catch (reason) { setError(reason instanceof Error ? reason.message : "스케줄을 등록하지 못했습니다."); } finally { setBusy(false); }
  }

  return <details className="guide"><summary>스케줄로 등록 (주기적으로 반복 실행)</summary>
    <div className="grid" role="group" aria-label="스케줄 등록">
      {!canSchedule && <div className="alert warning" role="status">스케줄은 비밀번호를 저장한 연결만 사용할 수 있습니다. 원본과 대상 연결에서 &apos;비밀번호 저장&apos;을 선택해 저장한 뒤 다시 연결 확인을 하세요.</div>}
      <div className="row end">
        <label className="f" style={{ flex: 1 }}>스케줄 이름<input aria-label={`${id} 스케줄 이름`} maxLength={60} placeholder={defaultName} value={name} onChange={e => setName(e.target.value)} /></label>
        <label className="f" style={{ width: 160 }}>반복 방식<select aria-label={`${id} 반복 방식`} value={mode} onChange={e => setMode(e.target.value as "interval" | "daily")}><option value="daily">매일 정해진 시각</option><option value="interval">일정 간격</option></select></label>
        {mode === "daily" ? <label className="f" style={{ width: 140 }}>실행 시각<input aria-label={`${id} 실행 시각`} type="time" value={time} onChange={e => setTime(e.target.value)} /></label>
          : <label className="f" style={{ width: 140 }}>간격(분)<input aria-label={`${id} 간격(분)`} inputMode="numeric" value={minutes} onChange={e => setMinutes(e.target.value)} /></label>}
      </div>
      <fieldset style={{ border: 0, padding: 0, margin: 0 }}><legend className="mut">실행할 때마다 대상 테이블을<Help text="추가 적재는 기존 행을 그대로 두고 이어 붙입니다(기본 키가 겹치면 실패). 비우고 다시 적재는 실행 전에 대상 테이블의 모든 행을 삭제한 뒤 원본 전체를 다시 넣으므로 원본과 항상 같은 상태가 됩니다." /></legend>
        <div className="row"><span className="ol"><input type="radio" name={`${id}-refresh`} id={`${id}-replace`} checked={refresh === "replace"} onChange={() => setRefresh("replace")} /><label htmlFor={`${id}-replace`}>비우고 다시 적재 (권장)</label></span>
          <span className="ol"><input type="radio" name={`${id}-refresh`} id={`${id}-append`} checked={refresh === "append"} onChange={() => setRefresh("append")} /><label htmlFor={`${id}-append`}>추가 적재</label></span></div></fieldset>
      {refresh === "replace" && <div className="check"><input id={`${id}-confirm`} type="checkbox" checked={confirmReplace} onChange={e => setConfirmReplace(e.target.checked)} /><label htmlFor={`${id}-confirm`}>실행할 때마다 대상 테이블의 기존 행을 모두 삭제하는 데 동의합니다</label></div>}
      <div className="row"><button type="button" className="btn p" disabled={disabled || busy || !canSchedule || (refresh === "replace" && !confirmReplace)} onClick={() => void submit()}>{busy ? "등록 중…" : "스케줄 등록"}</button></div>
      {error && <div className="alert warning" role="alert">{error}</div>}
      {done && <div className="alert success" role="status">{done}</div>}
    </div></details>;
}
