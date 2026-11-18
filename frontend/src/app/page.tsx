"use client";

import { useEffect, useRef, useState } from "react";
import { ApiError, request } from "@/lib/api";
import type { Column, DataType, Issue, ParseResult, Table, ValidationResult } from "@/lib/types";

const types: [DataType, string][] = [["string", "문자열"], ["integer", "정수"], ["decimal", "소수"], ["date", "날짜"], ["datetime", "일시"], ["bool", "불리언"]];
const pageSize = 50;

export default function Home() {
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
  const [tableName, setTableName] = useState("TB_EMPLOYEE");
  const [page, setPage] = useState(0);
  const [tab, setTab] = useState<"data" | "mapping">("data");
  const [dragging, setDragging] = useState(false);
  const [loadTargets, setLoadTargets] = useState<string[] | null>(null);
  const [loadTableName, setLoadTableName] = useState("");
  const [loadPlan, setLoadPlan] = useState<{tableName: string; rows: number; columns: string[]} | null>(null);
  const [loadConfirmed, setLoadConfirmed] = useState(false);
  const [loadRequestId, setLoadRequestId] = useState("");
  const [loadResult, setLoadResult] = useState<{insertedRows: number; requestId: string; replayed: boolean} | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);
  useEffect(() => {
    if (!file) return;
    const url = URL.createObjectURL(file); setPreview(url);
    return () => URL.revokeObjectURL(url);
  }, [file]);

  function changed(next: Table) {
    resetLoad();
    setTable(next); setTables(previous => previous.map((t, index) => index === selectedTable ? next : t));
    setReviewed(false); setValidated(false); setIssues([]); setNotice(""); setError("");
  }
  function resetLoad() { setLoadPlan(null); setLoadConfirmed(false); setLoadRequestId(""); setLoadResult(null); }
  async function discoverLoadTarget() {
    await run("DB 적재 설정을 확인하고 있습니다", async () => {
      const target: {enabled: boolean; tables: string[]} = await (await request("/loads/target")).json();
      setLoadTargets(target.enabled ? target.tables : []); setLoadTableName(target.tables[0] || ""); resetLoad();
      if (!target.enabled) setNotice("서버에서 DB 적재를 활성화하고 허용 테이블을 등록해주세요.");
    });
  }
  async function checkLoad() {
    if (!table) return;
    setLoadPlan(null); setLoadConfirmed(false);
    await run("대상 DB 스키마를 검사하고 있습니다", async () => {
      const plan = await (await request("/loads/check", { table, tableName: loadTableName })).json();
      setLoadPlan(plan); setLoadConfirmed(false); setLoadResult(null); setLoadRequestId(current => current || crypto.randomUUID());
    });
  }
  async function loadDatabase() {
    if (!table || !loadPlan || !loadConfirmed || !loadRequestId) return;
    await run("MySQL에 데이터를 적재하고 있습니다", async () => {
      const result = await (await request("/loads/database", {table, tableName: loadTableName, requestId: loadRequestId, confirmed: true}, false, reviewed)).json();
      setLoadResult(result); setNotice(`${result.insertedRows}행의 DB 적재가 확인되었습니다.${result.replayed ? " 이미 처리된 요청으로 추가 적재하지 않았습니다." : ""}`);
    });
  }
  async function run(label: string, action: () => Promise<void>) {
    setBusy(label); setError(""); setNotice("");
    try { await action(); } catch (e) {
      setError(e instanceof Error ? e.message : "처리 중 오류가 발생했습니다.");
      if (e instanceof ApiError && e.issues.length) { setIssues(e.issues); setValidated(true); }
    } finally { setBusy(""); }
  }
  async function parse(selected: File, selectedSheet = "") {
    if (selected.size > 10 * 1024 * 1024) { setError("파일 최대 크기는 10MB입니다."); return; }
    if (!/\.(csv|xlsx|pdf|png|jpe?g)$/i.test(selected.name)) { setError("CSV, XLSX, PDF, PNG 또는 JPEG 파일을 선택해주세요."); return; }
    if (table && !window.confirm("현재 편집 내용을 새 파일 또는 시트로 바꿀까요?")) return;
    await run("파일을 분석하고 있습니다", async () => {
      const data = new FormData(); data.append("file", selected); data.append("encoding", encoding); data.append("delimiter", delimiter);
      data.append("page", String(pdfPage));
      data.append("externalConsent", String(externalConsent)); setExternalConsent(false);
      if (selectedSheet) data.append("sheet", selectedSheet);
      const result: ParseResult = await (await request("/files/parse", data, true)).json();
      if (!result.tables.length) throw new Error("추출된 표가 없습니다.");
      changed(result.tables[0]); setFile(selected); setSheets(result.sheets); setSheet(selectedSheet || result.sheets[0] || "");
      setTables(result.tables); setSelectedTable(0);
      setWarnings(result.warnings); setPage(0); setTab("data");
    });
  }
  function editCell(rowId: string, columnId: string, value: string) {
    if (!table) return;
    changed({ ...table, rows: table.rows.map(row => row.id === rowId
      ? { ...row, cells: { ...row.cells, [columnId]: { ...row.cells[columnId], value, edited: true } } } : row) });
  }
  function editColumn(id: string, update: Partial<Column>) {
    if (table) changed({ ...table, columns: table.columns.map(c => c.id === id ? { ...c, ...update } : c) });
  }
  async function validate() {
    if (!table) return;
    setLoadPlan(null); setLoadConfirmed(false); setLoadResult(null);
    await run("데이터를 검증하고 있습니다", async () => {
      const transformed: Table = await (await request("/tables/transform", table)).json();
      const result: ValidationResult = await (await request("/tables/validate", transformed)).json();
      setTable(transformed); setTables(previous => previous.map((t, index) => index === selectedTable ? transformed : t));
      setReviewed(false); setIssues(result.issues); setValidated(true);
      setNotice(result.valid ? "모든 검증을 통과했습니다. 파일을 내보낼 수 있습니다." : "검증 오류를 수정한 뒤 다시 검증해주세요.");
    });
  }
  async function download(format: "sql" | "excel") {
    if (!table) return;
    await run("파일을 생성하고 있습니다", async () => {
      const response = await request(`/exports/${format}`, format === "sql" ? { table, dbms, tableName } : table, false, reviewed);
      const url = URL.createObjectURL(await response.blob());
      const link = document.createElement("a"); link.href = url; link.download = `tableflow.${format === "sql" ? "sql" : "xlsx"}`;
      document.body.appendChild(link); link.click(); link.remove(); setTimeout(() => URL.revokeObjectURL(url), 1000);
      setNotice("파일 다운로드를 시작했습니다.");
    });
  }
  const visible = table?.rows.slice(page * pageSize, (page + 1) * pageSize) || [];
  const modified = table?.rows.reduce((sum, row) => sum + Object.values(row.cells).filter(c => c.edited).length, 0) || 0;
  const issueMap = new Set(issues.map(i => `${i.rowId}:${i.columnId}`));
  const isOcr = table?.source.extraction_method.includes("ocr") || false;
  const canExport = !!table && !busy && validated && !issues.length && (!isOcr || reviewed);
  return <div className="app-shell">
    <aside className="sidebar">
      <a href="/" className="brand"><span className="brand-icon">▦</span>TableFlow<span className="beta">MVP</span></a>
      <div className="sidebar-caption">WORKSPACE</div>
      <div className="nav-active"><span>▤</span> 데이터 변환 <span className="nav-dot" /></div>
      <div className="sidebar-bottom"><span className="local-dot" /> 로컬 워크스페이스<p>새로고침 시 화면 작업은 초기화됩니다.</p></div>
    </aside>
    <main>
      <header className="topbar"><span>워크스페이스 <span className="slash">/</span> <strong>새 변환 작업</strong></span><a href="https://github.com/orca305/TableFlow" target="_blank" rel="noreferrer">GitHub ↗</a></header>
      <div className="content">
        <div className="page-heading"><div><div className="eyebrow">YOUR DATA, READY FOR WHAT’S NEXT</div><h1>데이터를 다음 단계로.</h1><p>파일을 불러오고, 다듬고, 필요한 형식으로 내보내세요.</p></div><span className="engine-badge">Java ETL Engine</span></div>
        <ol className="steps"><li className="current"><b>1</b> 파일 불러오기</li><li className={table ? "current" : ""}><b>2</b> 검토 · 컬럼 매핑</li><li className={validated && !issues.length ? "current" : ""}><b>3</b> 검증 · 내보내기</li></ol>
        {error && <div className="alert error" role="alert">{error}</div>}
        {notice && <div className="alert success" role="status">{notice}</div>}
        {warnings.map((warning, i) => <div className="alert warning" key={i}>{warning}</div>)}
        <section className="panel upload-panel" aria-label="파일 업로드">
          <div className="section-title"><h2><span className="section-number">01</span> 소스 파일</h2><div className="sample-links"><a href="/samples/employees.csv" download>샘플 CSV</a><a href="/samples/table-text.pdf" download>PDF</a><a href="/samples/table-ko.png" download>이미지 ↓</a></div></div>
          <div className={`dropzone ${dragging ? "dragging" : ""}`} onDragOver={e => { e.preventDefault(); if (!busy) setDragging(true); }} onDragLeave={() => setDragging(false)} onDrop={e => { e.preventDefault(); setDragging(false); if (!busy && e.dataTransfer.files[0]) void parse(e.dataTransfer.files[0]); }}>
            <div className="upload-icon">↥</div><div><h3>{file ? file.name : "파일을 여기에 놓아주세요"}</h3><p>Excel · CSV · PDF · PNG/JPEG · 최대 10MB</p></div>
            <button className="secondary" disabled={!!busy} onClick={() => fileInput.current?.click()}>파일 선택</button>
            <input ref={fileInput} hidden type="file" accept=".csv,.xlsx,.pdf,.png,.jpg,.jpeg" aria-label="파일 선택" onChange={e => { const f = e.target.files?.[0]; if (f) void parse(f); e.target.value = ""; }} />
          </div>
          <div className="parse-options"><label>CSV 인코딩<select value={encoding} disabled={!!busy} onChange={e => setEncoding(e.target.value)}><option>UTF-8</option><option>MS949</option><option>EUC-KR</option></select></label>
            <label>구분자<select value={delimiter} disabled={!!busy} onChange={e => setDelimiter(e.target.value)}><option value=",">쉼표 (,)</option><option value=";">세미콜론 (;)</option><option value={"\t"}>탭</option><option value="|">파이프 (|)</option></select></label>
            {sheets.length > 0 && <label>Excel 시트<select disabled={!!busy} value={sheet} onChange={e => { if (file) void parse(file, e.target.value); }}>{sheets.map(s => <option key={s}>{s}</option>)}</select></label>}
            <label>PDF 페이지<input type="number" aria-label="PDF 페이지" min={0} max={20} value={pdfPage} disabled={!!busy} onChange={e => setPdfPage(Math.min(20, Math.max(0, Number(e.target.value))))} /><span>0 = 전체 (최대 20)</span></label>
            {file?.name.toLowerCase().endsWith(".pdf") && <button className="text-button" disabled={!!busy} onClick={() => void parse(file)}>페이지 다시 읽기 ↻</button>}
            {file?.name.toLowerCase().endsWith(".csv") && <button className="text-button" disabled={!!busy} onClick={() => void parse(file)}>설정으로 다시 읽기 ↻</button>}
            <span className="privacy-note">원본 파일은 서버에 영구 저장하지 않습니다.</span>
          </div>
          <details className="ocr-options"><summary>OCR 전송 설정</summary><label><input type="checkbox" checked={externalConsent} disabled={!!busy} onChange={e => setExternalConsent(e.target.checked)} /> 이번 파일의 외부 OCR 전송에 동의합니다</label><p>로컬 OCR은 외부로 전송하지 않습니다. 외부 공급자는 서버의 명시적 허용과 파일별 동의가 모두 필요합니다.</p></details>
        </section>
        {table && <div className="source-info">
          <span>추출: <strong>{table.source.extraction_method === "local_ocr" ? "로컬 OCR" : table.source.extraction_method === "mock_ocr" ? "MOCK · 고정 테스트 데이터" : table.source.extraction_method === "external_ocr" ? "외부 OCR" : table.source.extraction_method === "pdf_text" ? "PDF 텍스트" : "파일 파서"}</strong>{table.source.page ? ` · ${table.source.page}페이지` : ""}</span>
          {tables.length > 1 && <label>추출된 표<select aria-label="추출된 표" disabled={!!busy} value={selectedTable} onChange={e => { resetLoad(); const index = Number(e.target.value); setSelectedTable(index); setTable(tables[index]); setValidated(false); setReviewed(false); setIssues([]); setPage(0); setError(""); setNotice(""); }}>{tables.map((t, index) => <option key={t.table_id} value={index}>{t.name}</option>)}</select></label>}
          {preview && <a href={preview} target="_blank" rel="noreferrer">원본 파일 열기 ↗</a>}
        </div>}
        {isOcr && <section className="panel ocr-review"><h2>OCR 결과 검토</h2><p>신뢰도가 낮은 셀을 강조했습니다. 원본과 대조하고 필요한 값을 수정한 뒤 검증해주세요.</p>
          {preview && file?.type.startsWith("image/") && <img src={preview} alt="OCR 원본 표" className="source-image" />}
          <label><input type="checkbox" checked={reviewed} disabled={!!busy || !validated || !!issues.length} onChange={e => setReviewed(e.target.checked)} /> 검증된 OCR 결과를 원본과 대조하여 검토했습니다</label></section>}
        <section className="panel" aria-label="데이터 편집">
          <div className="section-title"><h2><span className="section-number">02</span> 데이터 워크벤치</h2><div className="table-stats"><span>{table?.rows.length.toLocaleString() || "0"} 행</span><span>{table?.columns.length || "0"} 열</span>{modified > 0 && <span className="edited-count">{modified}개 수정</span>}</div></div>
          <div className="tabs" role="tablist" aria-label="워크벤치 보기"><button role="tab" aria-selected={tab === "data"} onClick={() => setTab("data")} className={tab === "data" ? "selected" : ""}>데이터 미리보기</button><button role="tab" aria-selected={tab === "mapping"} onClick={() => setTab("mapping")} className={tab === "mapping" ? "selected" : ""}>컬럼 매핑 <span>{table?.columns.length || 0}</span></button></div>
          {!table ? <div className="empty-state"><div className="empty-grid">▦</div><h3>데이터가 들어올 자리입니다</h3><p>파일을 업로드하면 여기서 데이터를 검토하고 수정할 수 있어요.</p><span>CSV / XLSX → 검토 → 검증 → SQL / XLSX</span></div> : tab === "data" ? <>
            <div className="table-scroll"><table className="data-table"><thead><tr><th className="row-number">#</th>{table.columns.map(c => <th key={c.id} className={!c.included ? "excluded" : ""}>{c.name}<small>{c.target_name} · {c.data_type}</small></th>)}</tr></thead><tbody>{visible.map((row, index) => <tr key={row.id}><td className="row-number">{page * pageSize + index + 1}</td>{table.columns.map(c => {
              const cell = row.cells[c.id]; const invalid = issueMap.has(`${row.id}:${c.id}`) || issueMap.has(`${row.id}:null`);
              return <td key={c.id} className={`${invalid ? "invalid" : ""} ${cell?.edited ? "edited" : ""} ${cell?.confidence != null && cell.confidence < 0.85 ? "low-confidence" : ""} ${!c.included ? "excluded" : ""}`}><input aria-label={`${page * pageSize + index + 1}행 ${c.name}`} aria-invalid={invalid} disabled={!!busy} value={cell?.value ?? ""} title={`원본: ${cell?.raw ?? "(빈 값)"}`} onChange={e => editCell(row.id, c.id, e.target.value)} />{cell?.confidence != null && <small className="confidence">신뢰도 {Math.round(cell.confidence * 100)}%</small>}</td>;
            })}</tr>)}</tbody></table></div>
            <div className="table-footer"><span>셀을 클릭해 수정 · 셀 위에 마우스를 올려 원본 확인</span><div><button disabled={page === 0 || !!busy} onClick={() => setPage(page - 1)}>← 이전</button><span>{page + 1} / {Math.max(1, Math.ceil(table.rows.length / pageSize))}</span><button disabled={(page + 1) * pageSize >= table.rows.length || !!busy} onClick={() => setPage(page + 1)}>다음 →</button></div></div>
          </> : <div className="table-scroll"><table className="mapping-table"><thead><tr><th>포함</th><th>원본 컬럼</th><th>대상 컬럼명</th><th>자료형</th><th>필수</th><th>최대 길이</th><th>정밀도</th><th>소수 자릿수</th></tr></thead><tbody>{table.columns.map(c => <tr key={c.id} className={!c.included ? "excluded" : ""}>
            <td><input aria-label={`${c.name} 포함`} type="checkbox" disabled={!!busy} checked={c.included} onChange={e => editColumn(c.id, { included: e.target.checked })} /></td><td>{c.name}</td>
            <td><input aria-label={`${c.name} 대상 컬럼명`} disabled={!!busy} maxLength={64} value={c.target_name} onChange={e => editColumn(c.id, { target_name: e.target.value })} /></td>
            <td><select aria-label={`${c.name} 자료형`} disabled={!!busy} value={c.data_type} onChange={e => editColumn(c.id, { data_type: e.target.value as DataType })}>{types.map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></td>
            <td><input aria-label={`${c.name} 필수`} disabled={!!busy} type="checkbox" checked={!c.nullable} onChange={e => editColumn(c.id, { nullable: !e.target.checked })} /></td>
            {(["max_length", "precision", "scale"] as const).map((key, i) => <td key={key}><input type="number" min={key === "scale" ? 0 : 1} max={key === "max_length" ? 32767 : 38} aria-label={`${c.name} ${["최대 길이", "정밀도", "소수 자릿수"][i]}`} disabled={!!busy} placeholder="—" value={c[key] ?? ""} onChange={e => editColumn(c.id, { [key]: e.target.value === "" ? null : Number(e.target.value) })} /></td>)}
          </tr>)}</tbody></table><p className="mapping-help">자료형을 추정하며 선행 0이 있는 값은 문자열로 유지합니다. 필요한 자료형으로 수정하세요. 빈 값·공백만 있는 값은 NULL로 처리합니다.</p></div>}
        </section>
        {issues.length > 0 && <section className="panel issues"><h2>검증 오류 {issues.length}개 <small>최대 1,000개 표시</small></h2><ul>{issues.slice(0, 100).map((issue, i) => <li key={i}><button onClick={() => { const index = table?.rows.findIndex(r => r.id === issue.rowId) ?? -1; if (index >= 0) { setPage(Math.floor(index / pageSize)); setTab("data"); } else setTab("mapping"); }}>{issue.rowId ? `${(table?.rows.findIndex(r => r.id === issue.rowId) ?? 0) + 1}행` : "컬럼 설정"} {table?.columns.find(c => c.id === issue.columnId)?.name}</button><span>{issue.message}</span></li>)}</ul>{issues.length > 100 && <p>상위 100개 오류를 표시합니다. 수정 후 다시 검증해주세요.</p>}</section>}
        <section className="panel export-panel"><div className="section-title"><h2><span className="section-number">03</span> 검증 및 내보내기</h2><span className={`status-badge ${validated && !issues.length ? "ready" : ""}`}>{validated ? issues.length ? "오류 수정 필요" : "검증 완료" : "검증 대기"}</span></div><div className="export-controls">
          <label>대상 DBMS<select value={dbms} disabled={!!busy} onChange={e => setDbms(e.target.value)}><option value="oracle">Oracle</option><option value="mysql">MySQL</option></select></label>
          <label className="target-table">대상 테이블명<input value={tableName} maxLength={64} disabled={!!busy} onChange={e => setTableName(e.target.value)} /></label>
          <button className="secondary" disabled={!table || !!busy} onClick={() => void validate()}>데이터 검증</button>
          <button className="secondary" disabled={!canExport} onClick={() => void download("excel")}>Excel ↓</button>
          <button className="primary" disabled={!canExport} onClick={() => void download("sql")}>SQL 내보내기 ↓</button>
        </div><p className="export-note">파일 다운로드는 DB를 변경하지 않습니다. 실제 적재는 아래에서 별도로 확인 후 실행하세요.</p></section>
        <section className="panel load-panel" aria-label="MySQL DB 적재">
          <div className="section-title"><h2><span className="section-number">04</span> MySQL DB 적재</h2><button className="secondary" disabled={!!busy} onClick={() => void discoverLoadTarget()}>적재 대상 확인</button></div>
          <div className="load-content"><p>서버에 등록된 MySQL 테이블에 INSERT합니다. 한 요청은 전체 성공하거나 전체 취소되며, 테이블 생성·기존 행 수정은 하지 않습니다.</p>
            {loadTargets !== null && (loadTargets.length ? <>
              <div className="load-controls"><label>DB 대상 테이블<select aria-label="DB 대상 테이블" value={loadTableName} disabled={!!busy} onChange={e => {setLoadTableName(e.target.value); resetLoad();}}>{loadTargets.map(name => <option key={name}>{name}</option>)}</select></label>
                <button className="secondary" disabled={!canExport} onClick={() => void checkLoad()}>연결·스키마 검사</button></div>
              {loadPlan && <div className="load-confirm"><p>{loadPlan.tableName} · {loadPlan.rows.toLocaleString()}행 · {loadPlan.columns.join(", ")}</p>
                <label><input type="checkbox" checked={loadConfirmed} disabled={!!busy || !!loadResult} onChange={e => setLoadConfirmed(e.target.checked)} /> 대상과 행 수를 확인했으며 실제 DB 적재에 동의합니다</label>
                <button className="primary" disabled={!canExport || !loadConfirmed || !!loadResult} onClick={() => void loadDatabase()}>MySQL에 적재</button>
                <small>요청 ID: {loadRequestId} · 통신 오류 시 같은 요청으로 재시도하며 중복 적재하지 않습니다.</small>
              </div>}
              {loadResult && <div className="alert success" role="status">적재 완료: {loadResult.insertedRows}행 · 요청 {loadResult.requestId}</div>}
            </> : <p>현재 서버에서 DB 적재가 비활성화되어 있습니다. 실행 안내의 DB 설정을 확인하세요.</p>)}
          </div>
        </section>
        <footer>TableFlow <span>Extract. Refine. Flow.</span><span>Spring Boot + Next.js</span></footer>
      </div>
    </main>{busy && <div className="busy-indicator" role="status"><span className="spinner" />{busy}…</div>}
  </div>;
}
