package dev.friendai.features.acquisition.minecraft;

import dev.friendai.execution.tool.Caller;
import dev.friendai.features.knowledge.search.TargetKind;
import dev.friendai.task.Task;
import java.util.UUID;

/** Composition boundary for search tasks requested by the knowledge tools. */
@FunctionalInterface
public interface ResourceSearchTaskFactory {
    Task create(
            TargetKind fixedTarget,
            String resource,
            String requestedStrategy,
            String scope,
            String heading,
            Caller caller);

    /**
     * Rebuilds a checkpointed search that resumes the named session. The default drops the session link, so a
     * composition that does not support resuming still gets a bounded retry instead of failing the task.
     */
    default Task restore(
            TargetKind fixedTarget,
            String requestedStrategy,
            String scope,
            String heading,
            Caller caller,
            UUID sessionId) {
        return create(fixedTarget, null, requestedStrategy, scope, heading, caller);
    }
}
