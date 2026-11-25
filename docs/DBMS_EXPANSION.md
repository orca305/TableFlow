# DBMS 확장 구현·검증 (2026-11-25)

SQL Server 반영 커밋 `e979448` 이후 사용자 재개 요청에 따른 4~5단계 작업이다. Tibero 실서버 검증은 아직 끝나지 않았다. 6~12단계 구현을 포함하지 않는다.

## 지원 상태

| DBMS | 코드 상태 | 검증 | 기본 활성화 |
|---|---|---|---|
| 기존 Oracle/PostgreSQL/MySQL/MariaDB/SQL Server | 기존 지원 유지 | 전체 실제 DB 회귀에 포함 | JDBC 조회/적재 전역 설정에 따름 |
| Tibero | 실험 어댑터 | 단위 3개, 실제 Tibero 서버 미검증 | `TABLEFLOW_TIBERO_ENABLED=false` |
| CUBRID | 제한 지원 어댑터 | 실제 DB 6개, 실제 브라우저 양방향 2개 | `TABLEFLOW_CUBRID_ENABLED=true` |

전역 `TABLEFLOW_JDBC_ENABLED`와 `TABLEFLOW_JDBC_LOAD_ENABLED`는 기존처럼 기본 비활성화다. Tibero/CUBRID JAR는 배포물에 넣지 않았으며 관리자가 직접 반입한 `driverId`를 사용한다. 화면에 DBMS가 나타나는 것과 실서버 검증 완료는 다르다.

## Tibero

- 클래스 `com.tmax.tibero.jdbc.TbDriver`, 기본 포트 8629, `jdbc:tibero:thin:@host:port:database`.
- `login_timeout=5000`, `read_timeout=10000`(밀리초). 드라이버가 지원하지 않을 수 있는 `Connection.setNetworkTimeout`을 호출하지 않는다.
- NUMBER/VARCHAR2/TIMESTAMP 계열 DDL, 숫자 형태 불리언, 표준 JDBC 메타데이터, Oracle 계열 트리거 조회·잠금 처리를 사용한다.
- 조회는 ROWNUM 래퍼를 사용한다. OFFSET 페이지의 추가 행 번호가 실제 컬럼 이름과 충돌하지 않도록 처리한다. H2 Oracle 모드 테스트는 래퍼 알고리즘 검증이며 실제 Tibero 호환성 검증이 아니다.
- JDBC에서 DATE로 보고되는 값도 시간 부분을 잃지 않도록 Timestamp로 읽는다. 실제 공급자 드라이버와의 타입·기본값·FK·잠금·재개·권한·정밀도 검증이 필요하다.
- 초기 VARCHAR2 생성 범위는 4,000자로 제한했다. Tibero 전체 버전의 네이티브 최대 길이를 보증하는 값이 아니다.
- 정식 JAR·전용 서버가 없어 기본 기능을 켜지 않았다. 비공식 JAR나 라이선스 없는 서버 이미지를 내려받아 검증하지 않았다.

선택 통합 테스트 `TiberoIntegrationTests`는 사용자 전용 fixture를 `localhost:18629`, DB `tibero`, 스키마/계정 `TABLEFLOW`로 준비한 경우에만 실행한다. 테스트 전용 비밀번호는 해당 테스트의 공개 fixture 값이며 운영 계정을 쓰지 않는다. `TABLEFLOW_TEST_TIBERO=true`, `TABLEFLOW_TEST_TIBERO_DRIVER=<정식 JAR 경로>`가 필요하다. 환경이 없는데 건너뛴 결과를 통과로 세지 않는다.

근거: [공급자 JDBC 클래스·URL·JAR 위치](https://support.tibero.com/hc/en-us/articles/13803295594511-dbeaver-Tibero-Integration-Method), [공급자 네트워크 타임아웃 주의](https://support.tibero.com/hc/en-us/articles/13795963612431-Driver-does-not-support-get-set-network-timeout-for-connections-error-occurrence).

## CUBRID

- 클래스 `cubrid.jdbc.driver.CUBRIDDriver`, 기본 broker 포트 33000.
- URL은 사용자·비밀번호를 제외하고 별도 Properties로 전달한다. 연결 제한 5초, 조회 제한 30초, UTF-8 사용.
- `getSchemas()`의 빈 결과를 소유자 목록으로 보완하고, schema 인자를 무시하는 드라이버에서는 메타데이터 테이블 패턴에 소유자를 명시한다. 다른 소유자를 선택했을 때 현재 사용자의 테이블이 섞이지 않도록 검증했다.
- 일부 JAR가 반환하지 않는 자동 증가 표준 필드는 `SHOW COLUMNS`로 확인한다. 밑줄 패턴 이스케이프, 정규화된 테이블 이름, FK 부모 소유자/테이블 분리를 처리한다.
- 신규 테이블/컬럼은 소문자로 지정한다. 단건/일괄 이관 자동 이름 생성은 소문자로 정규화한다. 명시적으로 대문자를 요청한 신규 DDL은 조용히 바꾸지 않고 거부한다.
- CUBRID DDL은 VARCHAR/NUMERIC/BIGINT/DATE/DATETIME/SMALLINT를 사용한다. 불리언 대상은 SMALLINT(0/1)로 생성하며 반대 방향에서는 숫자 컬럼으로 취급된다.
- DATETIME의 밀리초 정밀도를 넘는 값은 잘라 넣지 않고 적재를 거부한다. 변환·정밀도 거부 시 데이터/적재 영수증 트랜잭션을 되돌린다. DDL이 남을 수 있는 점은 기존 신규 테이블 안내와 같다.
- 분할 취소/재개는 기존 요청 ID·적재 영수증을 사용한다. 부모 우선 일괄 이관, 빈 테이블 생성, 인덱스/기본값/FK 복제를 연결했다.

### 현재 제한

Tibero/CUBRID 어댑터의 **인증서·호스트 검증 TLS**는 미구현이다. 공급자 DB 자체에 TLS가 없다는 뜻이 아니다. `tls=true` 요청은 `JDBC_TLS_UNVERIFIED`로 거부하고, 평문 원격 연결도 기존 정책에 따라 거부한다. 현재 두 DB는 TableFlow 서버와 같은 장비의 DB 테스트 연결만 허용한다. 암호화만 켜고 인증서 검증을 생략하는 방식으로 우회하지 않는다.

두 어댑터는 기존 자동 증가 컬럼에 원본 값을 명시하는 옵션을 지원하지 않는다(`capabilities.preserveKeys=false`). 해당 옵션은 화면에서 비활성화하고 서버에서도 거부한다. 신규 일반 컬럼에 원본 키 값을 적재하고 PK를 생성하는 기능과는 별개다. 관리자가 기존 identity/serial 값을 임의로 재설정하는 SQL을 생성하지 않는다.

SQL 파일 내보내기 방언은 기존 4종이다. 이번 작업은 JDBC 조회·직접 적재·DB 이관 확장이다.

## 전용 CUBRID fixture 재현

- `infra/jdbc/compose.cubrid.yml`, 루프백 `13300`만 노출. 기존 프로젝트와 분리된 테스트 DB다.
- 엔진 `11.3.6.1321`, 이미지 `cubrid/cubrid:11.3`의 검증 digest를 Compose에 고정했다.
- DB `tableflow`, locale `ko_KR.utf8`. 서버 이미지에 포함된 JDBC `11.2.1.0040`으로 실제 검증했다. 엔진과 JAR 버전을 동일하다고 표현하지 않는다.
- JAR SHA-256: `8ebfef78e76bf26f507945f923cb30bb8051577ed85b912ed15aea2ccd04b876`.
- JAR는 `backend/build/drivers/`에만 두고 Git에는 포함하지 않았다.

```powershell
docker compose -f infra/jdbc/compose.cubrid.yml up -d --wait
# 먼저 backend/build/drivers 디렉터리를 만든다.
docker cp tableflow-jdbc-tests-cubrid-cubrid-1:/home/cubrid/CUBRID/jdbc/JDBC-11.2.1.0040-cubrid.jar backend/build/drivers/
# Java 21로 backend에서 실행, PostgreSQL은 기존 전용 15432 fixture 사용
$env:TABLEFLOW_TEST_CUBRID='true'
$env:TABLEFLOW_TEST_CUBRID_DRIVER='<위 JAR의 절대 경로>'
.\gradlew.bat test --tests '*Cubrid*' --tests '*TiberoDialectTests'
```

실제 브라우저 테스트는 `frontend/tests/cubrid.spec.ts`에 있다. 백엔드 JDBC 조회/적재와 테스트용 관리자 키를 활성화하고, 드라이버·연결·작업·이력 경로를 `backend/build/browser-db-expansion` 같은 전용 경로로 격리한다. 관리자 키는 실행 시 생성한 일회성 값만 환경으로 전달한다. `TABLEFLOW_TEST_CUBRID=true`, JAR 경로를 설정하고 해당 spec을 실행하면 실제 JAR 등록부터 연결·스키마/테이블 선택·사전 점검·동의·적재·사후 비교를 양방향으로 검증한다. DB를 mock하지 않는다.

근거: [CUBRID JDBC 공식 저장소](https://github.com/CUBRID/cubrid-jdbc), [공식 Docker 이미지 설정](https://github.com/CUBRID/cubrid-docker).

## 실행 결과

- 6종 DB 전체 회귀: 168개 중 **163개 통과·5개 건너뜀·실패 0**. Tibero 실서버 테스트 1개가 건너뜀에 포함된다.
- 이후 CUBRID 소유자 필터 수정과 일괄 이관 검증을 포함한 추가 실행: **11개 통과·실패 0**(CUBRID 실제 6개, CUBRID 단위 2개, Tibero 단위 3개). 위 전체 회귀와 중복되는 테스트를 더해 총 통과 수로 보고하지 않는다.
- 실제 브라우저 CUBRID↔PostgreSQL **2개 통과**, 기존 파일 변환/드라이버/메뉴/모바일 흐름 **7개 통과**(그중 드라이버 2개는 API mock).
- 프런트엔드 타입 검사·Webpack 프로덕션 빌드 통과. 기존 Turbopack 하위 프로세스 종료 오류를 해결했다고 주장하지 않는다.

Tibero 정식 JAR와 전용 서버 정보가 제공되면 해당 어댑터의 실제 호환성을 검증하고 제한을 다시 평가한다. 그 전에는 Tibero 실서버 지원 완료로 표시하지 않는다.
