package com.labflow.backend.job;

public class InvalidJobStateTransitionException extends IllegalStateException {

    private final JobState from;
    private final JobState to;

    public InvalidJobStateTransitionException(JobState from, JobState to) {
        super("Job state cannot transition from " + from + " to " + to);
        this.from = from;
        this.to = to;
    }

    public JobState getFrom() {
        return from;
    }

    public JobState getTo() {
        return to;
    }
}
