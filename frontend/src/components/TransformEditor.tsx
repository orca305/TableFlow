"use client";

import { Help } from "./ui";

/** 컬럼 값 변환 규칙의 한 단계. 서버(ValueTransform.Step)와 같은 모양이다. */
export type TransformCase = { column?: string; test: string; compare?: string; result?: string };
export type TransformStep = { op: string; find?: string; with?: string; value?: string; map?: Record<string, string>; delimiter?: string; part?: number; start?: number; length?: number; expression?: string; scale?: number; cases?: TransformCase[] };

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
  ["dateFormat", "날짜 형식 변경", "날짜 글자를 다른 형식으로 바꿉니다. yyyy(연) MM(월) dd(일) HH(시) mm(분) ss(초)를 씁니다. 예: yyyyMMdd → yyyy-MM-dd. 원본 형식을 비우면 yyyy-MM-dd 또는 일시로 읽습니다. 형식에 맞지 않거나 없는 날짜는 거부합니다."],
  ["regexReplace", "정규식 치환", "정규식에 맞는 부분을 모두 바꿉니다. 괄호로 묶은 부분은 $1, $2로 다시 씁니다. 예: (\\d{3})(\\d{4})(\\d{4}) → $1-$2-$3"],
  ["regexExtract", "정규식 추출", "정규식에 처음 맞는 부분만 남깁니다. 괄호가 있으면 첫 번째 괄호(또는 지정한 번호)의 내용이며, 맞는 부분이 없으면 비웁니다."],
  ["case", "조건별 값", "조건을 위에서부터 검사해 처음 맞는 값을 넣습니다. 같음은 글자 그대로 비교하고, 이상·이하 같은 크기 비교는 숫자나 날짜(yyyy-MM-dd)로 합니다. 빈 값은 '비어 있음' 조건에만 맞습니다. 결과에 {컬럼명}과 {@}(현재 값)을 쓸 수 있습니다."],
];

export const TESTS: [string, string][] = [["eq", "같음"], ["ne", "같지 않음"], ["contains", "포함"], ["startsWith", "시작 글자"], ["endsWith", "끝 글자"], ["gt", "초과"], ["ge", "이상"], ["lt", "미만"], ["le", "이하"], ["empty", "비어 있음"], ["notEmpty", "값이 있음"]];
const needsCompare = (test: string) => test !== "empty" && test !== "notEmpty";

const blank = (op: string): TransformStep => (op === "case" ? { op, cases: [{ test: "eq" }] } : { op });
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
    case "dateFormat": return !!s.with;
    case "regexReplace": case "regexExtract": return !!s.find;
    case "case": return !!s.cases?.length && s.cases.every(c => !needsCompare(c.test) || !!c.compare);
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
        {s.op === "dateFormat" && <><label className="f" style={{ width: 200 }}>원본 형식<input aria-label={lab(i, "원본 형식")} disabled={disabled} maxLength={200} placeholder="비우면 yyyy-MM-dd" value={s.find ?? ""} onChange={e => update(i, { find: e.target.value || undefined })} /></label>
          <label className="f" style={{ width: 200 }}>바꿀 형식<input aria-label={lab(i, "바꿀 형식")} disabled={disabled} maxLength={200} placeholder="예: yyyy-MM-dd" value={s.with ?? ""} onChange={e => update(i, { with: e.target.value })} /></label></>}
        {(s.op === "regexReplace" || s.op === "regexExtract") && <label className="f" style={{ flex: 1 }}>정규식<input aria-label={lab(i, "정규식")} disabled={disabled} maxLength={200} spellCheck={false} placeholder="예: \d+" value={s.find ?? ""} onChange={e => update(i, { find: e.target.value })} /></label>}
        {s.op === "regexReplace" && <label className="f">바꿀 내용<input aria-label={lab(i, "바꿀 내용")} disabled={disabled} maxLength={200} spellCheck={false} placeholder="예: $1-$2" value={s.with ?? ""} onChange={e => update(i, { with: e.target.value })} /></label>}
        {s.op === "regexExtract" && <label className="f" style={{ width: 220 }}>괄호 번호<input aria-label={lab(i, "괄호 번호")} inputMode="numeric" disabled={disabled} placeholder="자동" value={s.part ?? ""} onChange={e => update(i, { part: num(e.target.value) })} /></label>}
        {s.op === "calc" && <label className="f" style={{ width: 130 }}>소수 자리<input aria-label={lab(i, "소수 자리")} inputMode="numeric" disabled={disabled} placeholder="그대로" value={s.scale ?? ""} onChange={e => update(i, { scale: e.target.value === "" ? undefined : Math.min(20, Number(e.target.value.replace(/\D/g, "")) || 0) })} /></label>}
        <button type="button" className="btn sm" disabled={disabled} aria-label={lab(i, "삭제")} onClick={() => onChange(steps.filter((_, k) => k !== i))}>삭제</button>
      </div>
      {s.op === "map" && <label className="f">대응표 (한 줄에 하나, 원래값=바꿀값)<textarea aria-label={lab(i, "대응표")} rows={3} disabled={disabled} placeholder={"Y=1\nN=0"} defaultValue={mapToText(s.map)} onBlur={e => update(i, { map: textToMap(e.target.value) })} onChange={e => update(i, { map: textToMap(e.target.value) })} /></label>}
      {s.op === "case" && <div className="grid" role="group" aria-label={lab(i, "조건 목록")}>
        {(s.cases ?? []).map((c, k) => { const patch = (next: Partial<TransformCase>) => update(i, { cases: (s.cases ?? []).map((x, n) => (n === k ? { ...x, ...next } : x)) }); const name = (text: string) => lab(i, `조건 ${k + 1} ${text}`);
          return <div key={k} className="row end">
            <label className="f" style={{ width: 180 }}>검사할 값<select aria-label={name("컬럼")} disabled={disabled} value={c.column ?? ""} onChange={e => patch({ column: e.target.value || undefined })}><option value="">현재 값</option>{columns.map(x => <option key={x} value={x}>{x}</option>)}</select></label>
            <label className="f" style={{ width: 140 }}>조건<select aria-label={name("종류")} disabled={disabled} value={c.test} onChange={e => patch({ test: e.target.value, ...(needsCompare(e.target.value) ? {} : { compare: undefined }) })}>{TESTS.map(t => <option key={t[0]} value={t[0]}>{t[1]}</option>)}</select></label>
            {needsCompare(c.test) && <label className="f">비교할 값<input aria-label={name("비교할 값")} disabled={disabled} maxLength={200} value={c.compare ?? ""} onChange={e => patch({ compare: e.target.value })} /></label>}
            <label className="f">넣을 값<input aria-label={name("넣을 값")} disabled={disabled} maxLength={500} value={c.result ?? ""} onChange={e => patch({ result: e.target.value })} /></label>
            <button type="button" className="btn sm" disabled={disabled || (s.cases?.length ?? 0) <= 1} aria-label={name("삭제")} onClick={() => update(i, { cases: (s.cases ?? []).filter((_, n) => n !== k) })}>삭제</button>
          </div>; })}
        <div className="row end"><button type="button" className="btn sm" disabled={disabled || (s.cases?.length ?? 0) >= 20} onClick={() => update(i, { cases: [...(s.cases ?? []), { test: "eq" }] })}>조건 추가</button>
          <label className="f" style={{ flex: 1 }}>모두 맞지 않을 때<input aria-label={lab(i, "그 밖의 값")} disabled={disabled} maxLength={200} placeholder="비우면 현재 값 유지" value={s.value ?? ""} onChange={e => update(i, { value: e.target.value || undefined })} /></label></div>
      </div>}
      {(s.op === "template" || s.op === "calc") && <div className="chips" role="group" aria-label={lab(i, "컬럼 넣기")}><small className="mut">컬럼 넣기</small>
        <button type="button" className="pre" disabled={disabled} onClick={() => update(i, { expression: `${s.expression ?? ""}{@}` })}>현재 값</button>
        {columns.map(c => <button type="button" key={c} className="pre" disabled={disabled} onClick={() => update(i, { expression: `${s.expression ?? ""}{${c}}` })}>{c}</button>)}</div>}
      {!stepReady(s) && <small className="mut">입력이 끝나지 않은 규칙은 이관할 수 없습니다.</small>}
    </div>)}
    <div className="row"><button type="button" className="btn sm" disabled={disabled || steps.length >= 10} onClick={() => onChange([...steps, blank("trim")])}>규칙 추가</button>
      {steps.length > 0 && <button type="button" className="btn sm" disabled={disabled} onClick={() => onChange([])}>모두 지우기</button>}</div>
  </div>;
}
