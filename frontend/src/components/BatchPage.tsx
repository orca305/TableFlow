"use client";

import { useEffect, useMemo, useState } from "react";
import { remove, request } from "@/lib/api";
import type { SavedConnection, ServerInfo } from "@/lib/connections";
import BatchTableSettings, { settingProblem, tableOptionsOf, type TableSetting } from "./BatchTableSettings";
import ConnectionPicker, { type Verified } from "./ConnectionPicker";
import ScheduleForm from "./ScheduleForm";
import { Help } from "./ui";

type PlanItem = { table: string; target: string; rows: number; columns: number; excluded: string[]; targetExists: boolean; action: "create" | "append" | "skip" | "error"; level: "ok" | "warn" | "error"; messages: string[] };
type Plan = { items: PlanItem[]; cycles: string[]; notes: string[]; totalRows: number; blocked: boolean };
type Item = { table: string; target: string; state: "pending" | "running" | "succeeded" | "skipped" | "failed"; totalRows: number; doneRows: number; message: string | null };
type Job = { id: string; state: "queued" | "running" | "succeeded" | "failed" | "cancelled"; totalRows: number; doneRows: number; totalChunks: number; doneChunks: number; message: string | null; items: Item[] | null; notes: string[] };
type Check = { source: string; target: string; measure: string; sourceValue: string; targetValue: string; match: boolean; note: string };
type VerifyItem = { table: string; target: string; status: "match" | "mismatch" | "missing" | "error"; sourceRows: number; targetRows: number; checked: number; mismatches: Check[]; notes: string[] };
type Verification = { items: VerifyItem[]; matched: number; mismatched: number; failed: number };
const verifyLabel = { match: "일치", mismatch: "불일치", missing: "대상 없음", error: "검증 실패" } as const;
const fmt = (n: number) => n.toLocaleString("ko-KR");
const actionLabel = { create: "새로 생성", append: "추가 적재", skip: "건너뜀", error: "오류" } as const;
const stateLabel = { pending: "대기", running: "진행 중", succeeded: "완료", skipped: "건너뜀", failed: "실패" } as const;
const levelClass = { ok: "ok", warn: "warn", error: "bad" } as const;

/**
 * 여러 테이블(스키마 전체 포함)을 한 번에 이관한다. 대상 테이블은 원본 구조로 새로 만들며, 외래 키의 부모 테이블부터 차례로 처리한다.
 * 실패하면 그 테이블에서 멈추고, 같은 설정으로 재개하면 완료된 테이블은 건너뛴다.
 */
export default function BatchPage({ server, saved, reloadSaved, onHistoryChanged }: { server: ServerInfo | null; saved: SavedConnection[]; reloadSaved: () => void; onHistoryChanged: () => void }) {
  const [srcV, setSrcV] = useState<Verified | null>(null); const [dstV, setDstV] = useState<Verified | null>(null);
  const [schemas, setSchemas] = useState<string[]>([]); const [schema, setSchema] = useState("");
  const [tables, setTables] = useState<string[]>([]); const [picked, setPicked] = useState<Set<string>>(new Set()); const [filter, setFilter] = useState("");
  const [dstSchemas, setDstSchemas] = useState<string[]>([]); const [dstSchema, setDstSchema] = useState("");
  const [keys, setKeys] = useState(false); const [idx, setIdx] = useState(false); const [defs, setDefs] = useState(false); const [fks, setFks] = useState(true); const [dropEmpty, setDropEmpty] = useState(false);
  const [ifExists, setIfExists] = useState<"skip" | "append">("skip"); const [chunkText, setChunkText] = useState("");
  const [plan, setPlan] = useState<Plan | null>(null); const [confirmed, setConfirmed] = useState(false); const [requestId, setRequestId] = useState("");
  const [job, setJob] = useState<Job | null>(null);
  const [settings, setSettings] = useState<Record<string, TableSetting>>({}); const [verify, setVerify] = useState<Verification | null>(null); const [verifying, setVerifying] = useState(false);
  const [busy, setBusy] = useState(false); const [error, setError] = useState("");

  const chunk = Number(chunkText.replace(/\D/g, "")); const chunkValid = chunkText === "" || (chunk >= 100 && chunk <= 10000);
  const active = job?.state === "queued" || job?.state === "running"; const working = busy || active;
  const selected = useMemo(() => tables.filter(t => picked.has(t)), [tables, picked]);
  const shown = useMemo(() => tables.filter(t => t.toLowerCase().includes(filter.trim().toLowerCase())), [tables, filter]);
  const supportsIdentity = server?.drivers.find(d => d.dbms === dstV?.connection.dbms)?.capabilities?.preserveKeys !== false;
  const dbms = dstV?.connection.dbms;
  const problem = dbms ? settingProblem(settings, selected, dbms) : "";
  const body = () => ({ source: srcV!.connection, sourceSchema: schema, target: dstV!.connection, targetSchema: dstSchema, tables: selected, preserveKeys: keys && supportsIdentity, copyIndexes: idx, copyDefaults: defs,
    copyForeignKeys: fks, dropEmptyOnFailure: dropEmpty, ifExists, chunkSize: chunkText === "" ? undefined : chunk, tableOptions: tableOptionsOf(settings, selected, dstV!.connection.dbms) });
  function reset() { setPlan(null); setConfirmed(false); setRequestId(""); setJob(null); setVerify(null); setError(""); }
  async function guarded(action: () => Promise<void>) {
    setBusy(true); setError("");
    try { await action(); } catch (reason) { setError(reason instanceof Error ? reason.message : "요청을 처리하지 못했습니다."); } finally { setBusy(false); onHistoryChanged(); }
  }

  useEffect(() => { // 원본 연결이 확인되면 스키마 목록
    setSchemas([]); setSchema(""); setTables([]); setPicked(new Set()); setSettings({}); reset();
    if (!srcV) return;
    request("/connections/schemas", srcV.connection).then(r => r.json()).then(r => { setSchemas(r.items); if (r.items.length === 1) setSchema(r.items[0]); }).catch(reason => setError(reason instanceof Error ? reason.message : "스키마를 불러오지 못했습니다."));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [srcV]);
  useEffect(() => {
    setTables([]); setPicked(new Set()); setSettings({}); reset();
    if (!srcV || !schema) return;
    request("/connections/tables", { connection: srcV.connection, schema }).then(r => r.json()).then(r => setTables(r.items)).catch(reason => setError(reason instanceof Error ? reason.message : "테이블을 불러오지 못했습니다."));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [srcV, schema]);
  useEffect(() => {
    setDstSchemas([]); setDstSchema(""); reset();
    if (!dstV) return;
    request("/connections/schemas", dstV.connection).then(r => r.json()).then(r => { setDstSchemas(r.items); if (r.items.length === 1) setDstSchema(r.items[0]); }).catch(reason => setError(reason instanceof Error ? reason.message : "스키마를 불러오지 못했습니다."));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dstV]);
  useEffect(() => { // 진행 현황
    if (!job || !active) return;
    const timer = setTimeout(async () => {
      try {
        const next: Job = await (await request(`/connections/migrate/jobs/${job.id}`)).json(); setJob(next);
        if (next.state === "failed" || next.state === "cancelled") { setError(next.message || "이관이 중단되었습니다."); onHistoryChanged(); }
        if (next.state === "succeeded") onHistoryChanged();
      } catch (reason) { setError(reason instanceof Error ? reason.message : "진행 현황을 조회하지 못했습니다."); }
    }, 800);
    return () => clearTimeout(timer);
  }, [job, active, onHistoryChanged]);

  const ready = !!srcV && !!dstV && !!schema && !!dstSchema && selected.length > 0 && chunkValid && !problem;
  const toggle = (t: string, on: boolean) => { setPicked(cur => { const next = new Set(cur); if (on) next.add(t); else next.delete(t); return next; }); reset(); };
  const allShown = shown.length > 0 && shown.every(t => picked.has(t));
  const items: Item[] | null = job?.items ?? null; const failed = job?.state === "failed" || job?.state === "cancelled";
  const loaded = items?.filter(i => i.state === "succeeded").map(i => i.table) ?? [];
  async function runVerify() {
    setVerifying(true); setError(""); setVerify(null);
    try { const all = body(); setVerify(await (await request("/connections/migrate/batch/verify", { ...all, tables: loaded, tableOptions: all.tableOptions?.filter(o => loaded.includes(o.table)) })).json()); }
    catch (reason) { setError(reason instanceof Error ? reason.message : "검증하지 못했습니다."); } finally { setVerifying(false); }
  }
  const start = () => guarded(async () => { setJob(await (await request("/connections/migrate/batch", { ...body(), requestId, confirmed: true })).json()); });

  return <div className="grid">
    <div className="page-head"><h1>일괄 이관</h1><p className="sub">여러 테이블 또는 스키마 전체를 한 번에 이관합니다. 외래 키(FK)의 부모 테이블부터 자동으로 처리합니다.</p></div>
    <details className="guide" open><summary>사용 방법</summary>
      <ol className="gs">
        <li><b><i>1</i>원본 선택</b>원본 DB에 연결하고 스키마와 이관할 테이블을 고릅니다. 스키마 전체를 이관하려면 &apos;전체 선택&apos;을 누르세요.</li>
        <li><b><i>2</i>대상 선택</b>대상 DB와 스키마를 고릅니다. 대상에는 원본과 같은 구조의 테이블이 새로 생성됩니다.</li>
        <li><b><i>3</i>점검·실행</b>&apos;사전 점검&apos;으로 처리 순서와 테이블별 행 수·충돌을 확인한 뒤 이관합니다. 실패하면 그 테이블에서 멈추고 재개할 수 있습니다.</li>
      </ol></details>

    <div role="region" className="panel" aria-label="일괄 이관 원본"><h2>원본 선택<span className="hs">이관할 DB와 테이블을 선택하세요</span></h2>
      <div className="grid">
        <ConnectionPicker role="원본" server={server} saved={saved} onSavedChanged={reloadSaved} disabled={working} onVerified={setSrcV} />
        {srcV && <div className="form">
          <label className="f">스키마<select aria-label="일괄 원본 스키마" disabled={working} value={schema} onChange={e => setSchema(e.target.value)}><option value="">선택하세요</option>{schemas.map(s => <option key={s} value={s}>{s}</option>)}</select></label>
        </div>}
        {schema && <div className="split">
          <div className="sp-h">이관할 테이블<Help text="선택한 테이블만 이관합니다. 외래 키로 서로 참조하는 테이블은 부모가 먼저 이관되도록 순서가 자동으로 정해집니다." /></div>
          <div className="row end">
            <label className="f" style={{ flex: 1 }}>테이블 검색<input aria-label="테이블 검색" value={filter} onChange={e => setFilter(e.target.value)} placeholder="이름 일부" /></label>
            <span className="check"><input id="batch-all" type="checkbox" checked={allShown} disabled={working || shown.length === 0} onChange={e => { setPicked(cur => { const next = new Set(cur); shown.forEach(t => e.target.checked ? next.add(t) : next.delete(t)); return next; }); reset(); }} /><label htmlFor="batch-all">전체 선택</label></span>
            <span className="chip" role="status">{selected.length}개 선택 / 전체 {tables.length}개</span>
          </div>
          <div className="table-scroll" style={{ maxHeight: 260 }} role="group" aria-label="테이블 목록">
            {shown.length === 0 ? <p className="mut">표시할 테이블이 없습니다.</p> : shown.map(t => <div key={t} className="check" style={{ padding: "4px 0" }}><input id={`bt-${t}`} type="checkbox" aria-label={`${t} 선택`} disabled={working} checked={picked.has(t)} onChange={e => toggle(t, e.target.checked)} /><label htmlFor={`bt-${t}`}>{t}</label></div>)}
          </div>
        </div>}
      </div></div>

    <div role="region" className="panel" aria-label="일괄 이관 대상"><h2>대상 선택<span className="hs">데이터를 넣을 DB와 스키마를 선택하세요</span></h2>
      <div className="grid">
        <ConnectionPicker role="대상" server={server} saved={saved} onSavedChanged={reloadSaved} disabled={working} onVerified={setDstV} />
        {dstV && <div className="form">
          <label className="f">스키마<select aria-label="일괄 대상 스키마" disabled={working} value={dstSchema} onChange={e => { setDstSchema(e.target.value); reset(); }}><option value="">선택하세요</option>{dstSchemas.map(s => <option key={s} value={s}>{s}</option>)}</select></label>
        </div>}
        {!supportsIdentity && <p className="mut">기존 자동 증가 컬럼의 원본 값 지정은 지원하지 않습니다. 신규 일반 컬럼으로 생성하는 테이블에는 원본 키 값을 이관할 수 있습니다.</p>}
        <div className="split"><div className="sp-h">이관 옵션<Help text="대상 테이블은 원본 컬럼 구조로 새로 만들며, 이름은 대상 DB 관례(PostgreSQL·CUBRID 소문자, Oracle·Tibero 대문자)에 맞춰 영문·숫자·_로 변환됩니다." /></div>
          <div className="row">
            <span className="check"><input id="b-keys" type="checkbox" checked={keys && supportsIdentity} disabled={working || !supportsIdentity} onChange={e => { setKeys(e.target.checked); reset(); }} /><label htmlFor="b-keys">원본 기본 키(PK) 유지</label></span>
            <span className="check"><input id="b-idx" type="checkbox" checked={idx} disabled={working} onChange={e => { setIdx(e.target.checked); reset(); }} /><label htmlFor="b-idx">인덱스 복제</label></span>
            <span className="check"><input id="b-def" type="checkbox" checked={defs} disabled={working} onChange={e => { setDefs(e.target.checked); reset(); }} /><label htmlFor="b-def">기본값 복제</label></span>
            <span className="check"><input id="b-fk" type="checkbox" checked={fks} disabled={working} onChange={e => { setFks(e.target.checked); reset(); }} /><label htmlFor="b-fk">외래 키(FK) 복제</label></span></div>
          <div className="ol"><input id="b-drop-empty" type="checkbox" checked={dropEmpty} disabled={working} onChange={e => { setDropEmpty(e.target.checked); reset(); }} /><label htmlFor="b-drop-empty">실패하면 새로 만든 빈 테이블 삭제</label><Help text="적재에 실패했을 때, 이번 실행에서 새로 만든 대상 테이블이 비어 있으면 삭제합니다. 원래 있던 테이블과 데이터가 들어간 테이블은 삭제하지 않으며, 중지한 경우에도 삭제하지 않습니다." /></div>
          <div className="row end">
            <fieldset className="radios" style={{ border: 0, padding: 0, margin: 0 }}><legend className="mut">대상에 같은 이름의 테이블이 있으면</legend>
              <div className="row"><span className="ol"><input type="radio" name="b-exists" id="b-skip" checked={ifExists === "skip"} disabled={working} onChange={() => { setIfExists("skip"); reset(); }} /><label htmlFor="b-skip">건너뜀</label></span>
                <span className="ol"><input type="radio" name="b-exists" id="b-append" checked={ifExists === "append"} disabled={working} onChange={() => { setIfExists("append"); reset(); }} /><label htmlFor="b-append">추가 적재</label></span></div></fieldset>
            <label className="f" style={{ width: 200 }}>분할 단위(행)<input aria-label="일괄 분할 단위(행)" inputMode="numeric" placeholder="서버 기본값" disabled={working} value={chunkText} onChange={e => { setChunkText(e.target.value); reset(); }} /></label>
          </div>
          {!chunkValid && <p className="mapsum bad" role="status">분할 단위는 100~10,000행 사이로 입력하세요</p>}
        </div>
      </div></div>

    {schema && srcV && dbms && selected.length > 0 && <div role="region" className="panel" aria-label="일괄 이관 테이블 설정">
      <div className="grid"><BatchTableSettings connection={srcV.connection} schema={schema} tables={selected} dbms={dbms} settings={settings} disabled={working}
        onChange={(table, next) => { setSettings(cur => { const copy = { ...cur }; if (next) copy[table] = next; else delete copy[table]; return copy; }); reset(); }} />
        {problem && <p className="mapsum bad" role="status">{problem}</p>}</div></div>}

    {ready && <div role="region" className="panel" aria-label="일괄 이관 실행"><h2>점검 및 실행</h2>
      <div className="grid">
        <div className="row"><button type="button" className="btn" disabled={working || !server?.loadEnabled} data-tip="아무것도 쓰지 않고 처리 순서, 테이블별 행 수, 이름 충돌, 컬럼 오류를 확인합니다." onClick={() => void guarded(async () => {
          reset(); setPlan(await (await request("/connections/migrate/batch/check", body())).json()); setRequestId(crypto.randomUUID());
        })}>{busy && !plan ? "점검 중…" : "사전 점검"}</button>
          <button type="button" className="btn p" disabled={working || !plan || plan.blocked || !confirmed || (!!job && !failed)} onClick={() => void start()}>{failed ? "재개" : "이관 시작"}</button>
          {active && <button type="button" className="btn" onClick={() => void guarded(async () => { setJob(await (await remove(`/connections/migrate/jobs/${job!.id}`)).json()); })}>중지</button>}
          {plan?.blocked && <span className="pill bad">오류가 있는 테이블이 있어 이관할 수 없습니다. 선택에서 제외하거나 원인을 해결하세요</span>}</div>
        {error && <div className="alert warning" role="alert">{error}</div>}
        {plan && <div className="grid">
          <p><strong>{selected.length}개 테이블 · 이관할 {fmt(plan.totalRows)}행</strong></p>
          {plan.notes.length > 0 && <div className="alert success" role="status"><ul>{plan.notes.map(n => <li key={n}>{n}</li>)}</ul></div>}
          <div className="table-scroll"><table className="fit" aria-label="일괄 이관 점검 결과"><thead><tr><th>순서</th><th>원본 테이블</th><th>대상 테이블</th><th>행 수</th><th>처리</th><th>상태</th></tr></thead><tbody>
            {plan.items.map((i, n) => <tr key={i.table}><td>{n + 1}</td><td>{i.table}</td><td>{i.target}</td><td>{fmt(i.rows)}</td><td>{actionLabel[i.action]}</td>
              <td className="wrap"><span className={`pill ${levelClass[i.level]}`}>{i.level === "ok" ? "정상" : i.level === "warn" ? "주의" : "오류"}</span>{i.messages.map(m => <small key={m}>{m}</small>)}</td></tr>)}</tbody></table></div>
          <div className="check"><input id="batch-confirm" type="checkbox" checked={confirmed} disabled={working || !!job} onChange={e => setConfirmed(e.target.checked)} /><label htmlFor="batch-confirm">표시된 테이블 생성(CREATE TABLE)과 DB INSERT에 동의합니다</label></div>
          <small className="mut">요청 ID: {requestId} · 같은 설정으로 재개하면 완료된 테이블은 다시 이관하지 않습니다.</small>
        </div>}
        {job && <div className="grid" role="status" aria-label="일괄 이관 진행 현황"><h2 style={{ margin: 0, fontSize: 15 }}>진행 현황</h2>
          <div className="prog"><i style={{ width: `${job.totalChunks ? Math.round(job.doneChunks / job.totalChunks * 100) : 0}%` }} /></div>
          <p>{job.state === "succeeded" ? "완료" : job.state === "failed" ? "실패" : job.state === "cancelled" ? "중지됨" : job.state === "queued" ? "대기 중" : "진행 중"} · 테이블 {job.doneChunks} / {job.totalChunks}개 · {fmt(job.doneRows)} / {fmt(job.totalRows)}행</p>
          {items && <div className="table-scroll"><table className="fit" aria-label="테이블별 진행 현황"><thead><tr><th>원본 테이블</th><th>대상 테이블</th><th>상태</th><th>행</th><th>비고</th></tr></thead><tbody>
            {items.map(i => <tr key={i.table}><td>{i.table}</td><td>{i.target}</td><td>{stateLabel[i.state]}</td><td>{fmt(i.doneRows)} / {fmt(i.totalRows)}</td><td className="wrap">{i.message ?? ""}</td></tr>)}</tbody></table></div>}
          {job.state === "succeeded" && job.notes.length > 0 && <div className="alert success"><ul>{job.notes.map(n => <li key={n}>{n}</li>)}</ul></div>}</div>}
        {job && !active && loaded.length > 0 && <div className="grid" role="group" aria-label="일괄 이관 검증">
          <div className="row"><button type="button" className="btn" disabled={verifying || working} data-tip="이관한 테이블마다 원본(행 조건 적용)과 대상의 행 수와 합계·최소·최대·문자열 길이를 비교합니다. 아무것도 쓰지 않습니다." onClick={() => void runVerify()}>{verifying ? "검증 중…" : `이관 결과 검증 (${loaded.length}개 테이블)`}</button>
            {verify && <span className={`pill ${verify.mismatched + verify.failed === 0 ? "ok" : "bad"}`} role="status">일치 {verify.matched} · 불일치 {verify.mismatched} · 실패 {verify.failed}</span>}</div>
          {verify && <div className="table-scroll"><table className="fit" aria-label="일괄 이관 검증 결과"><thead><tr><th>원본 테이블</th><th>대상 테이블</th><th>원본 행</th><th>대상 행</th><th>결과</th><th>상세</th></tr></thead><tbody>
            {verify.items.map(v => <tr key={v.table}><td>{v.table}</td><td>{v.target}</td><td>{fmt(v.sourceRows)}</td><td>{fmt(v.targetRows)}</td>
              <td><span className={`pill ${v.status === "match" ? "ok" : "bad"}`}>{verifyLabel[v.status]}</span></td>
              <td className="wrap">{v.mismatches.map(c => <small key={`${c.source}-${c.measure}`}>{c.source} {c.measure}: 원본 {c.sourceValue} / 대상 {c.targetValue}</small>)}{v.notes.map(n => <small key={n}>{n}</small>)}</td></tr>)}</tbody></table></div>}
        </div>}
        {plan && !plan.blocked && <ScheduleForm kind="batch" build={() => body()} canSchedule={!!srcV?.connection.savedId && !!dstV?.connection.savedId} defaultName={`${schema} 일괄 이관 (${selected.length}개)`} disabled={working} />}
      </div></div>}
  </div>;
}
