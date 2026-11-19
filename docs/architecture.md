# 아키텍처

```mermaid
flowchart LR
    U[Next.js 브라우저 상태] -->|multipart 파일| P[Spring Boot FileParser]
    P -->|Canonical Table 1.0| U
    U -->|JSON 표와 매핑| T[TableEngine 정규화·검증]
    T --> U
    U --> E[TableExporter]
    E --> S[Oracle / MySQL / PostgreSQL / MariaDB SQL 파일]
    E --> X[XLSX / CSV 파일]
    P --> D[PDFBox 텍스트 좌표 추출]
    P --> O[FastAPI OCR 서비스]
    O --> M[Mock / Local RapidOCR / External Gateway]
    M -->|Table 1.0 + 신뢰도·페이지| U
    U --> L[DatabaseLoader 스키마 검사·PreparedStatement]
    L -->|단일 트랜잭션| DB[MySQL 대상 + 적재 영수증]
```

## 현재 구현

- Controller: HTTP 입출력만 담당. DTO의 Jakarta Validation과 서비스 검증을 함께 수행합니다.
- FileParser: 실제 Apache POI / Commons CSV 파싱. 파일을 디스크에 영구 저장하지 않습니다.
- Table: 컬럼 ID, raw/value, 수정 여부, 신뢰도(null), 버전이 있는 Java record 기반 공통 계약입니다.
- TableEngine: 규칙 기반 정규화, 필수·자료형·길이·정밀도·중복·셀 키 정합성 검사입니다.
- TableExporter: Oracle/MySQL/PostgreSQL/MariaDB enum dialect로 인용·리터럴을 분리합니다. 출력 전에 반드시 재검증합니다.
- 프런트엔드는 표를 메모리에 유지합니다. 50행 단위로 표시하며 편집 시 검증 결과를 무효화합니다.
- 작업 이력용 내부 DB/인증/스케줄러/큐는 없습니다. 추가 승인된 실제 적재 대상 MySQL과 중복 방지용 영수증은 사용합니다.

## 보안과 경계

XLSX ZIP 시그니처와 압축 해제량을 검사한 후 POI로 읽습니다. CSV는 허용 인코딩으로 엄격하게 디코딩하고 바이너리 제어 문자를 거부합니다. CSV 자체에는 고유 시그니처가 없어 확장자·디코딩·구조를 함께 확인합니다.

SQL 이름은 영문/숫자/밑줄 최대 64자의 단일 식별자로 제한합니다. 항상 인용하고 문자열 작은따옴표를 이스케이프합니다. MySQL 문자열은 SQL mode에 따른 백슬래시 해석 차이를 없애기 위해 UTF-8 HEX 리터럴로 출력합니다. 숫자는 BigDecimal 기반 문자열을 유지합니다.

Excel 수식을 읽을 때 실행하지 않고 수식 텍스트를 보존합니다. 출력에는 문자열 셀만 사용합니다. 파싱·오류 처리에서 업로드 내용을 로그로 기록하지 않습니다. HTTP multipart 임시 파일은 요청 종료 시 컨테이너가 정리합니다.

## Python OCR 통합

DocumentParser는 텍스트 PDF를 PDFBox로 처리하고 이미지·스캔 페이지는 OcrClient를 통해 FastAPI에 전달합니다. Python은 별도 작업 프로세스에서 실제 모델 추론/PDF 렌더링을 수행하며 deadline 초과 시 종료합니다. Java는 수신 계약을 다시 검증하고 사용자 원본 파일명으로 출처를 고정합니다.

OCR은 mock/local/external 공급자를 분리했습니다. 외부 전송에는 서버 opt-in과 파일별 동의가 모두 필요하며 로컬 실패 시 자동 전환하지 않습니다. 실제 로컬 모델로 4종 샘플과 전체 브라우저 흐름을 검증했습니다. 외부 어댑터는 모의 HTTP 계약만 검증했습니다. 상세: [OCR 운영·검증](ocr.md).

## 다음 단계

MySQL 직접 적재는 DatabaseLoader에서 기존 공통 검증 후 JDBC 바인딩·250행 배치·단일 트랜잭션으로 수행합니다. 대상 엔진/매핑/필수값/정밀도를 검사하고 메타데이터 잠금을 유지합니다. 대상 데이터와 영수증을 함께 커밋하여 재시도·동시 요청이 중복 적재되지 않도록 합니다. API는 로컬 전용입니다. [DB 적재 상세](database-loading.md)

공개 배포는 별도 Java 호스팅 선정 후 진행해야 합니다. 현재 로컬 UI를 공개 Live Demo로 표시하지 않습니다.

## 2026-11-19 워크스페이스 개편

파일 → Canonical Table → 정규화/검증 → 파일 출력 흐름을 유지했습니다. 범용 플러그인 프레임워크를 미리 도입하지 않고 기존 Dialect enum을 확장했습니다. 검증 severity와 중복 정책은 TableEngine에서 공유하고, 모든 파일 출력은 서버에서 재검증합니다. 화면의 이름 제안은 헤더 규칙 기반이며 실제 DB 스키마와 매칭한 결과가 아닙니다. JDBC 범용 연결·메타데이터 탐색·DB 소스 추출은 아직 구현하지 않았습니다. 기존 MySQL 적재의 PreparedStatement/트랜잭션/서버 설정 경계는 유지합니다.
