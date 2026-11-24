"use client";

import { useRef, useState } from "react";
import { request } from "@/lib/api";
import { DBMS_LABEL, type Dbms, type InstalledDriver } from "@/lib/connections";

export default function DriverSettings() {
  const [adminKey, setAdminKey] = useState("");
  const [dbms, setDbms] = useState<Dbms>("postgresql");
  const [version, setVersion] = useState("");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const [items, setItems] = useState<InstalledDriver[]>([]);
  const file = useRef<HTMLInputElement>(null);

  async function submit(register: boolean) {
    setBusy(true); setMessage("");
    try {
      const headers = { "X-TableFlow-Admin-Key": adminKey };
      if (register) {
        const jar = file.current?.files?.[0];
        if (!jar || !jar.name.toLowerCase().endsWith(".jar") || jar.size > 10 * 1024 * 1024)
          throw new Error("10MB 이하의 JDBC JAR 파일을 선택해주세요.");
        const form = new FormData(); form.append("dbms", dbms); form.append("version", version); form.append("file", jar);
        await request("/driver-catalog", form, true, false, headers);
        window.dispatchEvent(new Event("tableflow-drivers-changed"));
        if (file.current) file.current.value = "";
      }
      const result = await (await request("/driver-catalog", undefined, false, false, headers)).json();
      setItems(result.items); setMessage(register ? "드라이버가 등록되었습니다. 연결 화면을 다시 열어 해당 버전을 선택하세요." : "등록된 드라이버 목록을 조회했습니다.");
    } catch (error) { setMessage(error instanceof Error ? error.message : "드라이버 관리 요청에 실패했습니다."); }
    finally { setBusy(false); setAdminKey(""); }
  }

  return <section className="panel" aria-label="JDBC 드라이버 관리">
    <h2>JDBC 드라이버 관리</h2>
    <p className="mut">서버 로컬 관리자 전용입니다. 신뢰할 수 있는 공급자의 JAR만 등록하세요. 관리자 키는 브라우저에 저장하지 않습니다.</p>
    <div className="form form3">
      <label className="f">관리자 키<input type="password" autoComplete="off" value={adminKey} disabled={busy} onChange={e => setAdminKey(e.target.value)} /></label>
      <label className="f">DBMS<select value={dbms} disabled={busy} onChange={e => setDbms(e.target.value as Dbms)}>{Object.entries(DBMS_LABEL).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select></label>
      <label className="f">드라이버 버전<input value={version} maxLength={64} disabled={busy} onChange={e => setVersion(e.target.value)} placeholder="예: 42.7.14" /></label>
    </div>
    <label className="f">JDBC JAR 파일<input ref={file} type="file" accept=".jar" disabled={busy} /></label>
    <div className="row">
      <button className="btn" disabled={busy || adminKey.length < 32 || !version} onClick={() => void submit(true)}>드라이버 등록</button>
      <button className="btn" disabled={busy || adminKey.length < 32} onClick={() => void submit(false)}>목록 조회</button>
    </div>
    {message && <p role="status">{message}</p>}
    {items.length > 0 && <div className="table-scroll"><table><thead><tr><th>DBMS</th><th>버전</th><th>SHA-256</th></tr></thead><tbody>
      {items.map(item => <tr key={item.id}><td>{DBMS_LABEL[item.dbms]}</td><td>{item.version}</td><td style={{ overflowWrap: "anywhere" }}>{item.sha256}</td></tr>)}
    </tbody></table></div>}
    <p className="mut">등록한 버전은 연결·작업에서 참조하므로 현재는 삭제할 수 없습니다.</p>
  </section>;
}
