"use client";

import { useEffect, useId, useMemo, useRef, useState } from "react";
import { request } from "@/lib/api";
import {
  DBMS_LABEL, PORTS, blankConnection, connectionName, deleteSavedConnection, saveConnection,
  type Connection, type Dbms, type SavedConnection, type ServerInfo,
} from "@/lib/connections";
import { Help } from "./ui";

/** 연결 확인에 성공한 접속 정보. 이후 요청(스키마·테이블 조회, 이관, 적재)에 그대로 사용한다. */
export type Verified = { connection: Connection; label: string };
type Status = { kind: "idle" | "busy" | "ok" | "bad" | "warn"; text: string };

type Props = {
  role: "원본" | "대상"; server: ServerInfo | null; saved: SavedConnection[]; onSavedChanged: () => void;
  onVerified: (verified: Verified | null) => void; disabled?: boolean;
};

const NEW = "new";

/**
 * 저장된 연결을 고르거나 신규 연결을 입력한다. 연결 정보가 항상 보이며 "연결 확인"으로 접속을 검증한다.
 * 비밀번호는 사용자가 "비밀번호 저장"을 선택한 경우에만 서버(로컬)에 암호화해서 저장하고, 브라우저로는 다시 내려오지 않는다.
 */
export default function ConnectionPicker({ role, server, saved, onSavedChanged, onVerified, disabled = false }: Props) {
  const [selected, setSelected] = useState<string>(NEW);
  const [conn, setConn] = useState<Connection>(blankConnection("postgresql"));
  const [name, setName] = useState("");
  const [savePassword, setSavePassword] = useState(false);
  const [status, setStatus] = useState<Status>({ kind: "idle", text: "신규 연결 · 정보를 입력하고 연결 확인을 누르세요" });
  const [confirmDelete, setConfirmDelete] = useState(false);
  const current = useMemo(() => saved.find(item => item.id === selected) ?? null, [saved, selected]);
  const canSavePassword = !!server?.savePasswordEnabled;
  const autoTested = useRef("");
  const uid = useId(); // 화면에 같은 역할의 선택기가 여러 개 있어도 label과 입력란이 섞이지 않도록 고유 id를 쓴다

  /** 저장된 연결과 필드가 달라졌는지(달라졌다면 저장된 비밀번호는 쓸 수 없다). */
  const dirty = !!current && (current.dbms !== conn.dbms || current.host !== conn.host || current.port !== conn.port
    || current.database !== conn.database || current.username !== conn.username || current.tls !== conn.tls);

  function load(item: SavedConnection | null) {
    setConfirmDelete(false); onVerified(null);
    if (!item) { setConn(blankConnection("postgresql")); setName(""); setSavePassword(false); setStatus({ kind: "idle", text: "신규 연결 · 정보를 입력하고 연결 확인을 누르세요" }); return; }
    setConn({ dbms: item.dbms, host: item.host, port: item.port, database: item.database, username: item.username, password: "", tls: item.tls });
    setName(item.name); setSavePassword(item.passwordSaved);
    setStatus(item.passwordSaved ? { kind: "idle", text: "저장된 비밀번호를 확인하는 중입니다" } : { kind: "warn", text: "비밀번호를 입력하고 연결 확인을 누르세요" });
  }
  function choose(id: string) {
    setSelected(id); const item = saved.find(entry => entry.id === id) ?? null; autoTested.current = ""; load(item);
  }
  function edit(patch: Partial<Connection>) { setConn(c => ({ ...c, ...patch })); onVerified(null); setStatus({ kind: "idle", text: "연결 정보가 바뀌었습니다. 연결 확인을 다시 누르세요" }); }

  async function test(auto = false) {
    if (!conn.host.trim() || !conn.database.trim() || !conn.username.trim()) { setStatus({ kind: "warn", text: "주소, 데이터베이스, 사용자를 입력하세요" }); return; }
    const useSaved = !!current && current.passwordSaved && !dirty && conn.password === "";
    if (!conn.password && !useSaved) { setStatus({ kind: "warn", text: "비밀번호를 입력하세요. 비밀번호를 저장하지 않은 연결은 확인할 때마다 필요합니다" }); return; }
    const request_: Connection = useSaved ? { ...conn, password: "", savedId: current!.id } : { ...conn };
    setStatus({ kind: "busy", text: auto ? "저장된 비밀번호로 연결을 확인하는 중입니다" : "연결을 확인하는 중입니다" });
    try {
      const info: { product: string; serverVersion: string } = await (await request("/connections/test", request_)).json();
      let verified = request_; let label = current?.name || connectionName(conn);
      if (server?.savedEnabled) {
        const keepOld = !!current && current.passwordSaved && savePassword && conn.password === "" && !dirty;
        const wantSave = !auto && (!current || dirty || savePassword !== current.passwordSaved || (savePassword && conn.password !== ""));
        if (wantSave || !current) {
          const saveName = (name.trim() || current?.name || connectionName(conn)).slice(0, 60);
          const result = await saveConnection(saveName, { ...conn, password: keepOld ? "" : conn.password, savedId: undefined }, savePassword && canSavePassword);
          label = result.name; setSelected(result.id); setName(result.name); setSavePassword(result.passwordSaved);
          if (result.passwordSaved) { verified = { ...conn, password: "", savedId: result.id }; setConn(c => ({ ...c, password: "" })); } // 저장한 뒤에는 서버의 저장된 비밀번호를 쓰므로 입력란을 비운다(다시 확인해도 savedId로 연결)
          onSavedChanged();
        }
      }
      setStatus({ kind: "ok", text: `연결 성공 · ${info.product} ${info.serverVersion}` });
      onVerified({ connection: verified, label });
    } catch (reason) {
      onVerified(null); setStatus({ kind: "bad", text: reason instanceof Error ? reason.message : "연결하지 못했습니다" });
    }
  }
  // 저장된 비밀번호가 있는 연결을 고르면 바로 연결을 확인한다.
  useEffect(() => {
    if (!current?.passwordSaved || dirty || autoTested.current === current.id || !server?.enabled) return;
    autoTested.current = current.id; void test(true);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [current?.id, current?.passwordSaved, server?.enabled]);

  async function remove() {
    if (!current) return;
    try { await deleteSavedConnection(current.id); onSavedChanged(); setSelected(NEW); load(null); }
    catch (reason) { setStatus({ kind: "bad", text: reason instanceof Error ? reason.message : "삭제하지 못했습니다" }); }
  }

  const pillClass = status.kind === "ok" ? "ok" : status.kind === "bad" ? "bad" : status.kind === "warn" ? "warn" : "";
  const dbmsOptions = (Object.keys(PORTS) as Dbms[]);
  const locked = disabled || status.kind === "busy";
  return <div className="grid">
    <label className="f">연결<Help text={role === "원본" ? "접속할 DB를 선택합니다. 목록에 없으면 '신규 연결'을 선택하세요." : "데이터를 넣을 DB를 선택합니다. 목록에 없으면 '신규 연결'을 선택하세요."} />
      <select aria-label={`${role} 연결`} value={selected} disabled={locked} onChange={e => choose(e.target.value)}>
        {saved.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}<option value={NEW}>신규 연결</option>
      </select></label>
    <div className="cd">
      <div className="cd-h"><b>연결 정보</b><span className={`pill ${pillClass}`} role="status" aria-label={`${role} 연결 상태`}>{status.text}</span>
        {current && !confirmDelete && <button type="button" className="btn sm" disabled={locked} onClick={() => setConfirmDelete(true)}>연결 삭제</button>}
        {current && confirmDelete && <><span className="mut">저장된 연결과 비밀번호가 삭제됩니다.</span><button type="button" className="btn sm" onClick={() => void remove()}>삭제 확인</button><button type="button" className="btn sm" onClick={() => setConfirmDelete(false)}>취소</button></>}
      </div>
      <div className="form form3">
        <label className="f">종류<select aria-label={`${role} 종류`} value={conn.dbms} disabled={locked} onChange={e => { const dbms = e.target.value as Dbms; edit({ dbms, port: PORTS[dbms] }); }}>{dbmsOptions.map(d => <option key={d} value={d}>{DBMS_LABEL[d]}</option>)}</select></label>
        <label className="f">주소<Help text="DB 서버의 호스트 이름 또는 IP입니다. 로컬이 아닌 주소는 TLS 보안 연결이 필요합니다." /><input aria-label={`${role} 주소`} value={conn.host} maxLength={253} disabled={locked} onChange={e => edit({ host: e.target.value })} /></label>
        <label className="f">포트<Help text="DB 접속 포트입니다. 기본값은 Oracle 1521, PostgreSQL 5432, MySQL·MariaDB 3306입니다." /><input aria-label={`${role} 포트`} type="number" min={1} max={65535} value={conn.port} disabled={locked} onChange={e => edit({ port: Number(e.target.value) })} /></label>
        <label className="f">{conn.dbms === "oracle" ? "서비스명" : "데이터베이스"}<Help text="접속할 DB 이름입니다. Oracle은 서비스명을 입력합니다." /><input aria-label={`${role} 데이터베이스`} value={conn.database} maxLength={128} disabled={locked} onChange={e => edit({ database: e.target.value })} /></label>
        <label className="f">사용자<input aria-label={`${role} 사용자`} autoComplete="off" value={conn.username} maxLength={128} disabled={locked} onChange={e => edit({ username: e.target.value })} /></label>
        <label className="f">비밀번호<Help text="연결 확인에 사용합니다. '비밀번호 저장'을 선택하지 않으면 서버에도 브라우저에도 저장하지 않습니다." />
          <input aria-label={`${role} 비밀번호`} type="password" autoComplete="new-password" maxLength={1024} value={conn.password} disabled={locked}
            placeholder={current?.passwordSaved && !dirty ? "저장된 비밀번호 사용" : "저장되지 않습니다"} onChange={e => { setConn(c => ({ ...c, password: e.target.value })); onVerified(null); }} /></label>
      </div>
      <div className="row">
        <span className="check"><input id={`${uid}-tls`} type="checkbox" checked={conn.tls} disabled={locked} onChange={e => edit({ tls: e.target.checked })} /><label htmlFor={`${uid}-tls`}>TLS 보안 연결 사용</label><Help text="인증서와 호스트 이름을 검증하는 암호화 연결입니다. 로컬이 아닌 DB에는 필수입니다." /></span>
        <span className="check"><input id={`${uid}-savepw`} type="checkbox" checked={savePassword} disabled={locked || !canSavePassword} onChange={e => setSavePassword(e.target.checked)} />
          <label htmlFor={`${uid}-savepw`}>비밀번호 저장</label>
          <Help text={canSavePassword ? "선택하면 비밀번호를 암호화(AES-256)해서 이 컴퓨터의 TableFlow 서버에만 저장합니다. 브라우저로 다시 내려보내지 않으며, 이후에는 입력 없이 연결합니다." : "서버에서 비밀번호 저장이 비활성화되어 있습니다(TABLEFLOW_CONNECTIONS_SAVE_PASSWORD)."} /></span>
      </div>
      {!current && server?.savedEnabled && <label className="f" style={{ maxWidth: 360 }}>연결 이름(선택)<Help text="비워 두면 '종류 · 주소:포트/데이터베이스'로 저장합니다." /><input aria-label={`${role} 연결 이름`} value={name} maxLength={60} disabled={locked} onChange={e => setName(e.target.value)} placeholder={connectionName(conn)} /></label>}
      <div className="row"><button type="button" className="btn" disabled={locked || !server?.enabled} data-tip="입력한 정보로 DB에 연결할 수 있는지 확인합니다. 성공하면 연결 정보가 저장됩니다." onClick={() => void test(false)}>연결 확인</button>
        <span className="mut">연결 정보는 이 컴퓨터의 서버에만 저장됩니다. 로컬이 아닌 주소는 TLS 보안 연결이 필요합니다.</span></div>
    </div>
  </div>;
}
