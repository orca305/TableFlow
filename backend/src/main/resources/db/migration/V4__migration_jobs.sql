-- 이관 작업의 상태와 다시 실행할 수 있는 정의(비밀번호 제외). owner_id는 지금 실행 중인 서버, heartbeat_at은 그 서버의 마지막 생존 신호다.
-- seq는 제출 순서(다시 제출하면 맨 뒤)이며 목록 순서와 보관 개수 정리의 기준이다.
CREATE SEQUENCE migration_job_seq;
CREATE TABLE migration_job (
    id               varchar(64)  PRIMARY KEY,
    seq              bigint       NOT NULL DEFAULT nextval('migration_job_seq') UNIQUE,
    state            varchar(20)  NOT NULL,
    status           jsonb        NOT NULL,
    definition       jsonb,
    source_label     text         NOT NULL DEFAULT '',
    target_label     text         NOT NULL DEFAULT '',
    created_target   text,
    owner_id         uuid,
    heartbeat_at     timestamptz  NOT NULL DEFAULT now(),
    cancel_requested boolean      NOT NULL DEFAULT false,
    updated_at       timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX migration_job_state ON migration_job (state);
