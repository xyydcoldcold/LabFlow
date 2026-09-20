package com.labflow.backend.job;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Component;

@Component
public class JobStateMachine {

    private static final Map<JobState, Set<JobState>> ALLOWED_TRANSITIONS = allowedTransitions();

    public boolean canTransition(JobState from, JobState to) {
        Objects.requireNonNull(from, "Current job state is required");
        Objects.requireNonNull(to, "Target job state is required");
        return ALLOWED_TRANSITIONS.get(from).contains(to);
    }

    public void requireTransition(JobState from, JobState to) {
        if (!canTransition(from, to)) {
            throw new InvalidJobStateTransitionException(from, to);
        }
    }

    private static Map<JobState, Set<JobState>> allowedTransitions() {
        EnumMap<JobState, Set<JobState>> transitions = new EnumMap<>(JobState.class);
        transitions.put(JobState.QUEUED, EnumSet.of(JobState.RUNNING, JobState.FAILED, JobState.CANCELLED));
        transitions.put(JobState.RUNNING, EnumSet.of(
                JobState.QUEUED,
                JobState.SUCCEEDED,
                JobState.FAILED,
                JobState.CANCELLED
        ));
        transitions.put(JobState.SUCCEEDED, EnumSet.noneOf(JobState.class));
        transitions.put(JobState.FAILED, EnumSet.noneOf(JobState.class));
        transitions.put(JobState.CANCELLED, EnumSet.noneOf(JobState.class));
        return Map.copyOf(transitions);
    }
}
