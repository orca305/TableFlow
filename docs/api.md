# API v1

Base: `http://localhost:8080/api/v1`

| Method | 경로 | 요청 | 응답 |
| --- | --- | --- | --- |
| GET | `/health` | 없음 | `{status, version}` |
| POST | `/files/parse` | multipart `file`(CSV/XLSX/PDF/PNG/JPEG), 선택 `sheet`, `encoding`, `delimiter`, `page`(0=전체, 1~20), `externalConsent`(기본 false) | `{tables, warnings, sheets}` |
| POST | `/tables/transform` | Table JSON | 정규화한 Table (오류 값은 원문 유지) |
| POST | `/tables/validate` | Table JSON | `{valid, issues}` |
| POST | `/exports/sql` | `{table, dbms: "oracle"\|"mysql", tableName}` | UTF-8 `.sql` attachment |
| POST | `/exports/excel` | Table JSON | `.xlsx` attachment |
| GET | `/loads/target` | 없음 | `{enabled,dbms,tables}` |
| POST | `/loads/check` | `{table,tableName}` | `{tableName,rows,columns}` 읽기 전용 검사 |
| POST | `/loads/database` | `{table,tableName,requestId:UUID,confirmed:true}` | `{requestId,tableName,insertedRows,replayed}` |

## Table 계약

```json
{
  "schema_version": "1.0",
  "table_id": "tbl_1",
  "name": "employees",
  "source": { "filename": "employees.csv", "type": "csv", "extraction_method": "parser" },
  "columns": [{
    "id": "col_1", "name": "이름", "target_name": "EMP_NM",
    "data_type": "string", "nullable": false, "included": true,
    "max_length": 50, "precision": null, "scale": null
  }],
  "rows": [{
    "id": "row_1",
    "cells": { "col_1": { "raw": "김하늘", "value": "김하늘", "confidence": null, "edited": false } }
  }]
}
```

자료형: `string`, `integer`, `decimal`, `date`, `datetime`, `bool`. 숫자도 JSON 문자열입니다. 값의 생략과 NULL을 혼동하지 않도록 셀 raw/value에 명시적인 null 또는 문자열을 사용하세요. 날짜는 ISO 날짜 또는 `YYYY.MM.DD` / `YYYY/MM/DD`, 일시는 로컬 ISO 형식입니다. 타임존 변환은 지원하지 않습니다.

검증 Issue: `{rowId, columnId, code, message}`. 행/컬럼 전체 문제는 해당 ID가 null입니다. 최대 1,000건을 반환합니다. 중복 행은 포함된 컬럼의 정규화 값으로 판정하며 출력 전 수정해야 합니다.

출력 요청도 서버에서 동일한 검증을 수행합니다. UI 검증 여부를 신뢰하지 않습니다. 제외한 컬럼은 값·식별자 검증과 출력에서 제외하지만 셀 ID 구조는 일치해야 합니다.

## 오류

OCR source에는 `page`(PDF 페이지, 이미지 null), `provider`(mock/local/external), `extraction_method`(mock_ocr/local_ocr/external_ocr)가 추가됩니다. 텍스트 PDF는 `pdf_text`입니다. OCR 내보내기 요청에는 `X-TableFlow-Reviewed: true`가 필요합니다. 이는 원본 대조 확인이며 인증 기능은 아닙니다.

내부 OCR: `POST http://127.0.0.1:8000/internal/v1/ocr/extract`, multipart `file`, 선택 `pages`(예: `1,3`), `external_upload_consent`(기본 false). 동일한 ParseResult를 반환하며 Spring Boot만 호출합니다. `GET /health`는 상태와 설정된 provider를 반환합니다.

기본 형태: `{code, message}`. 업로드 내용이나 내부 stack trace를 반환하지 않습니다.

| 상태 | 의미 |
| --- | --- |
| 400 | 잘못된 요청, 파일 형식, 값 또는 식별자 |
| 403 | 외부 OCR 파일별 전송 동의 필요 |
| 409 | 같은 적재 요청 ID에 다른 내용 |
| 413 | 파일 또는 JSON 요청 크기 초과 |
| 422 | 출력 전 검증 실패 (`issues` 포함) |
| 429 | 요청 횟수 또는 동시 처리 제한 (`Retry-After: 60`) |
| 500 | 예상하지 못한 처리 실패 |
| 502 | OCR 응답 형식·계약 오류 |
| 503 | OCR 연결·공급자 장애 |
| 504 | OCR 처리 시간 초과 |

`Content-Disposition` 다운로드 파일명은 고정이며 사용자 파일명으로 HTTP 헤더를 구성하지 않습니다. CORS는 `TABLEFLOW_FRONTEND_ORIGIN` 한 곳에만 허용합니다.

DB API는 로컬 원격 주소와 허용 Origin만 접근할 수 있으며 기본 비활성화입니다. 대상은 서버 allowlist에서만 선택합니다. OCR 실제 적재도 `X-TableFlow-Reviewed:true`가 필요합니다. 커밋 결과 미확인/연결 오류는 같은 requestId로 재시도해야 합니다. [DB 적재 계약](database-loading.md)
