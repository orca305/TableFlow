-- 작업 이력. 비밀번호·호스트·사용자명·행 데이터는 담지 않는다. seq는 기록한 순서(최신순 조회와 오래된 항목 정리 기준)다.
CREATE TABLE job_history (
    id          uuid         PRIMARY KEY,
    seq         bigint       GENERATED ALWAYS AS IDENTITY UNIQUE,
    kind        varchar(20)  NOT NULL,
    request_id  text,
    started_at  timestamptz  NOT NULL,
    duration_ms bigint       NOT NULL,
    status      varchar(20)  NOT NULL,
    source      text,
    target      text,
    row_count   integer      NOT NULL,
    error_code  varchar(64),
    message     varchar(300)
);
