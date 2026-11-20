"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { suggestColumns, suggestMapping, suggestTableName, type MappingSuggestion } from "@/lib/workspace";
import { ApiError, request } from "@/lib/api";
import type { DbColumn, SavedConnection, ServerInfo } from "@/lib/connections";
import type { Column, DataType, Issue, ParseResult, Table, ValidationResult } from "@/lib/types";
import ConnectionPicker, { type Verified } from "./ConnectionPicker";
import TablePicker, { type Picked } from "./TablePicker";
import { Help, Pager } from "./ui";

const types: [DataType, string][] = [["string", "문자열"], ["integer", "정수"], ["decimal", "소수"], ["date", "날짜"], ["datetime", "일시"], ["bool", "불리언"]];
const fmt = (n: number) => n.toLocaleString("ko-KR");

type LoadPlan = { dbms: string; schema: string; tableName: string; rows: number; columns: string[]; createsTable?: boolean; ddl?: string[]; warnings?: string[] };

export default function FilePage({ server, saved, reloadSaved, onHistoryChanged }: { server: ServerInfo | null; saved: SavedConnection[]; reloadSaved: () => void; onHistoryChanged: () => void }) {
  const [sourceMode, setSourceMode] = useState<"file" | "database">("file");
  const [outMode, setOutMode] = useState<"sql" | "excel" | "csv">("sql");
  const [duplicateMode, setDuplicateMode] = useState("warn");
  const [headerRow, setHeaderRow] = useState(1);
  const [table, setTable] = useState<Table | null>(null);
  const [tables, setTables] = useState<Table[]>([]);
  const [selectedTable, setSelectedTable] = useState(0);
  const [pdfPage, setPdfPage] = useState(0);
  const [reviewed, setReviewed] = useState(false);
  const [externalConsent, setExternalConsent] = useState(false);
  const [preview, setPreview] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [sheets, setSheets] = useState<string[]>([]);
  const [sheet, setSheet] = useState("");
  const [encoding, setEncoding] = useState("UTF-8");
  const [delimiter, setDelimiter] = useState(",");
  const [warnings, setWarnings] = useState<string[]>([]);
  const [issues, setIssues] = useState<Issue[]>([]);
  const [validated, setValidated] = useState(false);
  const [busy, setBusy] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [dbms, setDbms] = useState("oracle");
  const [tableName, setTableName] = useState("TB_DATA");
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(10);
  const [tab, setTab] = useState<"data" | "mapping">("data");
  const [dragging, setDragging] = useState(false);
  const [focusCell, setFocusCell] = useState<{ row: string; column: string } | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);

  // ---- DB 테이블에서 추출(원본)
  const [exV, setExV] = useState<Verified | null>(null);
  const [exPick, setExPick] = useState<Picked | null>(null);
  const [exCols, setExCols] = useState<string[]>([]);
  const [exLimit, setExLimit] = useState(1000);
  // ---- DB 적재(대상)
  const [ldV, setLdV] = useState<Verified | null>(null);
  const [ldSchema, setLdSchema] = useState("");
  const [ldPick, setLdPick] = useState<Picked | null>(null);
  const [ldMode, setLdMode] = useState<"new" | "existing">("new");
  const [ldName, setLdName] = useState("");
  const [ldPlan, setLdPlan] = useState<LoadPlan | null>(null);
  const [ldConfirmed, setLdConfirmed] = useState(false);
  const [ldRequestId, setLdRequestId] = useState("");
  const [ldResult, setLdResult] = useState<{ insertedRows: number; requestId: string; replayed: boolean } | null>(null);
  const [suggestions, setSuggestions] = useState<MappingSuggestion[] | null>(null);

  useEffect(() => {
    if (!focusCell) return;
    const input = document.getElementById(`cell-${focusCell.row}-${focusCell.column}`);
    input?.scrollIntoView({ block: "center", inline: "center" }); input?.focus(); setFocusCell(null);
  }, [focusCell, page, tab]);
  useEffect(() => {
    if (!file) return;
    const url = URL.createObjectURL(file); setPreview(url);
    return () => URL.revokeObjectURL(url);
  }, [file]);

  function resetLoad() { setLdPlan(null); setLdConfirmed(false); setLdRequestId(""); setLdResult(null); setSuggestions(null); }
  function changed(next: Table) {
    resetLoad();
    setTable(next); setTables(previous => previous.map((t, index) => index === selectedTable ? next : t));
    setReviewed(false); setValidated(false); setIssues([]); setNotice(""); setError("");
  }
  async function run(label: string, action: () => Promise<void>) {
    setBusy(label); setError(""); setNotice("");
    try { await action(); } catch (e) {
      setError(e instanceof Error ? e.message + (e instanceof ApiError && e.hint ? ` 해결: ${e.hint}` : "") : "처리 중 오류가 발생했습니다.");
      if (e instanceof ApiError && e.issues.length) { setIssues(e.issues); setValidated(true); }
    } finally { setBusy(""); }
  }
  async function parse(selected: File, selectedSheet = "") {
    if (selected.size > 10 * 1024 * 1024) { setError("파일 최대 크기는 10MB입니다."); return; }
    if (!/\.(csv|xlsx|pdf|png|jpe?g)$/i.test(selected.name)) { setError("CSV, XLSX, PDF, PNG 또는 JPEG 파일을 선택해주세요."); return; }
    if (table && !window.confirm("현재 편집 내용을 새 파일 또는 시트로 바꿀까요?")) return;
    await run("파일을 분석하고 있습니다", async () => {
      const data = new FormData(); data.append("file", selected); data.append("encoding", encoding); data.append("delimiter", delimiter);
      data.append("page", String(pdfPage)); data.append("headerRow", String(headerRow));
      data.append("externalConsent", String(externalConsent)); setExternalConsent(false);
      if (selectedSheet) data.append("sheet", selectedSheet);
      const result: ParseResult = await (await request("/files/parse", data, true)).json();
      if (!result.tables.length) throw new Error("추출된 표가 없습니다.");
      result.tables = result.tables.map(suggestColumns);
      setTableName(suggestTableName(result.tables[0].name)); setLdName(suggestTableName(result.tables[0].name));
      changed(result.tables[0]); setFile(selected); setSheets(result.sheets); setSheet(selectedSheet || result.sheets[0] || "");
      setTables(result.tables); setSelectedTable(0);
      setWarnings(result.warnings); setPage(0); setTab("data");
    });
  }
  function extracted(raw: Table) {
    const next = suggestColumns(raw);
    changed(next); setFile(null); setSheets([]); setSheet(""); setWarnings([]); setTables([next]); setSelectedTable(0);
    setTableName(suggestTableName(next.name)); setLdName(suggestTableName(next.name)); setPage(0); setTab("data");
    setTimeout(() => document.getElementById("workbench")?.scrollIntoView({ block: "start" }), 0);
  }
  function applyMapping(targets: Record<string, string>) {
    if (table) changed({ ...table, columns: table.columns.map(c => targets[c.id] ? { ...c, target_name: targets[c.id] } : c) });
  }
  function editCell(rowId: string, columnId: string, value: string) {
    if (!table) return;
    changed({ ...table, rows: table.rows.map(row => row.id === rowId ? { ...row, cells: { ...row.cells, [columnId]: { ...row.cells[columnId], value, edited: true } } } : row) });
  }
  function editColumn(id: string, update: Partial<Column>) {
    if (table) changed({ ...table, columns: table.columns.map(c => c.id === id ? { ...c, ...update } : c) });
  }
  async function validate() {
    if (!table) return;
    resetLoad();
    await run("데이터를 검증하고 있습니다", async () => {
      const transformed: Table = await (await request(`/tables/transform?duplicateMode=${duplicateMode}`, table)).json();
      const result: ValidationResult = await (await request(`/tables/validate?duplicateMode=${duplicateMode}`, transformed)).json();
      setTable(transformed); setTables(previous => previous.map((t, index) => index === selectedTable ? transformed : t));
      setReviewed(false); setIssues(result.issues); setValidated(true);
      setNotice(result.valid ? "모든 검증을 통과했습니다. 파일로 출력하거나 DB에 적재할 수 있습니다." + (table.rows.length > transformed.rows.length ? ` 중복 ${table.rows.length - transformed.rows.length}행을 제외했습니다.` : "") : "검증 오류를 수정한 뒤 다시 검증해주세요.");
    });
  }
  async function download(format: "sql" | "excel" | "csv") {
    if (!table) return;
    await run("파일을 생성하고 있습니다", async () => {
      const response = await request(`/exports/${format}?duplicateMode=${duplicateMode}`, format === "sql" ? { table, dbms, tableName } : table, false, reviewed);
      const url = URL.createObjectURL(await response.blob());
      const link = document.createElement("a"); link.href = url; link.download = `tableflow.${format === "excel" ? "xlsx" : format}`;
      document.body.appendChild(link); link.click(); link.remove(); setTimeout(() => URL.revokeObjectURL(url), 1000);
      setNotice("파일 다운로드를 시작했습니다.");
    });
  }
  async function loadSample(name = "employees.csv") {
    const response = await fetch(`/samples/${name}`);
    if (!response.ok) { setError("샘플 파일을 읽지 못했습니다."); return; }
    setSourceMode("file"); await parse(new File([await response.blob()], name));
  }

  const hasErrors = issues.some(issue => issue.severity !== "warning");
  const visible = table?.rows.slice(page * pageSize, (page + 1) * pageSize) || [];
  const modified = table?.rows.reduce((sum, row) => sum + Object.values(row.cells).filter(c => c.edited).length, 0) || 0;
  const issueMap = new Set(issues.filter(issue => issue.severity !== "warning").map(i => `${i.rowId}:${i.columnId}`));
  const isOcr = table?.source.extraction_method.includes("ocr") || false;
  const canExport = !!table && !busy && validated && !hasErrors && (!isOcr || reviewed);
  const loadTarget = ldMode === "new" ? ldName.trim() : ldPick?.table ?? "";
  const loadSchema = ldMode === "new" ? ldSchema : ldPick?.schema ?? ldSchema;
  const loadBody = () => ({ connection: ldV!.connection, table, schema: loadSchema, tableName: loadTarget, createTable: ldMode === "new" });
  const ldColumns: DbColumn[] = useMemo(() => ldPick?.columns ?? [], [ldPick]);

  return <div className="grid">
    <div className="page-head"><h1>파일 변환</h1><p className="sub">CSV, Excel, PDF, 이미지를 표로 변환하여 SQL, Excel, CSV로 출력하거나 DB에 적재합니다.</p></div>
    {error && <div className="alert error" role="alert">{error}</div>}
    {notice && <div className="alert success" role="status">{notice}</div>}
    {warnings.map((warning, i) => <div className="alert warning" key={i}>{warning}</div>)}

    <section className="panel" aria-label="데이터 소스"><h2>데이터 소스<span className="hs">파일을 업로드하거나 DB 테이블에서 행을 추출합니다</span></h2>
      <div className="tabs" role="tablist" aria-label="소스 종류">
        <button role="tab" aria-selected={sourceMode === "file"} className={sourceMode === "file" ? "selected" : ""} onClick={() => setSourceMode("file")}>파일 업로드</button>
        <button role="tab" aria-selected={sourceMode === "database"} className={sourceMode === "database" ? "selected" : ""} onClick={() => setSourceMode("database")}>DB 테이블에서 추출</button>
        <button disabled={!!busy} onClick={() => void loadSample()}>샘플 데이터로 시작</button></div>
      {sourceMode === "file" ? <div className="grid">
        <div className={`drop ${dragging ? "dragging" : ""}`} onDragOver={e => { e.preventDefault(); if (!busy) setDragging(true); }} onDragLeave={() => setDragging(false)} onDrop={e => { e.preventDefault(); setDragging(false); if (!busy && e.dataTransfer.files[0]) void parse(e.dataTransfer.files[0]); }}>
          <b>{file ? file.name : "파일을 이곳에 업로드하세요"}</b><span>Excel · CSV · PDF · PNG/JPEG · 최대 10MB</span>
          <button type="button" className="btn p" disabled={!!busy} onClick={() => fileInput.current?.click()}>파일 선택</button>
          <input ref={fileInput} hidden type="file" accept=".csv,.xlsx,.pdf,.png,.jpg,.jpeg" aria-label="파일 선택" onChange={e => { const f = e.target.files?.[0]; if (f) void parse(f); e.target.value = ""; }} /></div>
        <details className="advanced-options"><summary>입력 설정 · 인코딩 / 헤더 / 페이지</summary><div className="form" style={{ marginTop: 12 }}>
          {(!file || /\.csv$/i.test(file.name)) && <>
            <label className="f">CSV 인코딩<select value={encoding} disabled={!!busy} onChange={e => setEncoding(e.target.value)}><option>UTF-8</option><option>MS949</option><option>EUC-KR</option></select></label>
            <label className="f">구분자<select value={delimiter} disabled={!!busy} onChange={e => setDelimiter(e.target.value)}><option value=",">쉼표 (,)</option><option value=";">세미콜론 (;)</option><option value={"\t"}>탭</option><option value="|">파이프 (|)</option></select></label></>}
          {sheets.length > 0 && <label className="f">Excel 시트<select disabled={!!busy} value={sheet} onChange={e => { if (file) void parse(file, e.target.value); }}>{sheets.map(s => <option key={s}>{s}</option>)}</select></label>}
          {(!file || /\.(csv|xlsx)$/i.test(file.name)) && <label className="f">헤더 행<input aria-label="헤더 행" type="number" min={1} max={100} value={headerRow} disabled={!!busy} onChange={event => setHeaderRow(Math.min(100, Math.max(1, Number(event.target.value) || 1)))} /></label>}
          {(!file || /\.pdf$/i.test(file.name)) && <label className="f">PDF 페이지 (0 = 전체, 최대 20)<input type="number" aria-label="PDF 페이지" min={0} max={20} value={pdfPage} disabled={!!busy} onChange={e => setPdfPage(Math.min(20, Math.max(0, Number(e.target.value))))} /></label>}
        </div>
          <div className="row" style={{ marginTop: 8 }}>
            {file?.name.toLowerCase().endsWith(".pdf") && <button className="text-button" disabled={!!busy} onClick={() => void parse(file)}>페이지 다시 읽기 ↻</button>}
            {file && /\.(csv|xlsx)$/i.test(file.name) && <button className="text-button" disabled={!!busy} onClick={() => void parse(file)}>설정으로 다시 읽기 ↻</button>}
            <span className="mut">원본 파일은 서버에 영구 저장하지 않습니다.</span></div></details>
        <div className="row"><span className="mut">샘플:</span>{([["employees.csv", "기본 사원정보"], ["validation-errors.csv", "검증 오류"], ["duplicates.csv", "중복 데이터"], ["multiple-sheets.xlsx", "다중 시트 Excel"]] as const).map(([name, label]) => <button key={name} className="btn sm" disabled={!!busy} onClick={() => void loadSample(name)}>{label}</button>)}
          <a href="/samples/employees.csv" download>샘플 CSV</a><a href="/samples/table-text.pdf" download>PDF</a><a href="/samples/table-ko.png" download>이미지 ↓</a></div>
        <details className="ocr-options"><summary>OCR 전송 설정</summary><div className="check" style={{ marginTop: 8 }}><input id="ocr-consent" type="checkbox" checked={externalConsent} disabled={!!busy} onChange={e => setExternalConsent(e.target.checked)} /><label htmlFor="ocr-consent">이번 파일의 외부 OCR 전송에 동의합니다</label></div><p className="mut">로컬 OCR은 외부로 전송하지 않습니다. 외부 공급자는 서버의 명시적 허용과 파일별 동의가 모두 필요합니다.</p></details>
      </div> : <div className="grid">
        <ConnectionPicker role="원본" server={server} saved={saved} onSavedChanged={reloadSaved} disabled={!!busy} onVerified={v => { setExV(v); setExPick(null); }} />
        <TablePicker role="원본" verified={exV} disabled={!!busy} tableHelp="행을 추출할 테이블 또는 뷰입니다." onPick={p => { setExPick(p); setExCols(p ? p.columns.map(c => c.name) : []); }} />
        {exPick && <div className="grid"><div className="table-scroll mapping-table"><table><thead><tr><th>추출</th><th>컬럼</th><th>DB 타입</th><th>길이 / 정밀도</th><th>소수 자릿수</th><th>NULL</th><th>키</th></tr></thead><tbody>
          {exPick.columns.map(c => <tr key={c.name}><td><input type="checkbox" aria-label={`${c.name} 추출`} disabled={!!busy} checked={exCols.includes(c.name)} onChange={e => setExCols(cur => e.target.checked ? exPick.columns.map(i => i.name).filter(n => n === c.name || cur.includes(n)) : cur.filter(n => n !== c.name))} /></td>
            <td>{c.name}</td><td>{c.type}</td><td>{c.size}</td><td>{c.scale}</td><td>{c.nullable ? "허용" : "필수"}</td><td>{[c.primaryKey ? "PK" : "", c.foreignKey ? "FK" : "", c.autoIncrement ? "자동 증가" : ""].filter(Boolean).join(" · ") || "—"}</td></tr>)}</tbody></table></div>
          <div className="row end"><label className="f" style={{ width: 200 }}>최대 추출 행 수<input type="number" min={1} max={10000} aria-label="최대 추출 행 수" disabled={!!busy} value={exLimit} onChange={e => setExLimit(Math.min(10000, Math.max(1, Number(e.target.value) || 1)))} /></label>
            <button className="btn p" disabled={!!busy || !exCols.length} onClick={() => void run("DB에서 행을 추출하고 있습니다", async () => {
              const result: Table = await (await request("/connections/read", { connection: exV!.connection, schema: exPick.schema, table: exPick.table, columns: exCols, limit: exLimit })).json();
              extracted(result); setNotice(`${fmt(result.rows.length)}행을 추출했습니다. 아래 워크벤치에서 검토·검증한 뒤 출력하세요.`);
            })}>선택한 컬럼 행 추출</button></div>
          <p className="mut">선택한 {exCols.length}개 컬럼을 최대 {fmt(exLimit)}행까지 읽기 전용으로 가져옵니다(최대 10,000행). 이진(BLOB) 컬럼은 제외해주세요. DB 데이터는 변경되지 않습니다.</p></div>}
      </div>}
    </section>

    {table && <div className="source-info">
      <span>추출: <strong>{table.source.extraction_method === "local_ocr" ? "로컬 OCR" : table.source.extraction_method === "mock_ocr" ? "MOCK · 고정 테스트 데이터" : table.source.extraction_method === "external_ocr" ? "외부 OCR" : table.source.extraction_method === "pdf_text" ? "PDF 텍스트" : table.source.extraction_method === "jdbc" ? `DB 추출 · ${table.source.filename}` : "파일 파서"}</strong>{table.source.page ? ` · ${table.source.page}페이지` : ""}</span>
      {tables.length > 1 && <label>추출된 표 <select aria-label="추출된 표" disabled={!!busy} value={selectedTable} onChange={e => { resetLoad(); const index = Number(e.target.value); setSelectedTable(index); setTable(tables[index]); setValidated(false); setReviewed(false); setIssues([]); setPage(0); setError(""); setNotice(""); }}>{tables.map((t, index) => <option key={t.table_id} value={index}>{t.name}</option>)}</select></label>}
      {preview && <a href={preview} target="_blank" rel="noreferrer">원본 파일 열기 ↗</a>}
    </div>}
    {isOcr && <section className="panel ocr-review"><h2>OCR 결과 검토</h2><p className="mut">신뢰도가 낮은 셀을 강조했습니다. 원본과 대조하고 필요한 값을 수정한 뒤 검증해주세요.</p>
      {preview && file?.type.startsWith("image/") && <img src={preview} alt="OCR 원본 표" className="source-image" />}
      <div className="check" style={{ marginTop: 10 }}><input id="ocr-reviewed" type="checkbox" checked={reviewed} disabled={!!busy || !validated || hasErrors} onChange={e => setReviewed(e.target.checked)} /><label htmlFor="ocr-reviewed">검증된 OCR 결과를 원본과 대조하여 검토했습니다</label></div></section>}

    <section id="workbench" className="panel" aria-label="데이터 편집"><h2>데이터 워크벤치<span className="hs">{fmt(table?.rows.length ?? 0)}행 · {table?.columns.length ?? 0}열{modified > 0 ? ` · ${modified}개 수정` : ""}</span></h2>
      <div className="tabs" role="tablist" aria-label="워크벤치 보기"><button role="tab" aria-selected={tab === "data"} onClick={() => setTab("data")} className={tab === "data" ? "selected" : ""}>데이터 미리보기</button><button role="tab" aria-selected={tab === "mapping"} onClick={() => setTab("mapping")} className={tab === "mapping" ? "selected" : ""}>컬럼 매핑 {table?.columns.length || 0}</button></div>
      {!table ? <div className="empty-state"><h3>데이터를 불러오면 검토를 시작할 수 있습니다</h3><p>파일을 업로드하면 여기서 데이터를 검토하고 수정할 수 있습니다.</p></div> : tab === "data" ? <>
        <div className="table-scroll"><table className="data-table"><thead><tr><th className="row-number">#</th>{table.columns.map(c => <th key={c.id} className={!c.included ? "excluded" : ""}>{c.name}<small>{c.target_name} · {c.data_type}</small></th>)}</tr></thead><tbody>{visible.map((row, index) => <tr key={row.id}><td className="row-number">{page * pageSize + index + 1}</td>{table.columns.map(c => {
          const cell = row.cells[c.id]; const invalid = issueMap.has(`${row.id}:${c.id}`) || issueMap.has(`${row.id}:null`);
          return <td key={c.id} className={`${invalid ? "invalid" : ""} ${cell?.edited ? "edited" : ""} ${cell?.confidence != null && cell.confidence < 0.85 ? "low-confidence" : ""} ${!c.included ? "excluded" : ""}`}><input id={`cell-${row.id}-${c.id}`} aria-label={`${page * pageSize + index + 1}행 ${c.name}`} aria-invalid={invalid} disabled={!!busy} value={cell?.value ?? ""} title={`원본: ${cell?.raw ?? "(빈 값)"}`} onChange={e => editCell(row.id, c.id, e.target.value)} />{cell?.confidence != null && <small className="confidence">신뢰도 {Math.round(cell.confidence * 100)}%</small>}</td>;
        })}</tr>)}</tbody></table></div>
        <Pager total={table.rows.length} page={page} size={pageSize} onPage={setPage} onSize={s => { setPageSize(s); setPage(0); }} note="셀을 클릭해 수정, 셀 위에 마우스를 올려 원본 확인" />
      </> : <div className="table-scroll mapping-table"><table><thead><tr><th>포함</th><th>원본 컬럼</th><th>대상 컬럼명</th><th>자료형</th><th>필수</th><th>최대 길이</th><th>정밀도</th><th>소수 자릿수</th></tr></thead><tbody>{table.columns.map(c => <tr key={c.id} className={!c.included ? "excluded" : ""}>
        <td><input aria-label={`${c.name} 포함`} type="checkbox" disabled={!!busy} checked={c.included} onChange={e => editColumn(c.id, { included: e.target.checked })} /></td><td>{c.name}</td>
        <td><input aria-label={`${c.name} 대상 컬럼명`} disabled={!!busy} maxLength={64} value={c.target_name} onChange={e => editColumn(c.id, { target_name: e.target.value })} /></td>
        <td><select aria-label={`${c.name} 자료형`} disabled={!!busy} value={c.data_type} onChange={e => editColumn(c.id, { data_type: e.target.value as DataType })}>{types.map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></td>
        <td><input aria-label={`${c.name} 필수`} disabled={!!busy} type="checkbox" checked={!c.nullable} onChange={e => editColumn(c.id, { nullable: !e.target.checked })} /></td>
        {(["max_length", "precision", "scale"] as const).map((key, i) => <td key={key}><input type="number" min={key === "scale" ? 0 : 1} max={key === "max_length" ? 32767 : 38} aria-label={`${c.name} ${["최대 길이", "정밀도", "소수 자릿수"][i]}`} disabled={!!busy} placeholder="—" value={c[key] ?? ""} onChange={e => editColumn(c.id, { [key]: e.target.value === "" ? null : Number(e.target.value) })} /></td>)}
      </tr>)}</tbody></table><p className="mut" style={{ marginTop: 10 }}>대상 컬럼명은 원본 이름과 고정 별칭으로 추천했습니다. 실제 DB 컬럼과 자동으로 일치한다고 보장하지 않으므로 확인해주세요. 자료형을 추정하며 선행 0이 있는 값은 문자열로 유지합니다. 빈 값·공백만 있는 값은 NULL로 처리합니다.</p></div>}
      <div className="row end" style={{ marginTop: 14 }}><label className="f" style={{ minWidth: 200 }}>중복 행 처리<Help text="같은 값이 반복되는 행의 처리 방식입니다. 경고만 표시, 모두 유지, 중복 제외, 엄격 검증 중에서 선택합니다." />
        <select aria-label="중복 행 처리" value={duplicateMode} disabled={!!busy} onChange={event => { setDuplicateMode(event.target.value); setValidated(false); setIssues([]); resetLoad(); }}><option value="warn">경고만 표시</option><option value="keep">모두 유지</option><option value="exclude">중복 제외</option><option value="strict">엄격 검증</option></select></label>
        <button className="btn p" disabled={!table || !!busy} onClick={() => void validate()}>데이터 검증</button><span className="mut">중복 제외는 포함된 컬럼의 정규화 값을 비교해 첫 행만 남깁니다.</span></div>
    </section>

    {issues.length > 0 && <section className="panel issues"><h2>검증 결과 {issues.length}개<span className="hs">최대 1,000개 표시</span></h2><ul className="issue-list">{issues.slice(0, 100).map((issue, i) => <li key={i}><button className="btn sm" onClick={() => { const index = table?.rows.findIndex(r => r.id === issue.rowId) ?? -1; if (index >= 0) { setPage(Math.floor(index / pageSize)); setTab("data"); const column = issue.columnId || table?.columns[0]?.id; if (column && issue.rowId) setFocusCell({ row: issue.rowId, column }); } else { setTab("mapping"); document.getElementById("workbench")?.scrollIntoView({ block: "start" }); } }}>{issue.rowId ? `${(table?.rows.findIndex(r => r.id === issue.rowId) ?? 0) + 1}행` : "컬럼 설정"} {table?.columns.find(c => c.id === issue.columnId)?.name}</button><div><span className={issue.severity === "warning" ? "issue-warning" : ""}>{issue.severity === "warning" ? "경고 · " : ""}{issue.message}</span><small className="issue-solution">{issue.solution}</small></div></li>)}</ul>{issues.length > 100 && <p className="mut">상위 100개 오류를 표시합니다. 수정 후 다시 검증해주세요.</p>}</section>}

    <section id="target" className="panel" aria-label="파일로 출력"><h2>파일로 출력<span className="hs">변환한 표를 파일로 내려받습니다</span><span className={`pill ${validated && !hasErrors ? "ok" : ""}`} style={{ marginLeft: 12 }}>{validated ? hasErrors ? "오류 수정 필요" : "검증 완료" : "검증 대기"}</span></h2>
      <div className="tabs">{([["sql", "SQL 파일"], ["excel", "Excel 파일"], ["csv", "CSV 파일"]] as const).map(([value, label]) => <button key={value} aria-pressed={outMode === value} className={outMode === value ? "selected" : ""} onClick={() => setOutMode(value)}>{label}</button>)}</div>
      <div className="row end">{outMode === "sql" ? <><label className="f">대상 DBMS<select value={dbms} disabled={!!busy} onChange={e => setDbms(e.target.value)}><option value="oracle">Oracle</option><option value="postgresql">PostgreSQL</option><option value="mysql">MySQL</option><option value="mariadb">MariaDB</option></select></label>
        <label className="f" style={{ minWidth: 260 }}>SQL 대상 테이블명<input placeholder="예: TB_EMPLOYEE" value={tableName} maxLength={dbms === "postgresql" ? 63 : 64} disabled={!!busy} onChange={e => setTableName(e.target.value)} /></label>
        <button className="btn p" disabled={!canExport} onClick={() => void download("sql")}>SQL 내보내기 ↓</button></> : outMode === "excel" ? <button className="btn p" disabled={!canExport} onClick={() => void download("excel")}>Excel ↓</button> : <button className="btn p" disabled={!canExport} onClick={() => void download("csv")}>CSV 내보내기 ↓</button>}</div>
      <p className="mut" style={{ marginTop: 10 }}>{outMode === "sql" ? "생성할 SQL에서 사용할 테이블명입니다. 실제 DB에 연결하거나 데이터를 저장하지 않습니다." : outMode === "csv" ? "UTF-8 BOM 형식입니다. 수식처럼 보이는 값은 작은따옴표로 보호하며 NULL과 빈 문자열은 빈 칸으로 출력합니다." : "매핑한 컬럼과 검증한 값을 XLSX로 내려받습니다. DB 연결은 필요하지 않습니다."}</p>
    </section>

    <section id="db-load" className="panel" aria-label="DB 적재"><h2>DB 적재<span className="hs">변환한 표를 대상 DB의 테이블에 넣습니다</span></h2>
      <div className="grid">
        {server && !server.loadEnabled && <div className="alert warning">DB 적재가 비활성화되어 있습니다. 설정 메뉴에서 활성화 방법을 확인하세요(TABLEFLOW_JDBC_LOAD_ENABLED=true).</div>}
        <ConnectionPicker role="대상" server={server} saved={saved} onSavedChanged={reloadSaved} disabled={!!busy} onVerified={v => { setLdV(v); setLdPick(null); setLdSchema(""); resetLoad(); }} />
        <TablePicker role="대상" verified={ldV} disabled={!!busy} showTable={ldMode === "existing"} tableHelp="데이터를 넣을 기존 테이블입니다. 컬럼 구조가 맞아야 합니다." onSchema={s => { setLdSchema(s); resetLoad(); }} onPick={p => { setLdPick(p); resetLoad(); }} />
        <div className="opts">
          <div className="ol"><input type="radio" name="ld-mode" id="ld-new" checked={ldMode === "new"} onChange={() => { setLdMode("new"); resetLoad(); }} /><label htmlFor="ld-new">신규 테이블 생성</label><Help text="대상에 없는 테이블을 변환한 표의 컬럼 구조에 맞춰 새로 생성한 뒤 적재합니다." /></div>
          <input aria-label="신규 테이블 이름" value={ldName} maxLength={64} disabled={ldMode !== "new" || !!busy} onChange={e => { setLdName(e.target.value); resetLoad(); }} />
          <div className="ol"><input type="radio" name="ld-mode" id="ld-old" checked={ldMode === "existing"} onChange={() => { setLdMode("existing"); resetLoad(); }} /><label htmlFor="ld-old">기존 테이블에 적재</label><Help text="이미 있는 테이블에 행을 추가합니다. 컬럼 구조가 맞아야 합니다." /></div>
          <span className="mut">{ldMode === "existing" ? "위에서 대상 테이블을 선택하세요" : "기존 테이블에 적재하려면 선택하세요"}</span>
        </div>
        {ldMode === "existing" && table && ldColumns.length > 0 && <div className="grid">
          <div><button className="btn" disabled={!!busy} onClick={() => { setSuggestions(suggestMapping(table, ldColumns)); }}>DB 컬럼 기준 자동 매핑 추천</button></div>
          {suggestions && <><div className="table-scroll mapping-table"><table><thead><tr><th>원본 컬럼</th><th>추천 DB 컬럼</th><th>근거</th><th>비고</th></tr></thead><tbody>{suggestions.map(item => <tr key={item.columnId}><td>{item.source}</td><td>{item.target ?? "미확정"}</td><td>{{ exact: "이름 일치", alias: "사용자·업무 용어 매핑", similar: "유사한 이름", none: "—" }[item.reason]}</td><td>{item.note || "—"}</td></tr>)}</tbody></table></div>
            <p className="mut">추천은 확정이 아닙니다. 적용 후에도 컬럼 매핑 탭에서 확인·수정하세요. 미확정 컬럼은 현재 대상 컬럼명을 유지합니다.</p>
            <div><button className="btn p" disabled={!suggestions.some(item => item.target)} onClick={() => { applyMapping(Object.fromEntries(suggestions.filter(item => item.target).map(item => [item.columnId, item.target as string]))); setNotice("추천 매핑을 적용했습니다. 데이터를 다시 검증한 뒤 적재 점검을 실행하세요."); }}>추천 {suggestions.filter(item => item.target).length}개 적용</button></div></>}</div>}
        <div className="row"><button className="btn" disabled={!!busy || !server?.loadEnabled || !canExport || !ldV || !loadTarget || !loadSchema} data-tip="적재 전에 대상 스키마와 호환성을 점검합니다. 아직 아무것도 쓰지 않습니다." onClick={() => void run("대상 DB를 점검하고 있습니다", async () => {
          resetLoad(); setLdPlan(await (await request("/connections/load/check", loadBody())).json()); setLdRequestId(crypto.randomUUID());
        })}>대상·스키마 점검</button>{!canExport && <span className="mut">데이터 검증을 통과해야 적재할 수 있습니다</span>}</div>
        {ldPlan && <div className="grid">
          <p><strong>{ldPlan.dbms} · {ldPlan.schema}.{ldPlan.tableName} · {fmt(ldPlan.rows)}행</strong><br />{ldPlan.columns.join(", ")}</p>
          {ldPlan.warnings && ldPlan.warnings.length > 0 && <div className="alert warning" role="status"><ul>{ldPlan.warnings.map(w => <li key={w}>{w}</li>)}</ul></div>}
          {ldPlan.createsTable && <div className="alert warning" role="status"><strong>적재하면 아래 테이블이 새로 생성됩니다(CREATE만 실행)</strong>{ldPlan.ddl?.map(s => <pre key={s} className="ddl-preview">{s}</pre>)}</div>}
          <div className="check"><input id="ld-agree" type="checkbox" checked={ldConfirmed} disabled={!!busy || !!ldResult} onChange={e => setLdConfirmed(e.target.checked)} /><label htmlFor="ld-agree">대상과 행 수({fmt(ldPlan.rows)}행)를 확인했으며 실제 DB에 적재하는 것에 동의합니다</label><Help text="적재는 전체 성공 또는 전체 취소(롤백)로 처리되며, 같은 요청은 한 번만 적용됩니다." /></div>
          <div className="row"><button className="btn p" disabled={!!busy || !canExport || !ldConfirmed || !!ldResult} data-tip="검증을 통과한 표를 선택한 대상에 적재합니다." onClick={() => void run("DB에 데이터를 적재하고 있습니다", async () => {
            const result = await (await request("/connections/load", { ...loadBody(), requestId: ldRequestId, confirmed: true }, false, reviewed)).json();
            setLdResult(result); onHistoryChanged(); setNotice(`적재 완료: ${fmt(result.insertedRows)}행${result.replayed ? " · 이미 처리된 요청입니다. 추가 적재하지 않았습니다." : ""}`);
          })}>DB 적재</button></div>
          <small className="mut">요청 ID: {ldRequestId} · 통신 오류 시 같은 요청으로 재시도합니다. 중복 적재하지 않습니다.</small></div>}
        {ldResult && <div className="alert success" role="status">적재 완료: {fmt(ldResult.insertedRows)}행 · 요청 {ldResult.requestId}</div>}
      </div>
    </section>
    {busy && <div className="busy-indicator" role="status"><span className="spinner" />{busy}…</div>}
  </div>;
}
