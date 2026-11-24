"use client";

import { useEffect, useState } from "react";
import { DBMS_LABEL, deleteSavedConnection, type SavedConnection, type ServerInfo } from "@/lib/connections";
import DriverSettings from "./DriverSettings";
import { Help } from "./ui";

/**
 * DB 조회·적재·연결 저장은 서버 환경변수로 켜고 끄는 기능이라 이 화면에서 바꿀 수 없다(안전을 위해 기본값은 비활성화).
 * 여기서는 현재 상태와 활성화 방법을 보여주고, 브라우저별 기본 분할 이관 단위와 저장된 연결 관리를 제공한다.
 */
export default function SettingsPage({ server, error, saved, reloadSaved, visible = true }: { server: ServerInfo | null; error: string; saved: SavedConnection[]; reloadSaved: () => void; visible?: boolean }) {
  const [chunk, setChunk] = useState("");
  const [message, setMessage] = useState("");
  useEffect(() => {
    let value = server?.defaultChunkSize ?? 5000; try { const s = Number(localStorage.getItem("tf-chunk")); if (s >= 100 && s <= 10000) value = s; } catch { /* 저장소를 못 쓰면 서버 기본값 */ }
    setChunk(String(value));
  }, [server]);
  function changeChunk(value: string) {
    setChunk(value); const n = Number(value);
    try { localStorage.setItem("tf-chunk", String(n)); setMessage("분할 이관 단위를 저장했습니다. 다음 이관부터 기본값으로 쓰입니다."); } catch { setMessage("이 브라우저에서는 설정을 저장할 수 없습니다."); }
  }
  const state = (on: boolean | undefined, hint: string) => on === undefined ? <span className="pill">확인 중</span> : on ? <span className="pill ok">활성화</span> : <span className="pill warn" data-tip={hint}>비활성화</span>;
  return <div className="grid">
    <div className="page-head"><h1>설정</h1><p className="sub">기본값은 안전하게 비활성화되어 있습니다. 필요한 기능만 서버에서 활성화하세요.</p></div>
    {error && <div role="alert" className="alert error">{error}</div>}
    <section className="panel" aria-label="서버 기능"><h2>서버 기능<span className="hs">서버 환경변수로 설정하며, 변경 후 백엔드를 다시 시작해야 합니다</span></h2>
      <div className="sw"><div><b>데이터베이스 조회</b><p>DB에 연결하여 테이블과 데이터를 조회합니다 (TABLEFLOW_JDBC_ENABLED=true)</p></div><div className="val">{state(server?.enabled, "TABLEFLOW_JDBC_ENABLED=true 로 백엔드를 시작하세요")}</div></div>
      <div className="sw"><div><b>데이터베이스 적재</b><p>대상 DB에 데이터를 실제로 적재합니다. 실행 전에 한 번 더 동의를 받습니다 (TABLEFLOW_JDBC_LOAD_ENABLED=true)</p></div><div className="val">{state(server?.loadEnabled, "TABLEFLOW_JDBC_LOAD_ENABLED=true 로 백엔드를 시작하세요")}</div></div>
      <div className="sw"><div><b>연결 저장</b><p>연결 정보(주소·포트·DB·사용자)를 이 컴퓨터의 서버에 저장합니다 (TABLEFLOW_CONNECTIONS_ENABLED)</p></div><div className="val">{state(server?.savedEnabled, "TABLEFLOW_CONNECTIONS_ENABLED=true")}</div></div>
      <div className="sw"><div><b>비밀번호 저장</b><p>사용자가 선택한 연결의 비밀번호만 AES-256으로 암호화해 서버에 저장합니다. 브라우저로는 내려보내지 않습니다 (TABLEFLOW_CONNECTIONS_SAVE_PASSWORD)</p></div><div className="val">{state(server?.savePasswordEnabled, "TABLEFLOW_CONNECTIONS_SAVE_PASSWORD=true")}</div></div>
    </section>
    {visible && <DriverSettings />}
    <section className="panel" aria-label="분할 이관 단위"><h2>분할 이관 단위<Help text="대용량 테이블을 나누어 이관하는 단위입니다. 작을수록 중단 시 재작업 범위가 줄어듭니다. 이 브라우저의 기본값이며 이관마다 바꿀 수 있습니다." /></h2>
      <div className="row"><select aria-label="기본 분할 이관 단위" style={{ maxWidth: 160 }} value={chunk} onChange={e => changeChunk(e.target.value)}>
        {[...new Set([1000, 2500, 5000, 10000, Number(chunk)].filter(Boolean))].sort((a, b) => a - b).map(n => <option key={n} value={n}>{n.toLocaleString("ko-KR")}행</option>)}</select>
        <span className="mut">서버 기본값: {(server?.defaultChunkSize ?? 0).toLocaleString("ko-KR")}행 · 10,000행을 넘는 이관에만 적용됩니다</span></div>
      {message && <p className="mut" role="status" style={{ marginTop: 8 }}>{message}</p>}
    </section>
    <section className="panel" aria-label="저장된 연결"><h2>저장된 연결<span className="hs">비밀번호는 목록에 표시되지 않으며 저장 여부만 보여줍니다</span></h2>
      {saved.length === 0 ? <p className="mut">저장된 연결이 없습니다. DB 이관 또는 파일 변환에서 연결을 확인하면 자동으로 저장됩니다.</p> :
        <div className="table-scroll"><table><thead><tr><th>이름</th><th>종류</th><th>주소</th><th>사용자</th><th>비밀번호</th><th></th></tr></thead><tbody>
          {saved.map(item => <tr key={item.id}><td>{item.name}</td><td>{DBMS_LABEL[item.dbms]}</td><td>{item.host}:{item.port}/{item.database}</td><td>{item.username}</td>
            <td><span className={`pill ${item.passwordSaved ? "ok" : ""}`}>{item.passwordSaved ? "저장됨" : "저장 안 함"}</span></td>
            <td><button type="button" className="btn sm" onClick={async () => { await deleteSavedConnection(item.id); reloadSaved(); }}>삭제</button></td></tr>)}</tbody></table></div>}
    </section>
  </div>;
}
