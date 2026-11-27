-- 상태 DB의 첫 버전. 표는 저장을 옮기는 단계마다 새 버전 파일로 추가한다(적용한 파일은 고치지 않는다).
CREATE TABLE app_meta (
    key        varchar(64) PRIMARY KEY,
    value      text        NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);
