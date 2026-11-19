"use client";

import { useEffect, useState } from "react";
import type { Table } from "@/lib/types";
import { request } from "@/lib/api";

type Dbms = "oracle" | "postgresql" | "mysql" | "mariadb";
type Connection = {dbms: Dbms; host: string; port: number; database: string; username: string; password: string; tls: boolean};
type Driver = {dbms: Dbms; defaultPort: number; available: boolean; version: string};
type Server = {product: string; serverVersion: string; driver: string; driverVersion: string};
type Column = {name: string; type: string; size: number; scale: number; nullable: boolean; primaryKey: boolean; autoIncrement: boolean; position: number};
type Items<T> = {items: T[]; truncated: boolean};
const initial: Connection = {dbms:"postgresql", host:"localhost", port:5432, database:"", username:"", password:"", tls:true};
const ports = {oracle:1521, postgresql:5432, mysql:3306, mariadb:3306};

export default function DatabaseExplorer({role = "소스", data = null, canLoad = false, reviewed = false, onBusyChange}: {role?: string; data?: Table | null; canLoad?: boolean; reviewed?: boolean; onBusyChange?: (busy: boolean) => void}) {
  const [connection, setConnection] = useState<Connection>(initial);
  const [drivers, setDrivers] = useState<Driver[]>([]);
  const [loadEnabled, setLoadEnabled] = useState(false);
  const [plan, setPlan] = useState<{dbms:string;schema:string;tableName:string;rows:number;columns:string[]} | null>(null);
  const [confirmed, setConfirmed] = useState(false);
  const [requestId, setRequestId] = useState("");
  const [loaded, setLoaded] = useState(false);
  function resetLoad() {setPlan(null); setConfirmed(false); setRequestId(""); setLoaded(false);}
  useEffect(() => {setPlan(null);setConfirmed(false);setRequestId("");setLoaded(false);},[data]);
  const [enabled, setEnabled] = useState(false);
  const [server, setServer] = useState<Server | null>(null);
  const [schemas, setSchemas] = useState<string[]>([]);
  const [tables, setTables] = useState<string[]>([]);
  const [columns, setColumns] = useState<Column[]>([]);
  const [schema, setSchema] = useState("");
  const [table, setTable] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  useEffect(() => {
    let active = true;
    request("/connections/drivers").then(response => response.json()).then(result => {
      if (active) { setDrivers(result.drivers); setEnabled(result.enabled); setLoadEnabled(result.loadEnabled); }
    }).catch(reason => { if (active) setError(reason instanceof Error ? reason.message : "DB 설정을 읽지 못했습니다."); });
    return () => { active = false; };
  }, []);
  function clearResults() { resetLoad(); setServer(null); setSchemas([]); setTables([]); setColumns([]); setSchema(""); setTable(""); setNotice(""); setError(""); }
  function edit(patch: Partial<Connection>) { setConnection(current => ({...current, ...patch})); clearResults(); }
  async function run(action: () => Promise<void>) {
    setBusy(true); onBusyChange?.(true); setError(""); setNotice("");
    try { await action(); } catch (reason) { setError(reason instanceof Error ? reason.message : "DB 조회에 실패했습니다."); }
    finally { setBusy(false); onBusyChange?.(false); }
  }
  function truncated(value: boolean) { if (value) setNotice("조회 개수 제한에 도달했습니다. 목록은 일부만 표시됩니다."); }
  const driver = drivers.find(item => item.dbms === connection.dbms);
  return <div className="db-explorer" aria-label={`${role} DB 메타데이터 탐색`}>
    <div className="db-intro"><h3>{role} DB 연결 · 스키마 탐색</h3><p>{role === "대상" ? "선택한 DB 테이블에 검증한 데이터를 INSERT합니다. 스키마 검사와 실제 적재 동의 후 실행하세요." : "읽기 전용 메타데이터 조회입니다. 소스 행 추출은 아직 지원하지 않습니다."}</p></div>
    {!enabled && <div className="alert warning">서버에서 DB 탐색이 비활성화되어 있습니다. TABLEFLOW_JDBC_ENABLED=true 설정 후 백엔드를 재시작하세요.</div>}
    <form autoComplete="off" onSubmit={event => {event.preventDefault(); void run(async () => {
      clearResults(); const result: Server = await (await request("/connections/test", connection)).json(); setServer(result);
      setNotice("연결 테스트에 성공했습니다. 스키마 목록을 조회할 수 있습니다.");
    });}}>
      <fieldset disabled={busy} className="db-fields">
        <label>DBMS<select value={connection.dbms} onChange={event => {const dbms = event.target.value as Dbms; edit({dbms, port:ports[dbms], password:""});}}>{Object.keys(ports).map(dbms => <option key={dbms} value={dbms}>{dbms}</option>)}</select></label>
        <label>DB 호스트<input required maxLength={253} value={connection.host} onChange={event => edit({host:event.target.value})} /></label>
        <label>DB 포트<input required type="number" min={1} max={65535} value={connection.port} onChange={event => edit({port:Number(event.target.value)})} /></label>
        <label>{connection.dbms === "oracle" ? "Oracle 서비스명" : "데이터베이스명"}<input required maxLength={128} value={connection.database} onChange={event => edit({database:event.target.value})} /></label>
        <label>DB 사용자<input required maxLength={128} autoComplete="off" value={connection.username} onChange={event => edit({username:event.target.value})} /></label>
        <label>DB 비밀번호<input type="password" maxLength={1024} autoComplete="new-password" value={connection.password} onChange={event => edit({password:event.target.value})} /></label>
      </fieldset>
      <div className="db-actions"><label><input type="checkbox" checked={connection.tls} disabled={busy} onChange={event => edit({tls:event.target.checked})} /> 인증서·호스트 검증 TLS 사용</label><button className="primary" disabled={busy || !enabled || !driver?.available} type="submit">{busy ? "DB 조회 중…" : "연결 테스트"}</button><button className="secondary" type="button" disabled={busy} onClick={() => {setConnection(initial); clearResults();}}>연결 정보 지우기</button></div>
    </form>
    <p className="db-help">비밀번호는 화면 메모리에서만 사용하며 서버·브라우저 저장소에 저장하지 않습니다. 화면을 벗어나면 지워집니다. TLS 해제는 로컬 DB에만 허용합니다. {role === "대상" ? "적재 계정에는 대상·영수증 테이블의 SELECT/INSERT 권한이 필요합니다." : "읽기 전용 계정을 사용하세요."}</p>
    <p className="db-help">드라이버: {driver ? driver.available ? `${driver.dbms} ${driver.version} · 사용 가능 (실서버 호환 검증과 별개)` : "드라이버 없음" : "설정 조회 중"}</p>
    {error && <div role="alert" className="alert error">{error}</div>}
    {notice && <div role="status" className="alert success">{notice}</div>}
    {server && <div className="db-metadata"><p><strong>{server.product} {server.serverVersion}</strong><br />{server.driver} {server.driverVersion}</p>
      <button className="secondary" disabled={busy} onClick={() => void run(async () => {
        resetLoad(); setSchemas([]); setSchema(""); setTables([]); setTable(""); setColumns([]);
        const result: Items<string> = await (await request("/connections/schemas", connection)).json(); setSchemas(result.items); truncated(result.truncated);
        if (!result.items.length) setNotice("조회 가능한 스키마가 없습니다. 계정 권한을 확인하세요.");
      })}>스키마 조회</button>
      <div className="db-fields"><label>스키마 / 카탈로그<select aria-label="스키마 / 카탈로그" disabled={busy || !schemas.length} value={schema} onChange={event => {
        const next = event.target.value; resetLoad(); setSchema(next); setTable(""); setTables([]); setColumns([]);
        if (next) void run(async () => { const result: Items<string> = await (await request("/connections/tables", {connection, schema:next})).json(); setTables(result.items); truncated(result.truncated); if (!result.items.length) setNotice("조회 가능한 테이블이 없습니다."); });
      }}><option value="">선택해주세요</option>{schemas.map(name => <option key={name}>{name}</option>)}</select></label>
      <label>테이블 / 뷰<select aria-label="테이블 / 뷰" disabled={busy || !tables.length} value={table} onChange={event => {
        const next = event.target.value; resetLoad(); setTable(next); setColumns([]);
        if (next) void run(async () => { const result: Items<Column> = await (await request("/connections/columns", {connection, schema, table:next})).json(); setColumns(result.items); truncated(result.truncated); if (!result.items.length) setNotice("조회 가능한 컬럼이 없습니다."); });
      }}><option value="">선택해주세요</option>{tables.map(name => <option key={name}>{name}</option>)}</select></label></div>
      {columns.length > 0 && <div className="table-scroll"><table className="mapping-table"><thead><tr><th>컬럼</th><th>DB 타입</th><th>길이 / 정밀도</th><th>소수 자릿수</th><th>NULL</th><th>키</th></tr></thead><tbody>{columns.map(column => <tr key={column.name}><td>{column.name}</td><td>{column.type}</td><td>{column.size}</td><td>{column.scale}</td><td>{column.nullable ? "허용" : "필수"}</td><td>{[column.primaryKey ? "PK" : "", column.autoIncrement ? "자동 증가" : ""].filter(Boolean).join(" · ") || "—"}</td></tr>)}</tbody></table></div>}
      {role === "대상" && <div className="load-confirm">
        {!loadEnabled && <p>실제 적재는 비활성화되어 있습니다. TABLEFLOW_JDBC_LOAD_ENABLED=true 설정 후 백엔드를 재시작하세요.</p>}
        <a href={`/samples/${connection.dbms}-receipts.sql`} download>영수증 테이블 준비 SQL 다운로드 ↓</a>
        <p>대상 스키마에 tableflow_load_receipts 영수증 테이블이 필요합니다. 테이블 생성·기존 행 수정 없이 전체 성공 또는 전체 롤백합니다. 중복 행은 엄격 검증합니다.</p>
        <button className="secondary" disabled={busy || !loadEnabled || !canLoad || !table || !columns.length} onClick={() => void run(async () => {
          resetLoad(); const result = await (await request("/connections/load/check", {connection, table:data, schema, tableName:table})).json(); setPlan(result); setRequestId(crypto.randomUUID());
        })}>대상·스키마 검사</button>
        {plan && <><p><strong>{plan.dbms} · {plan.schema}.{plan.tableName} · {plan.rows}행</strong><br />{plan.columns.join(", ")}</p>
          <label><input type="checkbox" checked={confirmed} disabled={busy || loaded} onChange={event => setConfirmed(event.target.checked)} /> 이 대상과 행 수를 확인했으며 실제 DB INSERT에 동의합니다</label>
          <button className="primary" disabled={busy || !canLoad || !confirmed || loaded} onClick={() => void run(async () => {
            const result = await (await request("/connections/load", {connection, table:data, schema, tableName:table, requestId, confirmed:true}, false, reviewed)).json();
            setLoaded(true); setNotice(`적재 완료: ${result.insertedRows}행${result.replayed ? " · 이미 처리된 요청입니다. 추가 적재하지 않았습니다." : ""}`);
          })}>선택한 DB에 적재</button><small>요청 ID: {requestId} · 통신 오류 시 같은 요청으로 재시도합니다.</small></>}
      </div>}
    </div>}
  </div>;
}
