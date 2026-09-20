package com.labflow.backend.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class JobStateMachineTest {

    private static final Set<Transition> ALLOWED = Set.of(
            transition(JobState.QUEUED, JobState.RUNNING),
            transition(JobState.QUEUED, JobState.FAILED),
            transition(JobState.QUEUED, JobState.CANCELLED),
            transition(JobState.RUNNING, JobState.QUEUED),
            transition(JobState.RUNNING, JobState.SUCCEEDED),
            transition(JobState.RUNNING, JobState.FAILED),
            transition(JobState.RUNNING, JobState.CANCELLED)
    );

    private final JobStateMachine stateMachine = new JobStateMachine();

    @ParameterizedTest(name = "{0} -> {1} is allowed")
    @MethodSource("allowedTransitions")
    void permitsAllowedTransitions(JobState from, JobState to) {
        assertThat(stateMachine.canTransition(from, to)).isTrue();
        stateMachine.requireTransition(from, to);
    }

    @ParameterizedTest(name = "{0} -> {1} is forbidden")
    @MethodSource("forbiddenTransitions")
    void rejectsForbiddenTransitions(JobState from, JobState to) {
        assertThat(stateMachine.canTransition(from, to)).isFalse();
        assertThatThrownBy(() -> stateMachine.requireTransition(from, to))
                .isInstanceOf(InvalidJobStateTransitionException.class)
                .hasMessage("Job state cannot transition from " + from + " to " + to)
                .extracting("from", "to")
                .containsExactly(from, to);
    }

    @ParameterizedTest
    @MethodSource("terminalStates")
    void terminalStatesHaveNoOutgoingTransitions(JobState state) {
        assertThat(state.isTerminal()).isTrue();
        assertThat(Arrays.stream(JobState.values()).noneMatch(target -> stateMachine.canTransition(state, target)))
                .isTrue();
    }

    @Test
    void queuedAndRunningAreNotTerminal() {
        assertThat(JobState.QUEUED.isTerminal()).isFalse();
        assertThat(JobState.RUNNING.isTerminal()).isFalse();
    }

    private static Stream<Arguments> allowedTransitions() {
        return ALLOWED.stream().map(value -> Arguments.of(value.from(), value.to()));
    }

    private static Stream<Arguments> forbiddenTransitions() {
        return Arrays.stream(JobState.values())
                .flatMap(from -> Arrays.stream(JobState.values()).map(to -> transition(from, to)))
                .filter(value -> !ALLOWED.contains(value))
                .map(value -> Arguments.of(value.from(), value.to()));
    }

    private static Stream<JobState> terminalStates() {
        return Stream.of(JobState.SUCCEEDED, JobState.FAILED, JobState.CANCELLED);
    }

    private static Transition transition(JobState from, JobState to) {
        return new Transition(from, to);
    }

    private record Transition(JobState from, JobState to) {
    }
}
