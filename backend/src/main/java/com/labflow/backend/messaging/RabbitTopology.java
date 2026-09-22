package com.labflow.backend.messaging;

public final class RabbitTopology {

    public static final String JOBS_EXCHANGE = "labflow.jobs.exchange";
    public static final String JOBS_QUEUE = "labflow.jobs.q";
    public static final String JOBS_ROUTING_KEY = "jobs.run";

    public static final String RETRY_15S_QUEUE = "labflow.jobs.retry.15s.q";
    public static final String RETRY_60S_QUEUE = "labflow.jobs.retry.60s.q";
    public static final String RETRY_300S_QUEUE = "labflow.jobs.retry.300s.q";
    public static final String RETRY_15S_ROUTING_KEY = "jobs.retry.15s";
    public static final String RETRY_60S_ROUTING_KEY = "jobs.retry.60s";
    public static final String RETRY_300S_ROUTING_KEY = "jobs.retry.300s";

    public static final String JOBS_DLX = "labflow.jobs.dlx";
    public static final String JOBS_DLQ = "labflow.jobs.dlq";
    public static final String JOBS_DEAD_ROUTING_KEY = "jobs.dead";

    private RabbitTopology() {
    }
}
