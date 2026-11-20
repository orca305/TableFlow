"use client";

import { useEffect, useMemo, useState } from "react";
import { request, remove } from "@/lib/api";
import { suggestMapping } from "@/lib/workspace";
import JobHistory from "@/components/JobHistory";
import type { Table, DataType } from "@/lib/types";

type Dbms = "oracle" | "postgresql" | "mysql" | "mariadb";
type Connection = {dbms: Dbms; host: string; port: number; database: string; username: string; password: string; tls: boolean};
type DbColumn = {name: string; type: string; jdbcType?: number; size: number; scale: number; nullable: boolean; primaryKey: boolean; autoIncrement: boolean};
type Endpoint = {connection: Connection; connected: boolean; schemas: string[]; tables: string[]; columns: DbColumn[]; schema: string; table: string};
type Plan = {sourceDbms: string; targetDbms: string; source: string; target: string; sourceRows: number; rows: number;
  columns: {source: string; target: string; sourceType: string; targetType: string; conversion: string}[]; notes: string[];
  createsTable: boolean; ddl: string[]; warnings: string[]; bulk: boolean; chunks: number; chunkSize: number};
type JobStatus = {id: string; state: "queued" | "running" | "succeeded" | "failed" | "cancelled"; totalRows: number; doneRows: number; totalChunks: number; doneChunks: number;
  replayedChunks: number; errorCode: string | null; message: string | null; notes: string[]};
const ports: Record<Dbms, number> = {oracle: 1521, postgresql: 5432, mysql: 3306, mariadb: 3306};
const blank = (dbms: Dbms): Endpoint => ({connection: {dbms, host: "localhost", port: ports[dbms], database: "", username: "", password: "", tls: true}, connected: false, schemas: [], tables: [], columns: [], schema: "", table: ""});

/** 새 테이블 컬럼 이름 기본값: DBMS 관례(PostgreSQL 소문자, Oracle 대문자)에 맞추고 영문/숫자/_만 남긴다. */
function newName(dbms: Dbms, name: string): string {
  let clean = name.normalize("NFKD").replace(/[^A-Za-z0-9_]/g, "_").replace(/^_+|_+$/g, "") || "COL";
  if (/^[0-9]/.test(clean)) clean = "C_" + clean;
  return dbms === "postgresql" ? clean.toLowerCase() : dbms === "oracle" ? clean.toUpperCase() : clean;
}
function dataType(jdbcType = 12): DataType {
  if ([-6, 5, 4, -5].includes(jdbcType)) return "integer";
  if ([2, 3, 6, 7, 8].includes(jdbcType)) return "decimal";
  if (jdbcType === 91) return "date";
  if (jdbcType === 93) return "datetime";
  if (jdbcType === 16 || jdbcType === -7) return "bool";
  return "string";
}

export default function MigrationPanel() {
  const [source, setSource] = useState<Endpoint>(blank("oracle"));
  const [target, setTarget] = useState<Endpoint>(blank("postgresql"));
  const [enabled, setEnabled] = useState(false);
  const [loadEnabled, setLoadEnabled] = useState(false);
  const [mapping, setMapping] = useState<Record<string, string>>({});
  const [limit, setLimit] = useState(1000);
  const [plan, setPlan] = useState<Plan | null>(null);
  const [confirmed, setConfirmed] = useState(false);
  const [requestId, setRequestId] = useState("");
  const [done, setDone] = useState<{insertedRows: number; replayed: boolean; notes?: string[]} | null>(null);
  const [preserveKeys, setPreserveKeys] = useState(false);
  const [job, setJob] = useState<JobStatus | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [historyKey, setHistoryKey] = useState(0);
  const [createMode, setCreateMode] = useState(false);
  const [newTable, setNewTable] = useState("");
  useEffect(() => {
    request("/connections/drivers").then(r => r.json()).then(result => { setEnabled(result.enabled); setLoadEnabled(result.loadEnabled); })
      .catch(reason => setError(reason instanceof Error ? reason.message : "DB 설정을 읽지 못했습니다."));
  }, []);
  function resetRun() { setPlan(null); setConfirmed(false); setRequestId(""); setDone(null); setJob(null); }
  useEffect(() => {
    if (!job || !(job.state === "queued" || job.state === "running")) return;
    const timer = setTimeout(async () => {
      try {
        const next: JobStatus = await (await request(`/connections/migrate/jobs/${job.id}`)).json(); setJob(next);
        if (next.state === "succeeded") { setDone({insertedRows: next.doneRows, replayed: next.replayedChunks === next.totalChunks, notes: next.notes}); setNotice(`이전 완료: ${next.doneRows.toLocaleString()}행`); setHistoryKey(key => key + 1); }
        else if (next.state === "failed" || next.state === "cancelled") { setError(next.message || "이전 작업이 중단되었습니다."); setHistoryKey(key => key + 1); }
      } catch (reason) { setError(reason instanceof Error ? reason.message : "진행률을 조회하지 못했습니다."); }
    }, 800);
    return () => clearTimeout(timer);
  }, [job]);
  const jobActive = job?.state === "queued" || job?.state === "running";
  const working = busy || jobActive; // 대용량 작업 중에는 입력·실행을 잠근다
  async function run(action: () => Promise<void>) {
    setBusy(true); setError(""); setNotice("");
    try { await action(); } catch (reason) { setError(reason instanceof Error ? reason.message : "요청을 처리하지 못했습니다."); } finally { setBusy(false); setHistoryKey(key => key + 1); }
  }
  const suggestions = useMemo(() => {
    if (!source.columns.length || !target.columns.length) return [];
    const pseudo = {columns: source.columns.map(c => ({id: c.name, name: c.name, target_name: c.name.toUpperCase(), data_type: dataType(c.jdbcType), nullable: true, included: true, max_length: null, precision: null, scale: null}))} as unknown as Table;
    return suggestMapping(pseudo, target.columns.map(c => ({name: c.name, type: c.type, jdbcType: c.jdbcType})));
  }, [source.columns, target.columns]);
  useEffect(() => { // 추천은 미리 채우기만 하고, 점검·동의 단계는 항상 거친다.
    if (createMode) return;
    setMapping(Object.fromEntries(suggestions.filter(item => item.target).map(item => [item.columnId, item.target as string]))); resetRun();
  }, [suggestions, createMode]);
  useEffect(() => { // 새 테이블: 원본 컬럼 이름에서 대상 컬럼 이름을 만든다(수정 가능).
    if (!createMode) return;
    setMapping(Object.fromEntries(source.columns.map(c => [c.name, newName(target.connection.dbms, c.name)]))); resetRun();
  }, [createMode, source.columns, target.connection.dbms]);
  const targetTableName = createMode ? newTable : target.table;
  const mappings = Object.entries(mapping).filter(([, to]) => to).map(([from, to]) => ({source: from, target: to}));
  const body = () => ({source: source.connection, sourceSchema: source.schema, sourceTable: source.table, target: target.connection,
    targetSchema: target.schema, targetTable: targetTableName, mappings, limit, createTable: createMode, preserveKeys});
  const ready = !!source.table && !!targetTableName && mappings.length > 0;
  const duplicateTarget = new Set(mappings.map(item => item.target.toLowerCase())).size !== mappings.length;
  return <div className="db-explorer" aria-label="DB → DB 이전">
    <div className="db-intro"><h3>DB → DB 이전</h3><p>원본 테이블을 읽어 대상 테이블에 INSERT합니다. 대상 테이블은 미리 있어야 하며 생성·수정·삭제는 하지 않습니다. 점검(Dry-run) 후 동의해야 실행되고, 오류 시 전체 롤백합니다.</p></div>
    {!enabled && <div className="alert warning">서버에서 DB 탐색이 비활성화되어 있습니다. TABLEFLOW_JDBC_ENABLED=true 설정이 필요합니다.</div>}
    {enabled && !loadEnabled && <div className="alert warning">실제 적재가 비활성화되어 있습니다. TABLEFLOW_JDBC_LOAD_ENABLED=true 설정 후 백엔드를 재시작하세요.</div>}
    {error && <div role="alert" className="alert error">{error}</div>}
    {notice && <div role="status" className="alert success">{notice}</div>}
    <div className="migration-endpoints">
      <EndpointPicker title="원본" label="원본" ep={source} setEp={setSource} busy={working} enabled={enabled} run={run} onChange={resetRun} />
      <EndpointPicker title="대상" label="대상" ep={target} setEp={setTarget} busy={working} enabled={enabled} run={run} onChange={resetRun} create={{on: createMode, setOn: setCreateMode, name: newTable, setName: setNewTable}} />
    </div>
    {source.columns.length > 0 && (createMode ? !!target.schema : target.columns.length > 0) && <div className="load-confirm">
      <h4>컬럼 매핑</h4>
      <div className="table-scroll"><table className="mapping-table"><thead><tr><th>원본 컬럼</th><th>원본 타입</th><th>대상 컬럼</th><th>추천 근거</th></tr></thead><tbody>
        {source.columns.map(column => { const suggestion = suggestions.find(item => item.columnId === column.name);
          return <tr key={column.name}><td>{column.name}</td><td>{column.type}</td>
            <td>{createMode
              ? <input aria-label={`${column.name} 대상 컬럼`} disabled={working} maxLength={64} placeholder="이전하지 않음(비움)" value={mapping[column.name] ?? ""} onChange={event => {setMapping(current => ({...current, [column.name]: event.target.value})); resetRun();}} />
              : <select aria-label={`${column.name} 대상 컬럼`} disabled={working} value={mapping[column.name] ?? ""} onChange={event => {setMapping(current => ({...current, [column.name]: event.target.value})); resetRun();}}>
              <option value="">이전하지 않음</option>{target.columns.map(item => <option key={item.name} value={item.name}>{item.name} ({item.type})</option>)}</select>}</td>
            <td>{createMode ? "새 컬럼(원본 정의에서 생성)" : suggestion?.target ? ({exact: "이름 일치", alias: "업무 용어 매핑", similar: "유사한 이름", none: "—"}[suggestion.reason]) + (suggestion.note ? ` · ${suggestion.note}` : "") : "미확정 · 직접 선택"}</td></tr>; })}
      </tbody></table></div>
      {duplicateTarget && <div className="alert error" role="alert">같은 대상 컬럼에 여러 원본 컬럼이 매핑되어 있습니다.</div>}
      <p>추천은 확정이 아닙니다. 대상의 필수(NOT NULL) 컬럼은 반드시 매핑하세요. 자동 증가·생성 컬럼은 이전할 수 없습니다.</p>
      <div className="db-fields"><label>최대 이전 행 수<input type="number" min={1} max={1000000} aria-label="최대 이전 행 수" disabled={working} value={limit} onChange={event => {setLimit(Math.min(1000000, Math.max(1, Number(event.target.value) || 1))); resetRun();}} /></label></div>
      <p>원본이 이 값보다 많으면 일부만 이전하지 않고 거부합니다. 10,000행 이하는 한 번의 트랜잭션으로, <strong>10,000행을 넘기려면 한도를 더 크게(최대 1,000,000행)</strong> 올리세요. 그러면 청크마다 별도 트랜잭션으로 백그라운드에서 실행하며 진행률을 볼 수 있고, 실패해도 같은 요청으로 이어서 진행할 수 있습니다.</p>
      <label><input type="checkbox" aria-label="자동 증가 컬럼에 원본 키 값 보존" checked={preserveKeys} disabled={working} onChange={event => {setPreserveKeys(event.target.checked); resetRun();}} /> 자동 증가(IDENTITY/AUTO_INCREMENT) 대상 컬럼에 원본 키 값 보존 (적재 후 시퀀스를 최댓값 다음으로 재동기화)</label>
      <button className="secondary" disabled={working || !ready || duplicateTarget || !loadEnabled} onClick={() => void run(async () => {
        resetRun(); const result: Plan = await (await request("/connections/migrate/check", body())).json(); setPlan(result); setRequestId(crypto.randomUUID());
        setNotice("점검에 성공했습니다. 아직 아무것도 쓰지 않았습니다.");
      })}>점검 (Dry-run)</button>
      {plan && <div>
        <p><strong>{plan.sourceDbms} · {plan.source} → {plan.targetDbms} · {plan.target}</strong><br />원본 {plan.sourceRows.toLocaleString()}행 중 {plan.rows.toLocaleString()}행을 이전합니다.{plan.bulk ? ` (대용량: ${plan.chunks}개 청크 × 최대 ${plan.chunkSize.toLocaleString()}행)` : ""}</p>
        {plan.notes.length > 0 && <div className="alert success" role="status"><ul>{plan.notes.map(item => <li key={item}>{item}</li>)}</ul></div>}
        <div className="table-scroll"><table className="mapping-table"><thead><tr><th>원본</th><th>대상</th><th>타입</th><th>변환</th></tr></thead><tbody>{plan.columns.map(item => <tr key={item.source}><td>{item.source}</td><td>{item.target}</td><td>{item.sourceType} → {item.targetType}</td><td>{item.conversion || "—"}</td></tr>)}</tbody></table></div>
        {plan.warnings.length > 0 && <div className="alert warning" role="status"><strong>확인 필요</strong><ul>{plan.warnings.map(item => <li key={item}>{item}</li>)}</ul></div>}
        {plan.createsTable && <div className="alert warning" role="status"><strong>실행하면 아래 테이블이 새로 생성됩니다(CREATE만 실행, 기존 테이블은 변경하지 않음)</strong>
          {plan.ddl.map(statement => <pre key={statement} className="ddl-preview">{statement}</pre>)}
          <small>MySQL·MariaDB·Oracle의 CREATE는 롤백되지 않습니다. 적재가 실패하면 빈 테이블이 남을 수 있으며 같은 요청을 다시 실행하면 재생성 없이 적재만 합니다.</small></div>}
        <label><input type="checkbox" checked={confirmed} disabled={working || !!done} onChange={event => setConfirmed(event.target.checked)} /> {plan.createsTable ? "표시된 CREATE TABLE 실행과 " : "원본·대상과 행 수를 확인했으며 실제 "}DB INSERT에 동의합니다</label>
        <button className="primary" disabled={working || !confirmed || !!done} onClick={() => void run(async () => {
          if (plan.bulk) { setJob(await (await request("/connections/migrate/jobs", {...body(), requestId, confirmed: true})).json()); return; }
          const result = await (await request("/connections/migrate", {...body(), requestId, confirmed: true})).json();
          setDone(result); setNotice(`이전 완료: ${result.insertedRows}행${result.replayed ? " · 이미 처리된 요청입니다. 추가 적재하지 않았습니다." : ""}`);
        })}>{plan.bulk && job && (job.state === "failed" || job.state === "cancelled") ? "이어서 실행 (같은 요청)" : "선택한 대상에 이전 실행"}</button>
        {plan.bulk && job && <div role="status" aria-label="이전 진행률">
          <progress max={Math.max(1, job.totalRows)} value={job.doneRows} style={{width: "100%"}} />
          <p>{job.state === "succeeded" ? "완료" : job.state === "failed" ? "실패" : job.state === "cancelled" ? "취소됨" : job.state === "queued" ? "대기 중" : "진행 중"} · {job.doneRows.toLocaleString()} / {job.totalRows.toLocaleString()}행 · 청크 {job.doneChunks} / {job.totalChunks}{job.replayedChunks > 0 ? ` (이미 완료되어 건너뜀 ${job.replayedChunks})` : ""}</p>
          {jobActive && <button className="secondary" type="button" onClick={() => void run(async () => { setJob(await (await remove(`/connections/migrate/jobs/${job.id}`)).json()); })}>취소 (다음 청크 전에 멈춤)</button>}
        </div>}
        {done?.notes && done.notes.length > 0 && <div className="alert success" role="status"><ul>{done.notes.map(item => <li key={item}>{item}</li>)}</ul></div>}
        <small>요청 ID: {requestId} · 통신 오류 시 같은 요청으로 재시도하면 중복 적재하지 않습니다.</small>
      </div>}
    </div>}
    <JobHistory refreshKey={historyKey} />
  </div>;
}

function EndpointPicker({title, label, ep, setEp, busy, enabled, run, onChange, create}: {title: string; label: string; ep: Endpoint; setEp: (next: Endpoint) => void;
  busy: boolean; enabled: boolean; run: (action: () => Promise<void>) => Promise<void>; onChange: () => void;
  create?: {on: boolean; setOn: (on: boolean) => void; name: string; setName: (name: string) => void}}) {
  const c = ep.connection;
  const edit = (patch: Partial<Connection>) => {setEp({...blank(c.dbms), connection: {...c, ...patch}}); onChange();};
  return <fieldset className="db-fields migration-endpoint" aria-label={`${label} DB`} disabled={busy}>
    <legend>{title} DB</legend>
    <label>DBMS<select aria-label={`${label} DBMS`} value={c.dbms} onChange={event => {const dbms = event.target.value as Dbms; setEp({...blank(dbms), connection: {...c, dbms, port: ports[dbms], password: ""}}); onChange();}}>{(Object.keys(ports) as Dbms[]).map(name => <option key={name}>{name}</option>)}</select></label>
    <label>호스트<input aria-label={`${label} 호스트`} value={c.host} onChange={event => edit({host: event.target.value})} /></label>
    <label>포트<input aria-label={`${label} 포트`} type="number" value={c.port} onChange={event => edit({port: Number(event.target.value)})} /></label>
    <label>{c.dbms === "oracle" ? "서비스명" : "데이터베이스명"}<input aria-label={`${label} 데이터베이스명`} value={c.database} onChange={event => edit({database: event.target.value})} /></label>
    <label>사용자<input aria-label={`${label} 사용자`} autoComplete="off" value={c.username} onChange={event => edit({username: event.target.value})} /></label>
    <label>비밀번호<input aria-label={`${label} 비밀번호`} type="password" autoComplete="new-password" value={c.password} onChange={event => edit({password: event.target.value})} /></label>
    <label><input type="checkbox" checked={c.tls} onChange={event => edit({tls: event.target.checked})} /> 인증서·호스트 검증 TLS 사용</label>
    <button type="button" className="secondary" disabled={busy || !enabled || !c.database || !c.username} onClick={() => void run(async () => {
      await request("/connections/test", c); const schemas = await (await request("/connections/schemas", c)).json();
      setEp({...ep, connected: true, schemas: schemas.items, tables: [], columns: [], schema: "", table: ""}); onChange();
    })}>{label} 연결·스키마 조회</button>
    {ep.connected && <>
      <label>스키마 / 카탈로그<select aria-label={`${label} 스키마`} value={ep.schema} onChange={event => { const schema = event.target.value; setEp({...ep, schema, table: "", tables: [], columns: []}); onChange();
        if (schema) void run(async () => { const tables = await (await request("/connections/tables", {connection: c, schema})).json(); setEp({...ep, schema, table: "", tables: tables.items, columns: []}); }); }}>
        <option value="">선택해주세요</option>{ep.schemas.map(name => <option key={name}>{name}</option>)}</select></label>
      {create && ep.schema && <label><input type="checkbox" checked={create.on} onChange={event => {create.setOn(event.target.checked); onChange();}} /> 새 테이블 만들기 (없으면 생성)</label>}
      {create?.on ? <label>새 테이블 이름<input aria-label={`${label} 새 테이블 이름`} maxLength={64} value={create.name} onChange={event => {create.setName(event.target.value); onChange();}} /></label> :
      <label>테이블<select aria-label={`${label} 테이블`} value={ep.table} disabled={!ep.tables.length} onChange={event => { const table = event.target.value; setEp({...ep, table, columns: []}); onChange();
        if (table) void run(async () => { const columns = await (await request("/connections/columns", {connection: c, schema: ep.schema, table})).json(); setEp({...ep, table, columns: columns.items}); }); }}>
        <option value="">선택해주세요</option>{ep.tables.map(name => <option key={name}>{name}</option>)}</select></label>}
      {ep.columns.length > 0 && <small>{ep.columns.length}개 컬럼 · PK: {ep.columns.filter(item => item.primaryKey).map(item => item.name).join(", ") || "없음"}</small>}
    </>}
  </fieldset>;
}
