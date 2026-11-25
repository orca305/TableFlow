# API v1

Base: `http://localhost:8080/api/v1`

| Method | 경로 | 요청 | 응답 |
| --- | --- | --- | --- |
| GET | `/health` | 없음 | `{status, version}` |
| POST | `/files/parse` | multipart `file`(CSV/XLSX/PDF/PNG/JPEG), 선택 `sheet`, `encoding`, `delimiter`, `page`(0=전체, 1~20), `externalConsent`(기본 false), `headerRow`(CSV/XLSX 1~100, 기본 1) | `{tables, warnings, sheets}` |
| POST | `/tables/transform` | Table JSON | 정규화한 Table (오류 값은 원문 유지) |
| POST | `/tables/validate` | Table JSON | `{valid, issues}` |
| POST | `/exports/sql` | `{table, dbms: "oracle"\|"mysql"\|"postgresql"\|"mariadb", tableName}` | UTF-8 `.sql` attachment |
| POST | `/exports/excel` | Table JSON | `.xlsx` attachment |
| POST | `/exports/csv` | Table JSON | UTF-8 BOM RFC4180 `.csv` attachment |
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

검증 Issue: `{rowId, columnId, code, message, severity, solution}`. 행/컬럼 전체 문제는 해당 ID가 null입니다. 최대 1,000건을 반환합니다. 중복 행은 포함된 컬럼의 정규화 값으로 판정합니다. transform/validate/exports(sql,excel,csv)에 `?duplicateMode=strict|warn|keep|exclude`를 지정합니다. API 기본은 strict로 기존 계약을 유지하고 화면 기본은 warn입니다. warn은 경고를 반환하되 valid=true, keep은 유지, exclude는 transform/출력 시 첫 행만 보존합니다. strict는 중복을 오류로 처리합니다. DB 적재 API는 기존 strict 정책을 유지합니다.

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

파서 오류에는 `hint`와 선택적 `row`가 포함됩니다. 헤더 오류는 HEADER_ROW/EMPTY_HEADER, 행 너비는 ROW_WIDTH, XLSX 시그니처/구조는 XLSX_SIGNATURE/XLSX_STRUCTURE로 구분합니다. CSV 헤더 행은 빈 줄을 건너뛴 논리 레코드 번호이며 인용된 줄바꿈도 하나의 레코드입니다. XLSX는 실제 시트 행 번호입니다.

## JDBC 메타데이터 탐색

모든 `/connections/*`는 로컬 요청과 허용 Origin만 접근할 수 있습니다. 기본 비활성화이며 POST에는 호스트 allowlist를 적용합니다. 조회용 연결만 열고 요청 종료 시 닫습니다.

| Method | 경로 | 요청 | 응답 |
|---|---|---|---|
| GET | `/connections/drivers` | 없음 | `{enabled,drivers:[{dbms,defaultPort,available,version}]}` |
| POST | `/connections/test` | Credentials | `{product,serverVersion,driver,driverVersion}` |
| POST | `/connections/schemas` | Credentials | `{items:string[],truncated}` |
| POST | `/connections/tables` | `{connection:Credentials,schema}` | `{items:string[],truncated}` |
| POST | `/connections/columns` | `{connection:Credentials,schema,table}` | `{items:[{name,type,jdbcType,size,scale,nullable,primaryKey,autoIncrement,position}],truncated}` |

Credentials: `{dbms:oracle|postgresql|mysql|mariadb,host,port,database,username,password,tls}`. DB는 이름만 입력하고 Oracle은 서비스명입니다. URL·드라이버 옵션·SQL을 입력받지 않습니다. MySQL/MariaDB는 JDBC catalog를 스키마로 취급하며, JDBC 패턴의 `_`/`%`를 이스케이프해 선택한 이름을 정확히 조회합니다.

오류 코드는 JDBC_DISABLED(503), JDBC_ACCESS_DENIED/JDBC_HOST_DENIED(403), JDBC_TLS_REQUIRED/JDBC_DATABASE(400), JDBC_AUTH(401), JDBC_PERMISSION(403), JDBC_TIMEOUT(504), JDBC_TLS/JDBC_CONNECTION(502), JDBC_DRIVER(503)입니다. 원본 SQLException 메시지/URL/비밀번호/stack trace는 응답하지 않습니다. 인증 실패 분류는 SQLState·벤더 코드에 기반하며 드라이버가 표준 코드를 주지 않는 경우 일반 연결 오류로 표시할 수 있습니다.

## 공통 JDBC 적재 API

`/connections/drivers` 응답에 `loadEnabled`가 추가됐습니다. `POST /connections/load/check`와 `POST /connections/load`는 `{connection:Credentials,table:Table,schema,tableName,requestId?,confirmed?}`를 받습니다. check 응답은 `{dbms,schema,tableName,rows,columns}`입니다. load에는 UUID requestId와 confirmed=true 및 OCR일 때 `X-TableFlow-Reviewed:true`가 필요합니다. 응답은 기존 `{requestId,tableName,insertedRows,replayed}`입니다. 서버는 적재 시 다시 구조를 검사하며 클라이언트의 check 결과를 신뢰하지 않습니다. 적재 기본 비활성화 오류는 JDBC_LOAD_DISABLED(503)입니다. 제약조건/구조/재시도 오류는 LOAD_REJECTED/LOAD_SCHEMA(422), LOAD_REQUEST_CONFLICT(409), LOAD_OUTCOME_UNKNOWN/LOAD_FAILED(503)입니다. [적재 설정](jdbc-loading.md).

## DB 행 추출 · DB→DB 이전 API

모두 `/connections/*`와 같은 로컬 요청·Origin·호스트 allowlist 제한을 받으며 비밀번호는 응답·로그에 남지 않습니다.

| Method | 경로 | 요청 | 응답 |
|---|---|---|---|
| POST | `/connections/read` | `{connection,schema,table,columns?,limit(1~10000)}` | `Table`(source.type=`database`, extraction_method=`jdbc`) |
| POST | `/connections/migrate/check` | `MigrateRequest` | `{sourceDbms,targetDbms,source,target,sourceRows,rows,columns:[{source,target,sourceType,targetType,conversion}],notes,createsTable,ddl:string[],warnings:string[],bulk,chunks,chunkSize}` (쓰기 없음. `warnings`는 외래 키·자동 증가 컬럼·누락된 필수 컬럼 안내) |
| POST | `/connections/migrate` | `MigrateRequest` + `requestId`(UUID) + `confirmed:true` (limit ≤ 10,000) | `{requestId,tableName,insertedRows,replayed,notes:string[],chunks:0,replayedChunks:0}` |
| POST | `/connections/migrate/jobs` | `MigrateRequest` + `requestId` + `confirmed:true` (대용량) | `{id,state:queued\|running\|succeeded\|failed\|cancelled,totalRows,doneRows,totalChunks,doneChunks,replayedChunks,errorCode,message,startedAt,finishedAt,notes}` — 같은 `requestId`로 실행 중이면 새로 시작하지 않고 상태를 반환, 실패·취소 후 같은 `requestId`로 재제출하면 완료된 청크는 건너뛰고 이어서 진행 |
| GET | `/connections/migrate/jobs/{id}` | 없음 | 위와 같은 상태(없으면 404 `JOB_NOT_FOUND`) |
| DELETE | `/connections/migrate/jobs/{id}` | 없음 | 다음 청크 전에 멈추도록 요청(이미 커밋된 청크는 되돌리지 않음) |

`/connections/read`는 임의 SQL을 받지 않으며 메타데이터로 확인된 컬럼만 인용해 읽기 전용 SELECT를 실행합니다(`setMaxRows`, fetch size 500, 30초 타임아웃). BLOB·바이너리 등은 400으로 거부합니다. 결과 `Table`은 `/exports/csv|excel|sql`에 그대로 사용할 수 있습니다.

`MigrateRequest`: `{source:Credentials,sourceSchema,sourceTable,target:Credentials,targetSchema,targetTable,mappings:[{source,target}],limit(1~1000000),createTable?,preserveKeys?}`. `limit`이 10,000 이하이면 한 트랜잭션(동기), **10,000을 넘으면 대용량(청크) 점검·`/migrate/jobs` 실행**입니다. `preserveKeys:true`이면 자동 증가 대상 컬럼에 원본 값을 넣고 적재 후 시퀀스를 재동기화합니다. `createTable:true`이면 대상 테이블·영수증 테이블이 없을 때 `CREATE TABLE`만 실행합니다(Dry-run 응답의 `ddl`로 미리 확인, 이미 있으면 생성하지 않음). 원본 행 수가 `limit`보다 많으면 일부만 이전하지 않고 400으로 거부합니다. 시간 정보가 있는 일시를 DATE 컬럼으로 옮기면 400(손실 방지)입니다. 적재는 기존 공통 JDBC 적재 경로(트랜잭션·영수증·전체 롤백)를 사용하며 적재가 비활성화이면 JDBC_LOAD_DISABLED(503)입니다.

| Method | 경로 | 요청 | 응답 |
|---|---|---|---|
| GET | `/connections/history?limit=50` | 없음 | `{enabled,items:[{id,kind:load\|migrate,requestId,startedAt,durationMs,status:success\|replayed\|failed,source,target,rows,errorCode,message}]}` 최신순, 최대 200건 |

작업 이력에는 비밀번호·호스트·사용자명·행 데이터가 포함되지 않습니다. 대상 자동 증가 컬럼에 값을 넣는 매핑은 400(`INVALID_INPUT`)입니다.

## 2026-11-20 추가: 저장된 연결 · 분석 · 분할 단위

모든 `/connections/*` 요청은 기존처럼 로컬 요청·Origin 검사를 거치며, CORS는 GET/POST/DELETE를 허용한다.

| 메서드·경로 | 설명 |
|---|---|
| `GET /connections/drivers` | 기존 필드 + `savedEnabled`, `savePasswordEnabled`, `defaultChunkSize` |
| `GET /connections/saved` | 저장된 연결 목록. **비밀번호는 포함하지 않고** `passwordSaved`만 알린다 |
| `POST /connections/saved` | `{name, connection, savePassword}` — 연결 저장·갱신. `savePassword=true`일 때만 비밀번호를 AES-256-GCM으로 암호화해 서버 로컬 파일에 보관(비워 보내면 기존 비밀번호 유지) |
| `DELETE /connections/saved/{id}` | 저장된 연결 삭제 |
| `POST /connections/count` | `{connection, schema, table}` → `{rows}` 전체 행 수(읽기 전용) |
| `POST /connections/migrate/analyze` | `MigrateRequest` → `{sourceRows, maxRows, columns[{source,target,sourceType,targetType,conversion,level,detail}], notes, warnings, createsTable, ddl}`. 값은 검증하지 않고 컬럼 호환성만 판정. `level`은 ok/info/warn/error. 길이 위험이 있는 문자열 컬럼은 원본의 실제 최대 길이도 조회 |
| `POST /connections/migrate/check`·`/migrate`·`/migrate/jobs` | 요청에 선택 필드 `chunkSize`(100~10,000) 추가. 생략하면 서버 기본값 |
| `POST /connections/load/check`·`/load` | 요청에 `createTable`(기본 false) 추가. true면 대상에 없는 테이블을 변환한 표의 컬럼 정의로 생성(`createsTable`, `ddl`, `warnings` 응답). 이미 있으면 점검에서 거부, 적재 재시도에서는 건너뜀 |

`Credentials`(모든 접속 요청)에 선택 필드 `savedId`가 생겼다. `password`를 비우고 `savedId`를 보내면 서버가 저장된 비밀번호를 복호화해 쓰며, 주소·포트·DB·사용자·TLS가 저장된 연결과 다르면 거부한다(`CONNECTION_MISMATCH`).

### 추가 (신규 테이블의 대상 타입 지정 · FK 표시)
- `mappings[]` 항목에 선택 필드 `type`(string/integer/decimal/date/datetime/bool), `size`(문자열 길이 또는 숫자 정밀도), `scale`을 보낼 수 있다. **신규 테이블 생성(`createTable=true`)일 때만** 쓰이며, 생략하면 원본 정의를 따른다. 지정한 컬럼은 그 정의로 값을 검증하고, `/migrate/analyze`가 원본과 비교한 `level`/`detail`(길이 부족·형식 변환 위험 등)을 돌려준다.
- `/connections/columns`의 컬럼 정보에 `foreignKey`(외래 키 여부)가 추가되었다.

### 추가 (기본 키 지정 · 구조 복제 · 작업 영속화와 재개)
- `POST /connections/load/check`·`/load`: `primaryKey`(대상 컬럼 이름 배열, 선택)를 보내면 신규 테이블을 그 기본 키로 만든다. 선택한 컬럼에 빈 값·중복 값이 있으면 거부한다.
- `POST /connections/migrate/*`: 신규 테이블 생성(`createTable=true`)일 때 `copyIndexes`, `copyDefaults`, `copyForeignKeys`(기본 false)로 원본의 보조 인덱스·기본값·외래 키를 함께 만든다. 기본값은 숫자·문자·불리언·현재 시각만 옮기고(시퀀스·함수는 건너뛰고 경고), FK는 부모 테이블이 대상에 이미 있을 때만 생성한다.
- `POST /connections/migrate/analyze` 응답에 `foreignKeys[{name, columns, parentTable, parentInTarget, copied, message}]`가 추가되었다(부모 테이블 이관 순서 안내).
- `GET /connections/migrate/jobs` → `{enabled, items:[{status, source, target, resumable, sourceNeedsPassword, targetNeedsPassword}]}` 이관 작업 목록. **서버를 다시 시작해도 유지**되며(`data/jobs.json`, git 제외), 그때 실행 중이던 작업은 `failed/SERVER_RESTARTED`로 복원된다.
- `POST /connections/migrate/jobs/{id}/resume` `{sourcePassword?, targetPassword?}` → 같은 요청 ID로 다시 시작해 완료된 분할은 건너뛰고 이어서 진행한다. 저장된 비밀번호가 있는 연결은 비밀번호 없이, 없는 쪽만 비밀번호를 받는다. 저장 파일에는 비밀번호가 들어가지 않는다.

### 추가 (이관 순서)
- `POST /connections/order` `{connection, schema, tables[]}` → `{order:[{table, level, parents[], externalParents[]}], cycles[]}`. 선택한 테이블의 외래 키를 읽어 부모가 먼저 오도록 단계(level 0 = 부모 없음)를 계산한다. 서로 참조하는 테이블은 `cycles`로 돌려준다(읽기 전용, 최대 200개).
- `POST /connections/migrate/verify` (요청은 `/migrate`와 같음) → `{sourceRows,targetRows,rowsMatch,allMatch,checks[{source,target,measure,sourceValue,targetValue,match,note}],notes[]}`. 이관 후 원본과 대상의 행 수, 값이 있는 행 수, 숫자 합계·최소·최대, 문자열 길이 합계, 날짜 최소·최대를 비교한다(읽기 전용). 행 단위 해시는 DB별 값 표현 차이로 오탐이 많아 쓰지 않는다.
- `POST /connections/count-filtered` `{connection, schema, table, where}` → `{rows}`. 행 조건(WHERE)에 맞는 행 수를 센다(읽기 전용). 조건이 규칙에 어긋나거나 DB가 거부하면 4xx와 이유를 돌려준다.
- **행 조건**: `/migrate`, `/migrate/check`, `/migrate/analyze`, `/migrate/jobs`, `/migrate/verify` 요청의 선택 필드 `where`(최대 1000자, WHERE 없이 조건식만). 점검·이관·분할 읽기·검증의 원본 쪽에 모두 적용된다. 세미콜론·주석·DML/DDL·UNION·SLEEP 계열 단어는 거부하고, 따옴표 안의 값은 검사하지 않는다. 컬럼 이름 대소문자는 원본 DB 기준이다(PostgreSQL 대문자 컬럼은 큰따옴표). 이관할 컬럼은 `mappings`에 넣은 것만 쓴다.
- **값 변환 규칙**: `mappings[].steps[]`(최대 10단계, 앞 단계 결과가 다음 단계의 현재 값). `op`: `trim` / `upper` / `lower` / `replace`(find→with) / `map`(치환표) / `nullTo`(value) / `split`(delimiter, part) / `substr`(start, length) / `template`(expression, 문자열 합치기) / `calc`(expression, scale, 사칙연산·괄호). 식에서 `{컬럼}`은 같은 행의 변환 전 원본 컬럼 값, `{@}`은 현재 값이다. 규칙이 참조하는 컬럼은 서버가 추가로 읽는다. 결과는 문자열로 계산해 대상 컬럼 타입(기존 테이블) 또는 문자열/소수(신규 테이블, `type`·`size`·`scale`로 지정 가능)로 넣으며, 형식·길이를 넘으면 사전 점검(표본) 또는 해당 분할에서 거부된다. 변환 규칙이 있는 컬럼은 `/migrate/verify`의 값 비교에서 제외된다(행 수는 비교).
- `POST /connections/migrate/batch/check` `{source, sourceSchema, target, targetSchema, tables[], preserveKeys, copyIndexes, copyDefaults, copyForeignKeys, ifExists("skip"|"append"), chunkSize}` → `{items[{table,target,rows,columns,excluded[],targetExists,action(create|append|skip|error),level(ok|warn|error),messages[]}], cycles[], notes[], totalRows, blocked}`. 선택한 테이블(최대 200개)을 외래 키 부모 먼저 순서로 나열하고, 대상 이름(DBMS 관례로 변환)·충돌·컬럼 오류를 점검한다. 아무것도 쓰지 않는다.
- `POST /connections/migrate/batch` (위 요청 + `requestId`, `confirmed:true`) → 작업 상태. 테이블마다 새로 생성(빈 테이블 포함)·분할 적재하며, `GET /migrate/jobs/{id}`의 `items[{table,target,state(pending|running|succeeded|skipped|failed),totalRows,doneRows,message}]`로 테이블별 진행을 본다. 한 테이블이 실패하면 멈추고, `POST /migrate/jobs/{id}/resume`(또는 같은 `requestId` 재제출)로 재개하면 완료된 테이블은 건너뛰고 실패한 테이블부터 이어서 적재한다.
- **스케줄** `/api/v1/schedules`(로컬 전용): `GET` 목록 `{enabled, items[]}` / `POST` 등록 `{name, kind("single"|"batch"), request(= /migrate 또는 /migrate/batch 요청 본문), mode("interval"|"daily"), intervalMinutes(5~10080), dailyAt("HH:mm", 서버 시간대), refresh("append"|"replace"), confirmReplace}` / `POST /{id}/run` 지금 실행 / `POST /{id}/enabled {enabled}` / `DELETE /{id}`. 두 연결 모두 **비밀번호를 저장한 연결(savedId)**이어야 하고 정의에는 비밀번호가 저장되지 않는다. 실행마다 새 요청 ID로 일반 이관 작업을 시작하므로 작업 이력에 남는다. `replace`는 실행 전에 대상 테이블의 모든 행을 삭제하고(일괄 이관은 자식 테이블부터) 원본 전체를 다시 적재한다(동의 필수). 이전 회차가 진행 중이면 그 회차는 건너뛴다. 스케줄 이관은 이관 행 수 상한을 최대(1,000,000)로 두고, 일괄 이관은 이미 있는 테이블에 추가 적재한다.


## 관리자 반입 JDBC 드라이버 (2026-11-25)

- `GET /api/v1/driver-catalog`: 등록된 `id, dbms, version, sha256, bytes, registeredAt` 목록.
- `POST /api/v1/driver-catalog`: multipart `dbms`, `version`, `file`. 단일 JAR 최대 10MB, 전체 32개. 동일 DBMS/버전/해시의 재요청은 기존 항목 반환. 다른 버전 이름으로 동일 파일 중복 등록은 거부.
- 두 API 모두 `X-TableFlow-Admin-Key`와 루프백 주소/허용 Origin 필요. 서버 `TABLEFLOW_DRIVERS_ADMIN_KEY`가 32자 미만이거나 비어 있으면 거부. 삭제 API 없음.
- `GET /api/v1/connections/drivers` 응답에 `installedDrivers` 목록 추가. 각 기본 드라이버 항목의 `capabilities`에는 `browse/load/createTable/preserveKeys/experimental`을 명시하며, `available`은 기본 제공 JAR의 존재 여부를 뜻한다. 이 목록에는 JAR 경로나 관리자 키·비밀번호가 포함되지 않음.
- 모든 연결 요청의 `Credentials`에 선택적 `driverId` 추가. 미지정이면 기본 제공 드라이버, 지정하면 해당 DBMS 등록 JAR만 사용. 실패 시 다른 드라이버로 자동 대체하지 않음. 저장 연결 응답에도 `driverId` 유지.
- `dbms=sqlserver` 추가. Microsoft JAR는 관리자 반입 방식이며 기본 `TABLEFLOW_SQLSERVER_ENABLED=true`. 이 설정으로 별도 차단한 연결 요청은 `JDBC_EXPERIMENTAL_DISABLED`로 거부. SQL 파일 내보내기의 별도 방언 목록은 기존 4종을 유지한다.
- 비밀번호를 저장한 연결의 driverId를 바꾸면 기존 암호를 사용할 수 없으며 다시 입력해야 함.

운영 인증/역할 기반 권한은 후속 단계다. 현재 관리 키 기능을 내부 서버 인증 완료로 집계하지 않는다. 자세한 실행과 검증 상태는 [INTRANET_PROGRESS.md](INTRANET_PROGRESS.md) 참조.


## Tibero/CUBRID JDBC 확장 (2026-11-25 후속)

- `Credentials.dbms`에 `tibero`, `cubrid` 추가. 모두 정식 공급자 JAR를 관리자 반입하고 `driverId`를 선택하는 방식이다.
- 기본 포트: Tibero 8629, CUBRID broker 33000. 사용자·비밀번호를 URL에 넣지 않는다.
- `TABLEFLOW_TIBERO_ENABLED=false`: 실제 공급자 JAR/서버 미검증으로 기본 차단. 명시적 활성화 없이는 `JDBC_EXPERIMENTAL_DISABLED`.
- `TABLEFLOW_CUBRID_ENABLED=true`: 전역 JDBC 조회/적재 opt-in 설정은 그대로 적용된다.
- `/connections/drivers`의 `capabilities.verifiedTls` 추가. 기존 5종은 true, 두 확장 어댑터는 false. TableFlow 어댑터의 인증서 검증 지원 여부이며 DBMS 자체의 TLS 기능 유무를 뜻하지 않는다.
- Tibero/CUBRID의 `tls=true` 요청은 `JDBC_TLS_UNVERIFIED`, `tls=false` 원격 요청은 기존 `JDBC_TLS_REQUIRED`로 거부한다. 현재는 루프백 DB 테스트 연결만 지원한다.
- 두 어댑터의 `capabilities.preserveKeys=false`: 기존 자동 증가 컬럼에 원본 값을 지정하는 기능은 화면/서버 모두 제한한다. 신규 일반 컬럼의 PK 값 적재는 지원한다.
- CUBRID 신규 테이블/컬럼 이름은 소문자로 지정한다. 자동 매핑/일괄 이관은 소문자로 변환하며 명시적인 대문자 신규 DDL 요청은 거부한다.
- 조회/이관/사전 점검/분할 재개 API 경로는 기존과 같다. CUBRID 소유자 이름을 schema로 전달하고 타입/정밀도/제약 실패는 적재를 롤백한다.
- 임의 SQL이나 드라이버 다운로드 API를 추가하지 않았다. SQL 파일 내보내기 방언은 기존 4종을 유지한다.

검증 범위와 재현 방법: [DBMS_EXPANSION.md](DBMS_EXPANSION.md). Tibero 단위 검증과 CUBRID 실제 DB 검증을 구분한다.
