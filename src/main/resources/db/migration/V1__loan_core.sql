create table loan_application (
    application_id uuid primary key,
    idempotency_key varchar(128) not null unique,
    request_hash char(64) not null,
    status varchar(64) not null,
    snapshot_version integer not null,
    requested_amount numeric(19,2) not null,
    currency varchar(3) not null,
    applicant_reference varchar(255) not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    version bigint not null
);

create table financial_snapshot (
    snapshot_id uuid primary key,
    application_id uuid not null references loan_application(application_id),
    version integer not null,
    snapshot_version varchar(64) not null,
    applicant_reference varchar(255) not null,
    requested_amount numeric(19,2) not null,
    currency varchar(3) not null,
    debt_total_outstanding_amount numeric(19,2) not null,
    debt_monthly_payment_amount numeric(19,2) not null,
    delinquency_count integer not null,
    days_past_due_maximum integer not null,
    settlement_period varchar(64) not null,
    gross_settlement_amount numeric(19,2) not null,
    debt_source_references text not null,
    delinquency_source_references text not null,
    settlement_source_references text not null,
    risk_signal_references text not null,
    request_payload jsonb not null,
    created_at timestamp with time zone not null,
    constraint uq_financial_snapshot_version unique (application_id, version)
);

create table monthly_income (
    monthly_income_id uuid primary key,
    snapshot_id uuid not null references financial_snapshot(snapshot_id) on delete cascade,
    period varchar(7) not null,
    amount numeric(19,2) not null,
    source_reference varchar(255) not null,
    constraint uq_monthly_income_period unique (snapshot_id, period)
);

create table loan_decision (
    decision_record_id uuid primary key,
    application_id uuid not null references loan_application(application_id),
    command_id uuid not null unique,
    decision_case_id varchar(128) not null,
    decision_id uuid not null,
    evaluation_run_id uuid not null,
    final_decision varchar(32) not null,
    reason_codes text not null,
    issued_at timestamp with time zone not null,
    applied_at timestamp with time zone not null,
    constraint uq_loan_decision_application unique (application_id)
);

create table outbox_event (
    event_id uuid primary key,
    event_type varchar(128) not null,
    schema_version varchar(32) not null,
    aggregate_id uuid not null,
    correlation_id varchar(128) not null,
    causation_id uuid,
    payload jsonb not null,
    status varchar(32) not null,
    attempts integer not null,
    next_attempt_at timestamp with time zone not null,
    published_at timestamp with time zone,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

create index ix_outbox_status_next_attempt on outbox_event(status, next_attempt_at);

create table inbox_event (
    event_id uuid primary key,
    event_type varchar(128) not null,
    command_id uuid unique,
    payload_hash char(64) not null,
    processed_at timestamp with time zone not null
);
