"use client";

import { useEffect, useRef, useState } from "react";
import { request } from "@/lib/api";
import type { DbColumn } from "@/lib/connections";
import type { Verified } from "./ConnectionPicker";
import { Help } from "./ui";

export type Picked = { schema: string; table: string; columns: DbColumn[] };
type Props = {
  role: "원본" | "대상"; verified: Verified | null; disabled?: boolean; showTable?: boolean; tableHelp: string;
  onSchema?: (schema: string) => void; onPick?: (picked: Picked | null) => void;
  onTables?: (tables: string[]) => void; forceTable?: { name: string; n: number } | null; // 바깥에서 테이블을 골라 달라고 요청(이관 순서·부모 테이블 이동)
};

/** 연결 확인이 끝난 DB의 스키마와 테이블을 고르고, 선택한 테이블의 컬럼을 조회해 알려준다. */
export default function TablePicker({ role, verified, disabled = false, showTable = true, tableHelp, onSchema, onPick, onTables, forceTable }: Props) {
  const [schemas, setSchemas] = useState<string[]>([]);
  const [tables, setTables] = useState<string[]>([]);
  const [schema, setSchema] = useState(""); const [table, setTable] = useState("");
  const [error, setError] = useState(""); const [busy, setBusy] = useState(false);
  const token = useRef(0);

  useEffect(() => { // 연결이 바뀌면 목록을 다시 불러온다.
    setSchemas([]); setTables([]); setSchema(""); setTable(""); setError(""); onPick?.(null); onSchema?.("");
    if (!verified) return;
    const mine = ++token.current; setBusy(true);
    request("/connections/schemas", verified.connection).then(r => r.json()).then(result => {
      if (mine !== token.current) return; setSchemas(result.items);
      if (!result.items.length) setError("조회 가능한 스키마가 없습니다. 계정 권한을 확인하세요.");
    }).catch(reason => { if (mine === token.current) setError(reason instanceof Error ? reason.message : "스키마를 조회하지 못했습니다."); })
      .finally(() => { if (mine === token.current) setBusy(false); });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [verified]);

  function pickSchema(next: string) { setSchema(next); setTable(""); setTables([]); setError(""); onPick?.(null); onSchema?.(next); }
  useEffect(() => { // 스키마를 고르거나 "기존 테이블" 모드로 바뀌면 테이블 목록을 불러온다.
    if (!verified || !schema || !showTable) { setTables([]); return; }
    const mine = ++token.current; setBusy(true);
    request("/connections/tables", { connection: verified.connection, schema }).then(r => r.json()).then(result => {
      if (mine !== token.current) return; setTables(result.items); onTables?.(result.items); if (!result.items.length) setError("조회 가능한 테이블이 없습니다.");
    }).catch(reason => { if (mine === token.current) setError(reason instanceof Error ? reason.message : "테이블을 조회하지 못했습니다."); })
      .finally(() => { if (mine === token.current) setBusy(false); });
  }, [verified, schema, showTable]);
  useEffect(() => {
    if (!forceTable || !tables.length) return;
    const hit = tables.find(t => t.toLowerCase() === forceTable.name.toLowerCase());
    if (hit) void pickTable(hit);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [forceTable, tables]);
  async function pickTable(next: string) {
    setTable(next); setError(""); onPick?.(null);
    if (!next || !verified) return;
    setBusy(true);
    try { const result = await (await request("/connections/columns", { connection: verified.connection, schema, table: next })).json(); onPick?.({ schema, table: next, columns: result.items }); }
    catch (reason) { setError(reason instanceof Error ? reason.message : "컬럼을 조회하지 못했습니다."); } finally { setBusy(false); }
  }
  const locked = disabled || busy || !verified;
  return <div className="grid">
    <div className="form">
      <label className="f">스키마<Help text={role === "원본" ? "테이블이 속한 DB 내 구역(소유자)입니다. 예: HR, SALES" : "테이블을 생성하거나 적재할 DB 내 구역(소유자)입니다."} />
        <select aria-label={`${role} 스키마`} value={schema} disabled={locked || !schemas.length} onChange={e => pickSchema(e.target.value)}>
          <option value="">{verified ? "선택해주세요" : "연결 확인 후 선택"}</option>{schemas.map(name => <option key={name}>{name}</option>)}</select></label>
      {showTable && <label className="f">테이블<Help text={tableHelp} />
        <select aria-label={`${role} 테이블`} value={table} disabled={locked || !tables.length} onChange={e => void pickTable(e.target.value)}>
          <option value="">{schema ? "선택해주세요" : "스키마를 먼저 선택"}</option>{tables.map(name => <option key={name}>{name}</option>)}</select></label>}
    </div>
    {error && <div role="alert" className="alert error">{error}</div>}
  </div>;
}
