package dev.friendai.features.acquisition.minecraft.task;

import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.features.acquisition.minecraft.ResourceSearchTaskFactory;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskState;
import java.util.Optional;

/**
 * Checkpoint decoding for resource searches, contributed beside their definitions. The saved actor is only a
 * principal: its access is re-resolved from current owner and trust data before the task is rebuilt, and a caller
 * that no longer holds the minimum access gets no task rather than a stale authority.
 */
public final class SearchResourceCheckpoints {
    private SearchResourceCheckpoints() {}

    public static Optional<Task> search(TaskState state, TaskContext context, ResourceSearchTaskFactory factory) {
        if (state == null || context == null || factory == null || !SearchResourceTask.TYPE.equals(state.type()))
            return Optional.empty();
        Optional<SearchResourceTask.Saved> saved = SearchResourceTask.Saved.decode(state.data());
        if (saved.isEmpty()) return Optional.empty();
        Caller caller = context.currentCaller(saved.get().principal());
        if (caller == null || caller.access().ordinal() < AccessLevel.TRUSTED.ordinal()) return Optional.empty();
        return Optional.ofNullable(factory.restore(
                saved.get().target(),
                saved.get().strategy(),
                saved.get().scope(),
                saved.get().heading(),
                caller,
                saved.get().session()));
    }
}
