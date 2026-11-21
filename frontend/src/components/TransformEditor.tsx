"use client";

import { Help } from "./ui";

/** 컬럼 값 변환 규칙의 한 단계. 서버(ValueTransform.Step)와 같은 모양이다. */
export type TransformStep = { op: string; find?: string; with?: string; value?: string; map?: Record<string, string>; delimiter?: string; part?: number; start?: number; length?: number; expression?: string; scale?: number };

export const OPS: [string, string, string][] = [
  ["trim", "앞뒤 공백 제거", "값의 앞뒤 공백을 지웁니다."],
  ["upper", "대문자로 변경", "영문을 모두 대문자로 바꿉니다."],
  ["lower", "소문자로 변경", "영문을 모두 소문자로 바꿉니다."],
  ["replace", "문자열 치환", "값 안의 특정 문자열을 다른 문자열로 바꿉니다. 바꿀 문자열을 비우지 않고 '바꿀 내용'을 비우면 삭제합니다."],
  ["map", "값 대응표", "값이 대응표의 왼쪽과 같으면 오른쪽 값으로 바꿉니다. 목록에 없는 값은 그대로 둡니다. 한 줄에 하나씩 '원래값=바꿀값'으로 입력합니다."],
  ["nullTo", "빈 값 대체", "값이 비어 있으면 지정한 값으로 채웁니다."],
  ["split", "구분자로 분할", "값을 구분자로 나누어 지정한 번째 조각만 사용합니다. 예: 010-1234-5678을 '-'로 나눠 1번째 → 010"],
  ["substr", "일부 글자 추출", "시작 위치(1부터)에서 지정한 글자 수만큼만 사용합니다."],
  ["template", "문자열 합치기(병합)", "여러 컬럼과 문자를 이어 붙입니다. {컬럼명}은 같은 행의 원본 컬럼 값, {@}은 현재 값입니다. 예: {LAST_NAME} {FIRST_NAME}"],
  ["calc", "계산식", "숫자 컬럼으로 사칙연산(+ - * /)과 괄호를 계산합니다. 예: {PRICE} * {QTY}. 값이 하나라도 비어 있으면 결과도 비웁니다."],
];

const blank = (op: string): TransformStep => ({ op });
const mapToText = (m?: Record<string, string>) => Object.entries(m ?? {}).map(([k, v]) => `${k}=${v}`).join("\n");
const textToMap = (t: string) => Object.fromEntries(t.split("\n").map(l => l.replace(/\r$/, "")).filter(l => l.includes("=")).map(l => { const i = l.indexOf("="); return [l.slice(0, i), l.slice(i + 1)]; }));

/** 규칙이 완성되었는지(서버가 거부하지 않을 입력인지). */
export function stepReady(s: TransformStep): boolean {
  switch (s.op) {
    case "replace": return !!s.find;
    case "map": return Object.keys(s.map ?? {}).length > 0;
    case "split": return !!s.delimiter && !!s.part;
    case "substr": return !!s.start && !!s.length;
    case "template": case "calc": return !!s.expression?.trim();
    default: return true;
  }
}
export function summarize(steps: TransformStep[]): string {
  return steps.map(s => OPS.find(o => o[0] === s.op)?.[1] ?? s.op).join(" → ");
}

export default function TransformEditor({ column, columns, steps, disabled, onChange }: { column: string; columns: string[]; steps: TransformStep[]; disabled: boolean; onChange: (steps: TransformStep[]) => void }) {
  const update = (i: number, patch: Partial<TransformStep>) => onChange(steps.map((s, k) => (k === i ? { ...s, ...patch } : s)));
  const num = (v: string) => (v === "" ? undefined : Number(v.replace(/\D/g, "")) || undefined);
  const lab = (i: number, text: string) => `${column} 규칙 ${i + 1} ${text}`;
  return <div className="grid" role="group" aria-label={`${column} 변환 규칙`}>
    {steps.length === 0 && <p className="mut">변환 없이 원본 값을 그대로 이관합니다. 아래 &apos;규칙 추가&apos;로 값을 바꿀 수 있습니다.</p>}
    {steps.map((s, i) => <div key={i} className="split">
      <div className="row end">
        <label className="f" style={{ width: 220 }}>{i + 1}단계<select aria-label={lab(i, "종류")} disabled={disabled} value={s.op} onChange={e => onChange(steps.map((x, k) => (k === i ? blank(e.target.value) : x)))}>{OPS.map(o => <option key={o[0]} value={o[0]}>{o[1]}</option>)}</select></label>
        <Help text={OPS.find(o => o[0] === s.op)?.[2] ?? ""} />
        {s.op === "replace" && <><label className="f">바꿀 문자열<input aria-label={lab(i, "바꿀 문자열")} disabled={disabled} maxLength={200} value={s.find ?? ""} onChange={e => update(i, { find: e.target.value })} /></label>
          <label className="f">바꿀 내용<input aria-label={lab(i, "바꿀 내용")} disabled={disabled} maxLength={200} value={s.with ?? ""} onChange={e => update(i, { with: e.target.value })} /></label></>}
        {s.op === "nullTo" && <label className="f">채울 값<input aria-label={lab(i, "채울 값")} disabled={disabled} maxLength={200} value={s.value ?? ""} onChange={e => update(i, { value: e.target.value })} /></label>}
        {s.op === "split" && <><label className="f" style={{ width: 120 }}>구분자<input aria-label={lab(i, "구분자")} disabled={disabled} maxLength={20} value={s.delimiter ?? ""} onChange={e => update(i, { delimiter: e.target.value })} /></label>
          <label className="f" style={{ width: 120 }}>몇 번째<input aria-label={lab(i, "몇 번째")} inputMode="numeric" disabled={disabled} value={s.part ?? ""} onChange={e => update(i, { part: num(e.target.value) })} /></label></>}
        {s.op === "substr" && <><label className="f" style={{ width: 120 }}>시작 위치<input aria-label={lab(i, "시작 위치")} inputMode="numeric" disabled={disabled} value={s.start ?? ""} onChange={e => update(i, { start: num(e.target.value) })} /></label>
          <label className="f" style={{ width: 120 }}>글자 수<input aria-label={lab(i, "글자 수")} inputMode="numeric" disabled={disabled} value={s.length ?? ""} onChange={e => update(i, { length: num(e.target.value) })} /></label></>}
        {(s.op === "template" || s.op === "calc") && <label className="f" style={{ flex: 1 }}>{s.op === "calc" ? "계산식" : "합칠 형식"}<input aria-label={lab(i, s.op === "calc" ? "계산식" : "합칠 형식")} disabled={disabled} maxLength={500} placeholder={s.op === "calc" ? "예: {PRICE} * {QTY}" : "예: {LAST_NAME} {FIRST_NAME}"} value={s.expression ?? ""} onChange={e => update(i, { expression: e.target.value })} /></label>}
        {s.op === "calc" && <label className="f" style={{ width: 130 }}>소수 자리<input aria-label={lab(i, "소수 자리")} inputMode="numeric" disabled={disabled} placeholder="그대로" value={s.scale ?? ""} onChange={e => update(i, { scale: e.target.value === "" ? undefined : Math.min(20, Number(e.target.value.replace(/\D/g, "")) || 0) })} /></label>}
        <button type="button" className="btn sm" disabled={disabled} aria-label={lab(i, "삭제")} onClick={() => onChange(steps.filter((_, k) => k !== i))}>삭제</button>
      </div>
      {s.op === "map" && <label className="f">대응표 (한 줄에 하나, 원래값=바꿀값)<textarea aria-label={lab(i, "대응표")} rows={3} disabled={disabled} placeholder={"Y=1\nN=0"} defaultValue={mapToText(s.map)} onBlur={e => update(i, { map: textToMap(e.target.value) })} onChange={e => update(i, { map: textToMap(e.target.value) })} /></label>}
      {(s.op === "template" || s.op === "calc") && <div className="chips" role="group" aria-label={lab(i, "컬럼 넣기")}><small className="mut">컬럼 넣기</small>
        <button type="button" className="pre" disabled={disabled} onClick={() => update(i, { expression: `${s.expression ?? ""}{@}` })}>현재 값</button>
        {columns.map(c => <button type="button" key={c} className="pre" disabled={disabled} onClick={() => update(i, { expression: `${s.expression ?? ""}{${c}}` })}>{c}</button>)}</div>}
      {!stepReady(s) && <small className="mut">입력이 끝나지 않은 규칙은 이관할 수 없습니다.</small>}
    </div>)}
    <div className="row"><button type="button" className="btn sm" disabled={disabled || steps.length >= 10} onClick={() => onChange([...steps, blank("trim")])}>규칙 추가</button>
      {steps.length > 0 && <button type="button" className="btn sm" disabled={disabled} onClick={() => onChange([])}>모두 지우기</button>}</div>
  </div>;
}
