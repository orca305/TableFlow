"use client";

import { Fragment, useEffect, useMemo, useState } from "react";
import { remove, request } from "@/lib/api";
import { suggestMapping } from "@/lib/workspace";
import type { DataType, Table } from "@/lib/types";
import { DBMS_LABEL, type Dbms, type DbColumn, type SavedConnection, type ServerInfo } from "@/lib/connections";
import ConnectionPicker, { type Verified } from "./ConnectionPicker";
import TablePicker, { type Picked } from "./TablePicker";
import { Help, Pager } from "./ui";
import ScheduleForm from "./ScheduleForm";
import TransformEditor, { stepReady, summarize, type TransformStep } from "./TransformEditor";

type Level = "ok" | "info" | "warn" | "error";
type ColumnPlan = { source: string; target: string; sourceType: string; targetType: string; conversion: string; level: Level; detail: string };
type FkPlan = { name: string; columns: string[]; parentTable: string; parentInTarget: boolean; copied: boolean; message: string };
type Analysis = { sourceRows: number; maxRows: number; columns: ColumnPlan[]; notes: string[]; warnings: string[]; createsTable: boolean; ddl: string[]; foreignKeys: FkPlan[] };
type Plan = { sourceDbms: string; targetDbms: string; source: string; target: string; sourceRows: number; rows: number; columns: ColumnPlan[]; notes: string[];
  createsTable: boolean; ddl: string[]; warnings: string[]; bulk: boolean; chunks: number; chunkSize: number };
type JobStatus = { id: string; state: "queued" | "running" | "succeeded" | "failed" | "cancelled"; totalRows: number; doneRows: number; totalChunks: number; doneChunks: number;
  replayedChunks: number; errorCode: string | null; message: string | null; notes: string[] };
type OrderItem = { table: string; level: number; parents: string[]; externalParents: string[] };
type OrderPlan = { order: OrderItem[]; cycles: string[] };
type Step = "src" | "dst" | "map";
type Override = { type?: DataType; size?: number; scale?: number }; // 신규 테이블 생성 시 직접 지정한 대상 타입·길이
const TYPES: [DataType, string][] = [["string", "문자열"], ["integer", "정수"], ["decimal", "소수"], ["date", "날짜"], ["datetime", "일시"], ["bool", "불리언"]];

const UNSUPPORTED = new Set([-2, -3, -4, 2004, 2003, 2002, 2000, 1111, 2009, 2013]); // 이진·배열·구조체 등은 이관할 수 없다
const SYNC_LIMIT = 10000;
const levelLabel: Record<Level, string> = { ok: "호환", info: "자동 변환", warn: "주의", error: "오류" };
const levelClass: Record<Level, string> = { ok: "ok", info: "", warn: "warn", error: "bad" };

function dataType(jdbcType = 12): DataType {
  if ([-6, 5, 4, -5].includes(jdbcType)) return "integer";
  if ([2, 3, 6, 7, 8].includes(jdbcType)) return "decimal";
  if (jdbcType === 91) return "date";
  if (jdbcType === 93) return "datetime";
  if (jdbcType === 16 || jdbcType === -7) return "bool";
  return "string";
}
/** 신규 테이블 컬럼 이름 기본값: DBMS 관례(PostgreSQL 소문자, Oracle 대문자)에 맞추고 영문/숫자/_만 남긴다. */
function newColumnName(dbms: Dbms, name: string): string {
  let clean = name.normalize("NFKD").replace(/[^A-Za-z0-9_]/g, "_").replace(/^_+|_+$/g, "") || "COL";
  if (/^[0-9]/.test(clean)) clean = "C_" + clean;
  return dbms === "postgresql" ? clean.toLowerCase() : dbms === "oracle" ? clean.toUpperCase() : clean;
}
const fmt = (n: number) => n.toLocaleString("ko-KR");
const SIZED = new Set([1, 12, -9, -15, 2, 3]); // 길이·정밀도를 보여줄 문자/숫자 타입(날짜 등은 DB가 돌려주는 크기가 의미 없다)
const typeLabel = (c: DbColumn) => `${c.type}${SIZED.has(c.jdbcType ?? 12) && c.size ? `(${c.size}${c.scale ? `,${c.scale}` : ""})` : ""}`;

export default function MigratePage({ server, saved, reloadSaved, onHistoryChanged }: { server: ServerInfo | null; saved: SavedConnection[]; reloadSaved: () => void; onHistoryChanged: () => void }) {
  const [active, setActive] = useState<Step>("src");
  const [srcV, setSrcV] = useState<Verified | null>(null); const [dstV, setDstV] = useState<Verified | null>(null);
  const [src, setSrc] = useState<Picked | null>(null); const [srcRows, setSrcRows] = useState<number | null>(null); const [srcOk, setSrcOk] = useState(false);
  const [dstSchema, setDstSchema] = useState(""); const [dst, setDst] = useState<Picked | null>(null); const [dstOk, setDstOk] = useState(false);
  const [mode, setMode] = useState<"new" | "existing">("new"); const [newName, setNewName] = useState("");
  const [preserveKeys, setPreserveKeys] = useState(false);
  const [where, setWhere] = useState(""); const [whereRows, setWhereRows] = useState<number | null>(null); const [whereError, setWhereError] = useState(""); const [whereBusy, setWhereBusy] = useState(false);
  const [chunkText, setChunkText] = useState(""); const [mapping, setMapping] = useState<Record<string, string>>({});
  const [ov, setOv] = useState<Record<string, Override>>({});
  const [rules, setRules] = useState<Record<string, TransformStep[]>>({}); const [openRule, setOpenRule] = useState("");
  const [srcTables, setSrcTables] = useState<string[]>([]); const [srcSchemaSel, setSrcSchemaSel] = useState("");
  const [force, setForce] = useState<{ name: string; n: number } | null>(null); const [orderPlan, setOrderPlan] = useState<OrderPlan | null>(null);
  const [copyIdx, setCopyIdx] = useState(false); const [copyDef, setCopyDef] = useState(false); const [copyFk, setCopyFk] = useState(false);
  const [analysis, setAnalysis] = useState<Analysis | null>(null); const [analysisError, setAnalysisError] = useState("");
  const [preview, setPreview] = useState<Table | null>(null); const [previewError, setPreviewError] = useState("");
  const [pPage, setPPage] = useState(0); const [pSize, setPSize] = useState(10);
  const [plan, setPlan] = useState<Plan | null>(null); const [confirmed, setConfirmed] = useState(false); const [requestId, setRequestId] = useState("");
  const [done, setDone] = useState<{ insertedRows: number; replayed: boolean; notes?: string[] } | null>(null);
  const [job, setJob] = useState<JobStatus | null>(null);
  type Verification = { sourceRows: number; targetRows: number; rowsMatch: boolean; allMatch: boolean; notes: string[]; checks: { source: string; target: string; measure: string; sourceValue: string; targetValue: string; match: boolean; note: string }[] };
  const [verify, setVerify] = useState<Verification | null>(null); const [verifying, setVerifying] = useState(false); const [verifyError, setVerifyError] = useState("");
  const [busy, setBusy] = useState(false); const [error, setError] = useState(""); const [notice, setNotice] = useState("");

  useEffect(() => { // 분할 단위 기본값: 이 브라우저의 설정 → 서버 기본값 순
    if (chunkText || !server) return;
    let value = server.defaultChunkSize; try { const s = Number(localStorage.getItem("tf-chunk")); if (s >= 100 && s <= 10000) value = s; } catch { /* 저장소를 못 쓰면 서버 기본값 */ }
    setChunkText(String(value));
  }, [server, chunkText]);

  const targetTable = mode === "new" ? newName.trim() : dst?.table ?? "";
  const targetDbms = dstV?.connection.dbms ?? "postgresql";
  const mappings = useMemo(() => Object.entries(mapping).filter(([, to]) => to).map(([from, to]) => ({ source: from, target: to, ...(mode === "new" ? ov[from] : {}), ...(rules[from]?.length ? { steps: rules[from] } : {}) })), [mapping, ov, mode, rules]);
  const duplicated = useMemo(() => { const seen = new Set<string>(); const dup = new Set<string>(); mappings.forEach(m => { const k = m.target.toLowerCase(); if (seen.has(k)) dup.add(k); seen.add(k); }); return dup; }, [mappings]);
  const filtered = where.trim() !== "";
  const total = analysis?.sourceRows ?? (filtered ? whereRows : srcRows) ?? 0;
  const chunk = Number(chunkText.replace(/\D/g, ""));
  const chunkValid = chunk >= 100 && chunk <= 10000;
  const bulk = total > SYNC_LIMIT;
  const chunks = bulk && chunkValid ? Math.ceil(total / chunk) : 1;
  const incompleteRules = Object.entries(rules).filter(([c, st]) => mapping[c] && st.some(x => !stepReady(x))).length;
  const errors = (analysis?.columns.filter(c => c.level === "error").length ?? 0) + duplicated.size + incompleteRules;
  const warns = analysis?.columns.filter(c => c.level === "warn").length ?? 0;
  const jobActive = job?.state === "queued" || job?.state === "running";
  const working = busy || jobActive;

  const body = () => ({ source: srcV!.connection, sourceSchema: src!.schema, sourceTable: src!.table, target: dstV!.connection, targetSchema: mode === "new" ? dstSchema : dst!.schema,
    targetTable, mappings, limit: Math.max(1, total), createTable: mode === "new", preserveKeys,
    copyIndexes: mode === "new" && copyIdx, copyDefaults: mode === "new" && copyDef, copyForeignKeys: mode === "new" && copyFk, chunkSize: bulk && chunkValid ? chunk : undefined, where: filtered ? where.trim() : undefined });
  function resetRun() { setVerify(null); setVerifyError(""); setPlan(null); setConfirmed(false); setRequestId(""); setDone(null); setJob(null); setError(""); setNotice(""); }
  async function run(action: () => Promise<void>) {
    setBusy(true); setError(""); setNotice("");
    try { await action(); } catch (reason) { setError(reason instanceof Error ? reason.message : "요청을 처리하지 못했습니다."); } finally { setBusy(false); onHistoryChanged(); }
  }

  // ---- 원본 선택
  function onSrcPick(picked: Picked | null) {
    setSrc(picked); setRules({}); setOpenRule(""); setWhere(""); setWhereRows(null); setWhereError(""); setSrcOk(false); setDstOk(false); setSrcRows(null); setAnalysis(null); setPreview(null); resetRun();
    if (!picked || !srcV) return;
    request("/connections/count", { connection: srcV.connection, schema: picked.schema, table: picked.table }).then(r => r.json()).then(r => setSrcRows(r.rows)).catch(() => setSrcRows(null));
  }
  /** FK의 부모 테이블을 먼저 이관하려고 원본 선택으로 돌아가 그 테이블을 고른다(대상은 같은 연결·스키마, 신규 테이블 모드). */
  function goParent(name: string) {
    setForce({ name, n: Date.now() }); setMode("new"); setNewName(name); setActive("src"); setSrcOk(false); setDstOk(false); resetRun();
    setNotice(`부모 테이블 ${name}을(를) 먼저 이관하세요. '원본 확정'과 '대상 확정'을 누르면 같은 대상으로 이관할 수 있습니다. 끝나면 원래 테이블을 다시 선택하세요.`);
  }
  function changeWhere(value: string) { setWhere(value); setWhereRows(null); setWhereError(""); setSrcOk(false); setDstOk(false); resetRun(); }
  async function checkWhere() {
    if (!srcV || !src) return;
    setWhereBusy(true); setWhereError("");
    try { setWhereRows((await (await request("/connections/count-filtered", { connection: srcV.connection, schema: src.schema, table: src.table, where: where.trim() })).json()).rows); }
    catch (reason) { setWhereRows(null); setWhereError(reason instanceof Error ? reason.message : "조건을 확인하지 못했습니다."); }
    finally { setWhereBusy(false); }
  }
  function confirmSource() { setSrcOk(true); setDstOk(false); resetRun(); setActive("dst"); }
  // ---- 대상 선택
  function setOverride(name: string, patch: Partial<Override>) {
    setOv(cur => { const next: Override = { ...(cur[name] ?? {}), ...patch }; (Object.keys(next) as (keyof Override)[]).forEach(k => { if (next[k] === undefined) delete next[k]; }); return { ...cur, [name]: next }; });
    resetRun();
  }
  function confirmTarget() {
    if (!src || !dstV) return;
    setOv({}); setRules({}); setOpenRule("");
    const usable = src.columns.filter(c => !UNSUPPORTED.has(c.jdbcType ?? 12));
    if (mode === "new") setMapping(Object.fromEntries(usable.map(c => [c.name, newColumnName(dstV.connection.dbms, c.name)])));
    else if (dst) {
      const pseudo = { columns: usable.map(c => ({ id: c.name, name: c.name, target_name: c.name.toUpperCase(), data_type: dataType(c.jdbcType), nullable: true, included: true, max_length: null, precision: null, scale: null })) } as unknown as Table;
      const suggestions = suggestMapping(pseudo, dst.columns.map(c => ({ name: c.name, type: c.type, jdbcType: c.jdbcType })));
      setMapping(Object.fromEntries(suggestions.filter(s => s.target).map(s => [s.columnId, s.target as string])));
    }
    setDstOk(true); resetRun(); setActive("map");
  }
  // ---- 검토: 매핑이 바뀔 때마다 컬럼 호환성을 분석한다.
  useEffect(() => {
    if (!dstOk || !src || !srcV || !dstV || !mappings.length || duplicated.size) { if (dstOk) setAnalysis(null); return; }
    const timer = setTimeout(() => {
      request("/connections/migrate/analyze", body()).then(r => r.json()).then((a: Analysis) => { setAnalysis(a); setAnalysisError(""); })
        .catch(reason => { setAnalysis(null); setAnalysisError(reason instanceof Error ? reason.message : "컬럼을 분석하지 못했습니다."); });
    }, 350);
    return () => clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dstOk, mappings, preserveKeys, mode, targetTable, copyIdx, copyDef, copyFk]);
  useEffect(() => { // 원본 데이터 미리보기(샘플 200행)
    if (!srcOk || !src || !srcV) return;
    const columns = src.columns.filter(c => !UNSUPPORTED.has(c.jdbcType ?? 12)).map(c => c.name);
    setPreview(null); setPreviewError(""); setPPage(0);
    request("/connections/read", { connection: srcV.connection, schema: src.schema, table: src.table, columns, limit: 200 }).then(r => r.json()).then(setPreview)
      .catch(reason => setPreviewError(reason instanceof Error ? reason.message : "미리보기를 불러오지 못했습니다."));
  }, [srcOk, src, srcV]);
  async function runVerify() {
    setVerifying(true); setVerifyError("");
    try { setVerify(await (await request("/connections/migrate/verify", body())).json()); }
    catch (reason) { setVerify(null); setVerifyError(reason instanceof Error ? reason.message : "검증하지 못했습니다."); }
    finally { setVerifying(false); }
  }
  useEffect(() => { if (done) void runVerify(); // 이관이 끝나면 자동으로 원본과 대상을 비교한다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [done]);
  // ---- 대용량 작업 진행률
  useEffect(() => {
    if (!job || !jobActive) return;
    const timer = setTimeout(async () => {
      try {
        const next: JobStatus = await (await request(`/connections/migrate/jobs/${job.id}`)).json(); setJob(next);
        if (next.state === "succeeded") { setDone({ insertedRows: next.doneRows, replayed: next.replayedChunks === next.totalChunks, notes: next.notes }); setNotice(`이관 완료: ${fmt(next.doneRows)}행`); onHistoryChanged(); }
        else if (next.state === "failed" || next.state === "cancelled") { setError(next.message || "이관이 중단되었습니다."); onHistoryChanged(); }
      } catch (reason) { setError(reason instanceof Error ? reason.message : "진행 현황을 조회하지 못했습니다."); }
    }, 800);
    return () => clearTimeout(timer);
  }, [job, jobActive, onHistoryChanged]);

  const nextText = active === "src" ? "원본 DB와 테이블을 선택한 뒤 '원본 확정'을 누르세요. 연결 목록에 없으면 '신규 연결'을 선택합니다."
    : active === "dst" ? "대상 DB를 선택하고 신규 테이블 생성 또는 기존 테이블 적재를 고른 뒤 '대상 확정'을 누르세요."
    : errors ? `컬럼 매핑을 확인하고 오류 ${errors}건을 해결하세요. 모두 해결하면 '이관 시작'을 누를 수 있습니다.` : "컬럼 매핑을 확인한 뒤 '사전 점검'으로 결과를 확인하고 '이관 시작'을 누르세요.";
  const startBlocked = !srcOk || !dstOk || !analysis || errors > 0 || !mappings.length || (bulk && !chunkValid) || (!!analysis && total > analysis.maxRows);
  const pRows = preview?.rows.slice(pPage * pSize, (pPage + 1) * pSize) ?? [];

  return <div className="grid">
    <div className="page-head"><h1>DB 이관</h1><p className="sub">원본과 대상을 선택하고, 매핑과 검증을 마치면 이관할 수 있습니다.</p></div>
    <details className="guide" open><summary>처음이신가요? 사용 방법</summary>
      <ol className="gs">
        <li><b><i>1</i>원본 선택</b>첫 번째 카드를 눌러 이관할 DB와 테이블을 선택한 뒤 &apos;원본 확정&apos;을 누릅니다. 연결 목록에 없으면 &apos;신규 연결&apos;을 선택하세요.</li>
        <li><b><i>2</i>대상 선택</b>두 번째 카드를 눌러 데이터를 넣을 DB를 선택합니다. 신규 테이블을 생성하거나 기존 테이블에 적재할 수 있습니다.</li>
        <li><b><i>3</i>검토</b>세 번째 카드에서 컬럼 매핑을 확인하고 검증 오류를 모두 해결합니다. 오류가 남아 있으면 이관할 수 없습니다.</li>
        <li><b><i>4</i>이관</b>&apos;사전 점검&apos;으로 결과를 미리 확인한 뒤 &apos;이관 시작&apos;을 누릅니다. 중단되어도 &apos;재개&apos;로 이어서 진행할 수 있습니다.</li>
      </ol>
      <p className="mut" style={{ marginTop: 12 }}>원본이 파일(CSV, Excel, PDF, 이미지)이면 위쪽 &apos;파일 변환&apos; 메뉴에서 변환하고 DB에 적재할 수 있습니다.</p></details>
    {server && !server.enabled && <div className="alert warning">서버에서 DB 조회가 비활성화되어 있습니다. 설정 메뉴에서 활성화 방법을 확인하세요(TABLEFLOW_JDBC_ENABLED=true).</div>}
    {server?.enabled && !server.loadEnabled && <div className="alert warning">DB 적재가 비활성화되어 있어 이관을 실행할 수 없습니다. 설정 메뉴에서 활성화 방법을 확인하세요(TABLEFLOW_JDBC_LOAD_ENABLED=true).</div>}
    {error && <div role="alert" className="alert error">{error}</div>}
    {notice && <div role="status" className="alert success">{notice}</div>}
    <div className="next" role="status"><b>다음 할 일</b><span>{nextText}</span></div>

    <div className="flow">
      <button type="button" className="node" aria-pressed={active === "src"} data-tip="이관할 데이터가 있는 DB와 테이블을 선택합니다." onClick={() => setActive("src")}>
        <small>1 · 원본 <span className="edit">선택</span></small>
        <h3>{srcOk && src ? `${srcV ? DBMS_LABEL[srcV.connection.dbms] : ""} · ${src.schema}.${src.table}` : "선택 전"}</h3>
        <div className="chips">{srcOk && src ? <><span className="chip">{srcRows != null ? `${fmt(srcRows)}행` : "행 수 확인 중"}</span><span className="chip">{src.columns.length}개 컬럼</span><span className="chip">{src.columns.some(c => c.primaryKey) ? `PK ${src.columns.filter(c => c.primaryKey).map(c => c.name).join(", ")}` : "PK 없음"}</span></> : <span className="chip">원본 확정 필요</span>}</div></button>
      <div className="link-arrow" />
      <button type="button" className="node" aria-pressed={active === "dst"} data-tip="데이터를 넣을 DB를 선택하고, 신규 테이블 생성 또는 기존 테이블 적재를 정합니다." onClick={() => setActive("dst")}>
        <small>2 · 대상 <span className="edit">선택</span></small>
        <h3>{dstOk && dstV ? `${DBMS_LABEL[dstV.connection.dbms]} · ${targetTable}` : "선택 전"}</h3>
        <div className="chips">{dstOk ? <><span className="chip">{mode === "new" ? "신규 테이블 생성" : "기존 테이블에 적재"}</span><span className="chip">{bulk ? (chunkValid ? `${fmt(chunk)}행 × ${chunks}회 분할` : "분할 단위 확인") : "분할 없음"}</span></> : <span className="chip">대상 확정 필요</span>}</div></button>
      <div className="link-arrow" />
      <button type="button" className="node" aria-pressed={active === "map"} disabled={!srcOk || !dstOk} data-tip="원본 컬럼이 들어갈 대상 컬럼을 확인하고 데이터 오류를 검증합니다. 원본과 대상을 먼저 선택하세요." onClick={() => setActive("map")}>
        <small>3 · 검토 <span className="edit">열기</span></small>
        <h3>{analysis ? `매핑 ${analysis.columns.length}건 · 검증 오류 ${errors}건` : srcOk && dstOk ? "분석 중" : "선택 전"}</h3>
        <div className="chips">{analysis ? <><span className="chip">경고 {warns}건</span>{preserveKeys && <span className="chip">기본 키 유지</span>}</> : <span className="chip">원본과 대상 확정 후</span>}</div></button>
    </div>

    <div role="region" hidden={active !== "src"} className="panel" aria-label="원본 선택"><h2>원본 선택<span className="hs">이관할 데이터가 있는 DB와 테이블을 선택하세요</span></h2>
      <div className="grid">
        <ConnectionPicker role="원본" server={server} saved={saved} onSavedChanged={reloadSaved} disabled={working} onVerified={v => { setSrcV(v); setSrc(null); setSrcOk(false); setDstOk(false); resetRun(); }} />
        <TablePicker role="원본" verified={srcV} disabled={working} tableHelp="이관할 원본 테이블입니다. 선택하면 행 수가 자동으로 집계됩니다." onPick={onSrcPick} onTables={setSrcTables} onSchema={setSrcSchemaSel} forceTable={force} />
        {srcTables.length > 1 && srcSchemaSel && <details><summary>이관 순서 계산 (외래 키 기준)</summary>
          <div className="grid" style={{ marginTop: 10 }}>
            <p className="mut">이 스키마의 테이블이 서로 참조하는 관계(FK)를 읽어, 부모 테이블이 먼저 오도록 이관 순서를 계산합니다. 테이블 이름을 누르면 원본으로 선택됩니다.</p>
            <div><button type="button" className="btn" disabled={working || !srcV} data-tip="선택한 스키마의 테이블(최대 200개)의 외래 키를 읽어 이관 순서를 계산합니다. 아무것도 변경하지 않습니다." onClick={() => void run(async () => {
              setOrderPlan(await (await request("/connections/order", { connection: srcV!.connection, schema: srcSchemaSel, tables: srcTables.slice(0, 200) })).json());
            })}>순서 계산</button></div>
            {orderPlan && <div className="grid" role="status" aria-label="이관 순서">
              {[...new Set(orderPlan.order.map(i => i.level))].map(level => <div key={level} className="row"><b>{level + 1}단계{level === 0 ? " (부모 없음)" : ""}</b>
                {orderPlan.order.filter(i => i.level === level).map(i => <button type="button" key={i.table} className="btn sm" data-tip={i.parents.length ? `먼저 이관: ${i.parents.join(", ")}` : i.externalParents.length ? `선택하지 않은 부모: ${i.externalParents.join(", ")}` : "부모 테이블 없음"} onClick={() => setForce({ name: i.table, n: Date.now() })}>{i.table}</button>)}</div>)}
              {orderPlan.cycles.length > 0 && <div className="alert warning">서로 참조해 순서를 정할 수 없는 테이블: {orderPlan.cycles.join(", ")}. FK를 복제하지 않고 이관한 뒤 DB에서 FK를 추가하세요.</div>}
            </div>}</div></details>}
        {src && <div className="chips"><span className="chip">{srcRows != null ? `총 ${fmt(srcRows)}행` : "행 수 확인 중"}</span><span className="chip">{src.columns.length}개 컬럼</span>{src.columns.some(c => UNSUPPORTED.has(c.jdbcType ?? 12)) && <span className="pill warn">이진 등 이관할 수 없는 컬럼은 자동으로 제외됩니다</span>}</div>}
        {src && <div className="split"><div className="sp-h">이관할 행 조건<Help text="원본에서 조건에 맞는 행만 이관합니다. WHERE 없이 조건식만 입력하세요. 예: STATUS = 'A' AND AMT > 100. 비워 두면 전체 행을 이관합니다. 컬럼 이름의 대소문자는 원본 DB 기준이며, PostgreSQL에서 대문자 컬럼은 큰따옴표로 감싸세요. 이관할 컬럼은 컬럼 매핑에서 대상 컬럼을 비워 제외합니다." /></div>
          <div className="row end"><label className="f" style={{ flex: 1 }}>행 조건(선택)<input aria-label="행 조건" maxLength={1000} disabled={working} placeholder="예: STATUS = 'A' AND AMT > 100" value={where} onChange={e => changeWhere(e.target.value)} /></label>
            <button type="button" className="btn" disabled={!filtered || whereBusy || working} data-tip="조건에 맞는 행 수를 조회합니다. 데이터는 바꾸지 않습니다." onClick={() => void checkWhere()}>{whereBusy ? "확인 중…" : "조건 확인"}</button></div>
          {whereError && <div className="alert warning" role="alert">{whereError}</div>}
          {filtered && whereRows != null && <p className="mapsum" role="status">조건에 맞는 행 {fmt(whereRows)}행 (전체 {srcRows != null ? fmt(srcRows) : "?"}행)</p>}
          {filtered && whereRows == null && !whereError && <p className="mut">조건을 입력했다면 &apos;조건 확인&apos;으로 행 수를 확인한 뒤 원본을 확정할 수 있습니다.</p>}
        </div>}
        <div className="row"><button type="button" className="btn p" disabled={!srcV || !src || srcRows == null || working || (filtered && whereRows == null)} data-tip="원본을 확정하고 대상 선택으로 넘어갑니다." onClick={confirmSource}>원본 확정</button>
          {!srcV && <span className="mut">연결 확인을 먼저 완료하세요</span>}</div>
      </div></div>

    <div role="region" hidden={active !== "dst"} className="panel" aria-label="대상 선택"><h2>대상 선택<span className="hs">데이터를 넣을 DB와 테이블을 선택하세요</span></h2>
      <div className="grid">
        <ConnectionPicker role="대상" server={server} saved={saved} onSavedChanged={reloadSaved} disabled={working} onVerified={v => { setDstV(v); setDst(null); setDstSchema(""); setDstOk(false); resetRun(); }} />
        <TablePicker role="대상" verified={dstV} disabled={working} showTable={mode === "existing"} tableHelp="데이터를 넣을 기존 테이블입니다. 컬럼 구조가 맞아야 합니다." onSchema={setDstSchema} onPick={p => { setDst(p); setDstOk(false); resetRun(); }} />
        <div className="opts">
          <div className="ol"><input type="radio" name="dst-mode" id="dst-new" checked={mode === "new"} onChange={() => { setMode("new"); setDstOk(false); resetRun(); }} /><label htmlFor="dst-new">신규 테이블 생성</label><Help text="대상에 없는 테이블을 원본 컬럼 구조에 맞춰 새로 생성한 뒤 적재합니다." /></div>
          <input aria-label="신규 테이블 이름" value={newName} maxLength={64} disabled={mode !== "new" || working} placeholder={src ? src.table : "예: TB_EMPLOYEE"} onChange={e => { setNewName(e.target.value); setDstOk(false); }} />
          <div className="ol"><input type="radio" name="dst-mode" id="dst-old" checked={mode === "existing"} onChange={() => { setMode("existing"); setDstOk(false); resetRun(); }} /><label htmlFor="dst-old">기존 테이블에 적재</label><Help text="이미 있는 테이블에 행을 추가합니다. 컬럼 구조가 맞아야 합니다." /></div>
          <span className="mut">{mode === "existing" ? "위에서 대상 테이블을 선택하세요" : "기존 테이블에 적재하려면 선택하세요"}</span>
          <div className="ol span"><input type="checkbox" id="keep-keys" checked={preserveKeys} disabled={working} onChange={e => { setPreserveKeys(e.target.checked); setDstOk(false); }} /><label htmlFor="keep-keys">원본 기본 키(PK) 유지</label><Help text="기본 키는 행을 구분하는 고유 번호입니다. 선택하면 자동 증가 대상 컬럼에도 원본 값을 그대로 넣고, 적재 후 시퀀스를 최댓값 다음으로 맞춥니다." /></div>
        </div>
        {mode === "new" && <div className="split"><div className="sp-h">함께 복제할 항목<Help text="신규 테이블을 만들 때 원본의 구조를 함께 가져옵니다. 기본값은 숫자·문자·불리언·현재 시각만 옮기고, 시퀀스·함수는 복제하지 않습니다. FK는 부모 테이블이 대상에 이미 있어야 만들 수 있습니다." /></div>
          <div className="row">
            <span className="check"><input id="copy-idx" type="checkbox" checked={copyIdx} disabled={working} onChange={e => { setCopyIdx(e.target.checked); setDstOk(false); }} /><label htmlFor="copy-idx">인덱스 복제</label></span>
            <span className="check"><input id="copy-def" type="checkbox" checked={copyDef} disabled={working} onChange={e => { setCopyDef(e.target.checked); setDstOk(false); }} /><label htmlFor="copy-def">기본값 복제</label></span>
            <span className="check"><input id="copy-fk" type="checkbox" checked={copyFk} disabled={working} onChange={e => { setCopyFk(e.target.checked); setDstOk(false); }} /><label htmlFor="copy-fk">외래 키(FK) 복제</label></span></div></div>}
        <div className="split">
          <div className="sp-h">분할 이관<Help text="대용량 데이터를 나누어 이관하는 설정입니다. 분할 단위마다 따로 저장되므로 중단되어도 이어서 진행할 수 있습니다. 기본값은 설정 메뉴의 분할 이관 단위입니다." /></div>
          <div className="row end"><label className="f" style={{ width: 200 }}>분할 단위(행)<input aria-label="분할 단위(행)" inputMode="numeric" value={chunkText} disabled={working} onChange={e => { setChunkText(e.target.value); setDstOk(false); }} /></label>
            <div className="chips" role="group" aria-label="분할 단위 빠른 선택">{[1000, 2500, 5000, 10000].map(v => <button type="button" key={v} className="pre" aria-pressed={chunk === v} disabled={working} onClick={() => { setChunkText(String(v)); setDstOk(false); }}>{fmt(v)}</button>)}</div></div>
          <p className={`mapsum ${chunkValid ? "" : "bad"}`} role="status">{!chunkValid ? "분할 단위는 100~10,000행 사이로 입력하세요" : srcRows == null ? "원본을 선택하면 분할 횟수를 계산합니다" : !bulk ? `총 ${fmt(total)}행은 분할 없이 한 번에 이관합니다` : `총 ${fmt(total)}행 ÷ ${fmt(chunk)}행 = ${chunks}회 분할 (마지막 회 ${fmt(total - (chunks - 1) * chunk)}행)`}</p>
        </div>
        <div className="row"><button type="button" className="btn p" disabled={!dstV || !srcOk || working || !chunkValid || (mode === "new" ? !dstSchema || !newName.trim() : !dst)} data-tip="대상을 확정하고 검토 단계로 넘어갑니다." onClick={confirmTarget}>대상 확정</button>
          {!srcOk && <span className="mut">원본을 먼저 확정하세요</span>}</div>
      </div></div>

    {active === "map" && srcOk && dstOk && src && <div role="region" className="panel" aria-label="검토"><h2>컬럼 매핑<Help text="원본 컬럼이 어느 대상 컬럼으로 들어갈지 짝지어 주는 설정입니다. 추천값이 미리 입력되어 있습니다." /><span className="hs">원본 컬럼이 들어갈 대상 컬럼을 지정하세요</span></h2>
      <div className="table-scroll mapping-table"><table><thead><tr><th>원본 컬럼</th><th>대상 컬럼<span className="hs">{mode === "existing" ? "대상 테이블의 전체 컬럼" : "신규 테이블의 컬럼 이름"}</span></th><th>대상 타입{mode === "new" && <span className="hs">신규 생성 시 직접 지정할 수 있습니다</span>}</th><th>변환 규칙<span className="hs">값을 바꿔 이관할 때 설정합니다</span></th><th>상태</th></tr></thead><tbody>
        {src.columns.filter(c => !UNSUPPORTED.has(c.jdbcType ?? 12)).map(column => { const row = analysis?.columns.find(c => c.source === column.name); const dup = duplicated.has((mapping[column.name] ?? "").toLowerCase());
          const level: Level | "" = dup ? "error" : row?.level ?? ""; const targetCol = dst?.columns.find(c => c.name === mapping[column.name]);
          const ruleSteps = rules[column.name] ?? [];
          return <Fragment key={column.name}><tr><td>{column.name}{column.primaryKey && <> <span className="pill" data-tip="기본 키(PK): 행을 구분하는 고유 번호입니다.">PK</span></>}{column.foreignKey && <> <span className="pill" data-tip="외래 키(FK): 다른 테이블의 행을 참조하는 컬럼입니다. 참조되는 테이블을 먼저 이관하세요.">FK</span></>}<small>{typeLabel(column)}</small></td>
            <td>{mode === "new" ? <input aria-label={`${column.name} 대상 컬럼`} maxLength={64} disabled={working} placeholder="이관 제외(비움)" value={mapping[column.name] ?? ""} onChange={e => { setMapping(m => ({ ...m, [column.name]: e.target.value })); resetRun(); }} />
              : <select aria-label={`${column.name} 대상 컬럼`} disabled={working} value={mapping[column.name] ?? ""} onChange={e => { setMapping(m => ({ ...m, [column.name]: e.target.value })); resetRun(); }}>
                <option value="">(이관 제외)</option>{dst?.columns.map(c => <option key={c.name} value={c.name}>{c.name} · {typeLabel(c)}{c.primaryKey ? " [PK]" : ""}{c.foreignKey ? " [FK]" : ""}</option>)}</select>}</td>
            <td>{mode === "new" ? (() => { const o = ov[column.name] ?? {}; const srcType = dataType(column.jdbcType); const t = o.type ?? srcType; const sized = SIZED.has(column.jdbcType ?? 12);
              return <div className="typeedit"><select aria-label={`${column.name} 대상 타입`} disabled={working || !mapping[column.name]} value={t} onChange={e => setOverride(column.name, { type: e.target.value === srcType ? undefined : e.target.value as DataType })}>{TYPES.map(([v, label]) => <option key={v} value={v}>{label}</option>)}</select>
                {(t === "string" || t === "integer" || t === "decimal") && <input type="number" aria-label={`${column.name} 길이 또는 정밀도`} data-tip={t === "string" ? "문자열 최대 길이입니다. 비우면 원본 컬럼 길이를 따릅니다." : "숫자의 전체 자릿수(정밀도)입니다. 비우면 원본을 따릅니다."} min={1} max={t === "string" ? 32767 : 38} disabled={working || !mapping[column.name]} placeholder={sized && column.size ? String(column.size) : "자동"} value={o.size ?? ""} onChange={e => setOverride(column.name, { size: e.target.value === "" ? undefined : Number(e.target.value) })} />}
                {t === "decimal" && <input type="number" aria-label={`${column.name} 소수 자릿수`} data-tip="소수점 이하 자릿수입니다. 비우면 원본을 따릅니다." min={0} max={38} disabled={working || !mapping[column.name]} placeholder={String(column.scale || 0)} value={o.scale ?? ""} onChange={e => setOverride(column.name, { scale: e.target.value === "" ? undefined : Number(e.target.value) })} />}</div>; })()
              : targetCol ? <>{typeLabel(targetCol)}{targetCol.primaryKey && <> <span className="pill">PK</span></>}{targetCol.foreignKey && <> <span className="pill">FK</span></>}</> : "—"}</td>
            <td className="wrap"><button type="button" className="btn sm" aria-expanded={openRule === column.name} aria-label={`${column.name} 변환 규칙`} disabled={!mapping[column.name]} onClick={() => setOpenRule(openRule === column.name ? "" : column.name)}>{ruleSteps.length ? `규칙 ${ruleSteps.length}개` : "설정"}</button>{ruleSteps.length > 0 && <small>{summarize(ruleSteps)}</small>}</td>
            <td className="wrap">{!mapping[column.name] ? <span className="pill">제외</span> : level ? <><span className={`pill ${levelClass[level]}`}>{dup ? "중복 선택" : levelLabel[level]}</span><small>{dup ? "같은 대상 컬럼이 다른 원본 컬럼에도 지정되어 있습니다" : row?.detail}</small></> : <span className="mut">분석 중</span>}</td></tr>
            {openRule === column.name && <tr><td colSpan={5}><TransformEditor column={column.name} columns={src.columns.filter(c => !UNSUPPORTED.has(c.jdbcType ?? 12)).map(c => c.name)} steps={ruleSteps} disabled={working || !mapping[column.name]}
              onChange={next => { setRules(r => ({ ...r, [column.name]: next })); resetRun(); }} /></td></tr>}</Fragment>; })}
      </tbody></table></div>
      <p className="mapsum" role="status" style={{ marginTop: 10 }}>매핑 {mappings.length}건 · 경고 {warns}건 · 오류 {errors}건{errors ? " — 오류를 해결해야 이관할 수 있습니다" : ""}</p>
      {analysisError && <div role="alert" className="alert error" style={{ marginTop: 10 }}>{analysisError}</div>}

      <h2 style={{ marginTop: 26 }}>데이터 검증<Help text="형식 오류, 길이 초과, 호환되지 않는 타입을 찾아냅니다. 오류가 남아 있으면 이관을 시작할 수 없습니다." /><span className="hs">이관 전에 컬럼 호환성과 샘플 데이터를 확인합니다</span></h2>
      <div className="stats"><div className="stat"><b>{fmt(total)}</b><span>이관할 행 수</span></div><div className="stat"><b style={{ color: errors ? "var(--bad)" : undefined }}>{errors}</b><span>오류</span></div><div className="stat"><b style={{ color: warns ? "var(--warn)" : undefined }}>{warns}</b><span>경고</span></div><div className="stat"><b>{analysis ? analysis.columns.filter(c => c.level === "ok" || c.level === "info").length : 0}</b><span>정상 컬럼</span></div></div>
      {analysis && analysis.foreignKeys.length > 0 && <div className="alert" role="status" aria-label="이관 순서 안내" style={{ marginTop: 12 }}><strong>외래 키(FK) · 이관 순서</strong>
        <ul>{analysis.foreignKeys.map(f => <li key={f.name}><span className={`pill ${f.copied ? "ok" : f.parentInTarget ? "" : "warn"}`}>{f.copied ? "FK 생성" : f.parentInTarget ? "부모 있음" : "부모 먼저 이관"}</span> {f.columns.join(", ")} → <b>{f.parentTable}</b> · {f.message}{!f.parentInTarget && <> <button type="button" className="btn sm" disabled={working} data-tip="이 부모 테이블을 원본으로 선택해 먼저 이관합니다." onClick={() => goParent(f.parentTable)}>부모 테이블 이관</button></>}</li>)}</ul></div>}
      {analysis && (analysis.warnings.length > 0 || analysis.notes.length > 0 || analysis.createsTable) && <div className="grid" style={{ marginTop: 12 }}>
        {analysis.warnings.length > 0 && <div className="alert warning" role="status"><strong>확인 필요</strong><ul>{analysis.warnings.map(w => <li key={w}>{w}</li>)}</ul></div>}
        {analysis.notes.length > 0 && <div className="alert" role="status"><ul>{analysis.notes.map(n => <li key={n}>{n}</li>)}</ul></div>}
        {analysis.createsTable && <div className="alert warning" role="status"><strong>이관하면 아래 테이블이 새로 생성됩니다(CREATE만 실행, 기존 테이블은 변경하지 않음)</strong>{analysis.ddl.map(s => <pre key={s} className="ddl-preview">{s}</pre>)}</div>}</div>}

      <h2 style={{ marginTop: 26 }}>데이터 미리보기<span className="hs">원본의 샘플 200행</span></h2>
      {previewError && <div className="alert warning">{previewError}</div>}
      {preview && <><div><table className="fit"><thead><tr>{preview.columns.map(c => <th key={c.id} title={c.name}>{c.name}</th>)}</tr></thead><tbody>
        {pRows.map(row => <tr key={row.id}>{preview.columns.map(c => <td key={c.id} title={row.cells[c.id]?.value ?? ""}>{row.cells[c.id]?.value ?? ""}</td>)}</tr>)}</tbody></table></div>
        <Pager total={preview.rows.length} page={pPage} size={pSize} onPage={setPPage} onSize={s => { setPSize(s); setPPage(0); }} note={`미리보기는 샘플 ${fmt(preview.rows.length)}행만 표시합니다. 이관할 때는 전체 ${fmt(total)}행을 처리합니다`} /></>}
    </div>}

    {srcOk && dstOk && <div role="region" className="panel" aria-label="이관 실행"><h2>이관 실행</h2>
      <div className="grid">
        <div className="row"><button type="button" className="btn" disabled={working || startBlocked || !server?.loadEnabled} data-tip="데이터를 넣지 않고 이관 결과와 생성될 테이블 구조를 미리 확인합니다." onClick={() => void run(async () => {
          resetRun(); setPlan(await (await request("/connections/migrate/check", body())).json()); setRequestId(crypto.randomUUID()); setNotice("사전 점검에 성공했습니다. 아직 아무것도 쓰지 않았습니다.");
        })}>사전 점검</button>
          <button type="button" className="btn p" disabled={working || !plan || !confirmed || !!done} aria-disabled={startBlocked || !plan || !confirmed} data-tip="검증 오류를 모두 해결하고 사전 점검과 동의를 마치면 활성화됩니다. 시작하면 분할 단위로 나누어 이관합니다." onClick={() => void run(async () => {
            if (plan!.bulk) { setJob(await (await request("/connections/migrate/jobs", { ...body(), requestId, confirmed: true })).json()); return; }
            const result = await (await request("/connections/migrate", { ...body(), requestId, confirmed: true })).json();
            setDone(result); setNotice(`이관 완료: ${fmt(result.insertedRows)}행${result.replayed ? " · 이미 처리된 요청입니다. 추가 적재하지 않았습니다." : ""}`);
          })}>{plan?.bulk && job && (job.state === "failed" || job.state === "cancelled") ? "재개" : "이관 시작"}</button>
          {errors > 0 && <span className="pill bad">검증 오류 {errors}건을 해결해야 이관할 수 있습니다</span>}
          {jobActive && <button type="button" className="btn" data-tip="진행 중인 이관을 중지합니다. 완료된 분량은 유지되며 나중에 재개할 수 있습니다." onClick={() => void run(async () => { setJob(await (await remove(`/connections/migrate/jobs/${job!.id}`)).json()); })}>중지</button>}</div>
        {plan && <div className="grid">
          <p><strong>{plan.sourceDbms} · {plan.source} → {plan.targetDbms} · {plan.target}</strong><br />원본 {fmt(plan.sourceRows)}행 중 {fmt(plan.rows)}행을 이관합니다.{plan.bulk ? ` (대용량: ${plan.chunks}회 분할 × 최대 ${fmt(plan.chunkSize)}행)` : ""}</p>
          {plan.notes.length > 0 && <div className="alert success" role="status"><ul>{plan.notes.map(n => <li key={n}>{n}</li>)}</ul></div>}
          {plan.warnings.length > 0 && <div className="alert warning" role="status"><strong>확인 필요</strong><ul>{plan.warnings.map(w => <li key={w}>{w}</li>)}</ul></div>}
          <div className="check"><input id="confirm-run" type="checkbox" checked={confirmed} disabled={working || !!done} onChange={e => setConfirmed(e.target.checked)} /><label htmlFor="confirm-run">{plan.createsTable ? "표시된 CREATE TABLE 실행과 " : "원본·대상과 행 수를 확인했으며 실제 "}DB INSERT에 동의합니다</label></div>
          <small className="mut">요청 ID: {requestId} · 통신 오류 시 같은 요청으로 재시도하면 중복 적재하지 않습니다.</small>
        </div>}
        {(job || done) && <div className="grid" role="status" aria-label="이관 진행 현황"><h2 style={{ margin: 0, fontSize: 15 }}>진행 현황<Help text="대용량 데이터를 나누어 이관한 진행률입니다. 분할 단위마다 따로 저장되므로 중단되어도 이어서 진행할 수 있습니다." /></h2>
          {job && <><div className="prog"><i style={{ width: `${job.totalRows ? Math.round(job.doneRows / job.totalRows * 100) : 0}%` }} /></div>
            <p>{job.state === "succeeded" ? "완료" : job.state === "failed" ? "실패" : job.state === "cancelled" ? "중지됨" : job.state === "queued" ? "대기 중" : "진행 중"} · {fmt(job.doneRows)} / {fmt(job.totalRows)}행 · {job.doneChunks} / {job.totalChunks}회 완료{job.replayedChunks > 0 ? ` (이미 완료되어 건너뜀 ${job.replayedChunks}회)` : ""}</p></>}
          {done?.notes && done.notes.length > 0 && <div className="alert success"><ul>{done.notes.map(n => <li key={n}>{n}</li>)}</ul></div>}</div>}
        {done && <div className="grid" role="region" aria-label="이관 결과 검증"><h2 style={{ margin: 0, fontSize: 15 }}>이관 결과 검증<Help text="이관이 끝난 뒤 원본과 대상의 행 수, 값이 있는 행 수, 숫자 합계·최소·최대, 문자열 길이 합계, 날짜 최소·최대를 비교합니다. 읽기만 하며 데이터를 바꾸지 않습니다." /></h2>
          <div className="row"><button type="button" className="btn" disabled={verifying} onClick={() => void runVerify()}>{verifying ? "검증 중…" : "다시 검증"}</button>
            {verify && <span className={`pill ${verify.allMatch ? "ok" : "bad"}`}>{verify.allMatch ? "원본과 대상이 일치합니다" : "불일치가 있습니다"}</span>}</div>
          {verifyError && <div className="alert warning">{verifyError}</div>}
          {verify && <><p>원본 {fmt(verify.sourceRows)}행 · 대상 {fmt(verify.targetRows)}행 {verify.rowsMatch ? "(일치)" : "(불일치)"}</p>
            {verify.notes.length > 0 && <div className="alert warning" role="status"><ul>{verify.notes.map(n => <li key={n}>{n}</li>)}</ul></div>}
            <div><table className="fit"><thead><tr><th>컬럼</th><th>비교 항목</th><th>원본</th><th>대상</th><th>결과</th></tr></thead><tbody>
              {verify.checks.map((c, i) => <tr key={i}><td>{c.source}{c.source !== c.target ? ` → ${c.target}` : ""}</td><td>{c.measure}</td><td>{c.sourceValue}</td><td>{c.targetValue}</td><td>{c.match ? "일치" : `불일치${c.note ? ` · ${c.note}` : ""}`}</td></tr>)}</tbody></table></div></>}
        </div>}
        {plan && !done && <ScheduleForm kind="single" build={() => body()} canSchedule={!!srcV?.connection.savedId && !!dstV?.connection.savedId} defaultName={`${src?.table ?? ""} → ${targetTable}`} disabled={working || startBlocked} />}
      </div></div>}
  </div>;
}
