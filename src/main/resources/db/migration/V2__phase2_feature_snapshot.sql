create table loan_feature_snapshot (
    snapshot_id uuid primary key,
    application_id uuid not null references loan_application(application_id),
    financial_snapshot_id uuid not null references financial_snapshot(snapshot_id),
    snapshot_version varchar(64) not null,
    snapshot_schema_version varchar(32) not null,
    feature_schema_version varchar(64) not null,
    feature_payload jsonb not null,
    feature_payload_digest varchar(71) not null,
    snapshot_reference jsonb not null,
    source_loan_application_version integer not null,
    created_at timestamp with time zone not null,
    constraint uq_loan_feature_snapshot_version unique (application_id, snapshot_version),
    constraint uq_loan_feature_snapshot_financial unique (financial_snapshot_id),
    constraint ck_loan_feature_snapshot_digest check (feature_payload_digest like 'sha256:%' and length(feature_payload_digest) = 71),
    constraint ck_loan_feature_snapshot_source_version check (source_loan_application_version > 0)
);
