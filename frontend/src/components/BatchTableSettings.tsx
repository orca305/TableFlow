"use client";

import { Fragment, useState } from "react";
import { request } from "@/lib/api";
import { newColumnName } from "@/lib/names";
import type { Dbms } from "@/lib/connections";
import TransformEditor, { stepReady, summarize, type TransformStep } from "./TransformEditor";
import { Help } from "./ui";

export type SettingColumn = { name: string; type: string; jdbcType?: number; primaryKey?: boolean };
/** 한 테이블의 설정. 값을 바꾸지 않은 항목은 비워 둔다(서버의 자동 매핑을 그대로 쓴다). */
export type TableSetting = { where: string; columns: SettingColumn[]; excluded: Record<string, boolean>; targets: Record<string, string>; rules: Record<string, TransformStep[]> };

const UNSUPPORTED = new Set([-2, -3, -4, 2004, 2003, 2002, 2000, 1111, 2009, 2013]);
const usable = (s: TableSetting) => s.columns.filter(c => !UNSUPPORTED.has(c.jdbcType ?? 12));
const mappingChanged = (s: TableSetting) => Object.values(s.excluded).some(Boolean) || Object.keys(s.targets).length > 0 || Object.values(s.rules).some(r => r.length > 0);

export const isConfigured = (s: TableSetting | undefined) => !!s && (!!s.where.trim() || mappingChanged(s));

function mappingsOf(s: TableSetting, dbms: Dbms) {
  return usable(s).filter(c => !s.excluded[c.name]).map(c => ({ source: c.name, target: (s.targets[c.name] ?? newColumnName(dbms, c.name)).trim(), ...(s.rules[c.name]?.length ? { steps: s.rules[c.name] } : {}) }));
}
/** 요청에 넣을 테이블별 설정. 선택한 테이블 중 설정이 있는 것만 보낸다. */
export function tableOptionsOf(settings: Record<string, TableSetting>, tables: string[], dbms: Dbms) {
  const list = tables.filter(t => isConfigured(settings[t])).map(t => { const s = settings[t]; return { table: t, where: s.where.trim() || undefined, mappings: mappingChanged(s) ? mappingsOf(s, dbms) : undefined }; });
  return list.length ? list : undefined;
}
/** 보내면 서버가 거부할 미완성 설정의 설명(없으면 빈 문자열). */
export function settingProblem(settings: Record<string, TableSetting>, tables: string[], dbms: Dbms): string {
  for (const t of tables) {
    const s = settings[t]; if (!s || !mappingChanged(s)) continue;
    const mappings = mappingsOf(s, dbms);
    if (!mappings.length) return `${t}: 이관할 컬럼을 하나 이상 선택하세요`;
    if (mappings.some(m => !m.target)) return `${t}: 대상 컬럼 이름을 입력하세요`;
    if (new Set(mappings.map(m => m.target.toLowerCase())).size !== mappings.length) return `${t}: 대상 컬럼 이름이 중복됩니다`;
    if (mappings.some(m => m.steps?.some(step => !stepReady(step)))) return `${t}: 입력이 끝나지 않은 변환 규칙이 있습니다`;
  }
  return "";
}

export default function BatchTableSettings({ connection, schema, tables, dbms, settings, disabled, onChange }: {
  connection: object; schema: string; tables: string[]; dbms: Dbms; settings: Record<string, TableSetting>; disabled: boolean; onChange: (table: string, next: TableSetting | null) => void;
}) {
  const [open, setOpen] = useState(""); const [openRule, setOpenRule] = useState(""); const [loading, setLoading] = useState(false); const [error, setError] = useState("");
  const current = open && tables.includes(open) ? settings[open] : undefined;
  const configured = tables.filter(t => isConfigured(settings[t]));

  async function choose(table: string) {
    setOpen(table); setOpenRule(""); setError("");
    if (!table || settings[table]) return;
    setLoading(true);
    try {
      const result = await (await request("/connections/columns", { connection, schema, table })).json();
      onChange(table, { where: "", columns: result.items, excluded: {}, targets: {}, rules: {} });
    } catch (reason) { setError(reason instanceof Error ? reason.message : "컬럼을 불러오지 못했습니다."); } finally { setLoading(false); }
  }
  const patch = (next: Partial<TableSetting>) => { if (current) onChange(open, { ...current, ...next }); };

  return <details className="guide"><summary>테이블별 설정 (선택){configured.length > 0 && ` · ${configured.length}개 테이블에 적용`}</summary>
    <div className="grid" role="group" aria-label="테이블별 설정">
      <div className="row end">
        <label className="f" style={{ flex: 1 }}>설정할 테이블<select aria-label="설정할 테이블" disabled={disabled} value={tables.includes(open) ? open : ""} onChange={e => void choose(e.target.value)}>
          <option value="">선택하세요</option>{tables.map(t => <option key={t} value={t}>{t}{isConfigured(settings[t]) ? " (설정됨)" : ""}</option>)}</select></label>
        <Help text="설정하지 않은 테이블은 전체 컬럼과 전체 행을 그대로 이관합니다. 테이블마다 행 조건, 제외할 컬럼, 대상 컬럼 이름, 값 변환 규칙을 지정할 수 있습니다." />
        {current && isConfigured(current) && <button type="button" className="btn sm" disabled={disabled} onClick={() => { onChange(open, null); setOpen(""); }}>이 테이블 설정 지우기</button>}
      </div>
      {loading && <p className="mut">컬럼을 불러오는 중…</p>}
      {error && <div className="alert warning" role="alert">{error}</div>}
      {current && <>
        <label className="f">행 조건(선택)<input aria-label={`${open} 행 조건`} maxLength={1000} disabled={disabled} placeholder="예: STATUS = 'A' AND AMT > 100" value={current.where} onChange={e => patch({ where: e.target.value })} /></label>
        <div className="table-scroll mapping-table" style={{ maxHeight: 360 }}><table aria-label={`${open} 컬럼 설정`}><thead><tr><th>이관</th><th>원본 컬럼</th><th>대상 컬럼 이름</th><th>변환 규칙</th></tr></thead><tbody>
          {usable(current).map(c => { const off = !!current.excluded[c.name]; const steps = current.rules[c.name] ?? [];
            return <Fragment key={c.name}><tr>
              <td><input type="checkbox" aria-label={`${open} ${c.name} 이관`} disabled={disabled} checked={!off} onChange={e => patch({ excluded: { ...current.excluded, [c.name]: !e.target.checked } })} /></td>
              <td>{c.name}{c.primaryKey && <> <span className="pill">PK</span></>}<small>{c.type}</small></td>
              <td><input aria-label={`${open} ${c.name} 대상 컬럼`} maxLength={64} disabled={disabled || off} value={current.targets[c.name] ?? newColumnName(dbms, c.name)}
                onChange={e => { const targets = { ...current.targets }; if (e.target.value === newColumnName(dbms, c.name)) delete targets[c.name]; else targets[c.name] = e.target.value; patch({ targets }); }} /></td>
              <td className="wrap"><button type="button" className="btn sm" aria-expanded={openRule === c.name} aria-label={`${open} ${c.name} 변환 규칙`} disabled={off} onClick={() => setOpenRule(openRule === c.name ? "" : c.name)}>{steps.length ? `규칙 ${steps.length}개` : "설정"}</button>{steps.length > 0 && <small>{summarize(steps)}</small>}</td></tr>
              {openRule === c.name && !off && <tr><td colSpan={4}><TransformEditor column={c.name} columns={usable(current).map(x => x.name)} steps={steps} disabled={disabled}
                onChange={next => { const rules = { ...current.rules }; if (next.length) rules[c.name] = next; else delete rules[c.name]; patch({ rules }); }} /></td></tr>}
            </Fragment>; })}
        </tbody></table></div>
      </>}
    </div></details>;
}
