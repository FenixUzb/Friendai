package dev.friendai.agent.recover;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The one blocked job a recovery episode belongs to, and the remedies already spent on it.
 *
 * <p>A plan that cannot continue opens an incident for the job it stopped at. Every later stand-alone failure is a
 * remedy of that incident, whatever tool started it and whatever the wording of the cause, so a chain of unrelated
 * jobs cannot reset the same blocker. The counter only takes account of facts that actually changed: the {@code
 * facts:} labels of an outcome are compared with digits erased, so a shifted coordinate or a different stack size
 * buys no new budget. After {@link #FAILURE_BUDGET} failures without progress the incident is paused and the turn
 * that carries it sends no tools, so the companion reports one blocker instead of trying again.
 *
 * <p>This is a runtime memory, not persisted state: a reload starts a fresh episode, and an owner line, a settled
 * plan, a cancellation or an applied repair ends the current one.
 */
public final class RecoveryIncidentBook {
    /** Failed or no-progress remedies allowed for one incident before the turn offers no tools. */
    public static final int FAILURE_BUDGET = 3;

    static final int MAX_INCIDENTS = 8;
    static final int MAX_REMEDIES = 6;
    private static final int MAX_DETAIL = 120;
    private static final String FACTS_PREFIX = "facts: ";

    /** What the model and the owner line may read about the open incident. */
    public record View(long planId, long jobId, FailureKind kind, String detail, int failures, List<String> remedies) {
        public View {
            remedies = List.copyOf(remedies);
        }
    }

    private record Incident(
            long planId,
            long jobId,
            FailureKind kind,
            String detail,
            Set<String> evidence,
            int failures,
            List<String> remedies) {}

    private final RecoveryAdvisor advisor;
    /** Insertion order is least recently touched first; re-touching an incident moves it to the end. */
    private final LinkedHashMap<String, Incident> incidents = new LinkedHashMap<>();

    private String activeKey;

    public RecoveryIncidentBook(RecoveryAdvisor advisor) {
        this.advisor = Objects.requireNonNull(advisor);
    }

    /**
     * A plan cannot continue at one job. Repeating the same blocked job charges the same incident; a block names the
     * episode even when the plan's own failure arrived after other work.
     */
    public void blocked(long planId, long jobId, String tool, String reason, String extra) {
        Incident active = activeIncident();
        // A second plan that blocks while the first one is still stuck is another failed remedy, not a new episode:
        // otherwise every replacement plan would buy a fresh budget and the chain would never stop.
        boolean anotherPlanOrJob =
                active != null && active.planId() > 0 && (active.planId() != planId || active.jobId() != jobId);
        if (anotherPlanOrJob) {
            put(activeKey, charge(active, tool, evidenceOf(extra)));
            return;
        }
        Diagnosis diagnosis = advisor.diagnose(tool, reason, Set.of());
        String key = "plan:" + planId + ":" + jobId;
        Incident incident = incidents.getOrDefault(
                key, new Incident(planId, jobId, diagnosis.kind(), detail(diagnosis), Set.of(), 0, List.of()));
        put(key, charge(incident, tool, evidenceOf(extra)));
        activeKey = key;
    }

    /**
     * A task failed. While an incident is open this failure is one of its remedies; otherwise a typed cause opens an
     * incident of its own. An unclassifiable failure forms no incident, because grouping unrelated causes would gate
     * work that has nothing to do with the blocker.
     *
     * @param planId the queue plan that owned the task, or zero for a stand-alone job
     */
    public void failed(long planId, String tool, String summary, String extra) {
        Diagnosis diagnosis = advisor.diagnose(tool, summary, Set.of());
        Set<String> evidence = evidenceOf(extra);
        Incident active = activeIncident();
        // Everything that fails while a blocked plan is open is a remedy of that episode: the companion reaches for
        // another tool, another plan or another site, and none of it resets the blocker. A direct incident takes
        // only the same typed cause: another wording or shifted coordinates must not reset it, but a genuinely
        // different cause is a different trouble.
        boolean remedyOfTheOpenPlan = active != null && active.planId() > 0;
        boolean sameDirectCause = active != null
                && active.planId() == 0
                && planId == 0
                && diagnosis.kind() == active.kind()
                && normalize(diagnosis.detail()).equals(normalize(active.detail()));
        if (remedyOfTheOpenPlan || sameDirectCause) {
            String key = activeKey;
            put(key, charge(active, tool, evidence));
            activeKey = key;
            return;
        }
        if (diagnosis.kind() == FailureKind.UNKNOWN) {
            return;
        }
        String key = planId > 0
                ? "plan:" + planId + ":0"
                : "cause:" + diagnosis.kind().name() + ":" + normalize(diagnosis.detail());
        Incident incident = incidents.getOrDefault(
                key, new Incident(planId, 0, diagnosis.kind(), detail(diagnosis), Set.of(), 0, List.of()));
        put(key, charge(incident, tool, evidence));
        activeKey = key;
    }

    /** A tool started while the incident is open: one more remedy the owner can be told about. */
    public void attempted(String tool) {
        Incident active = activeIncident();
        if (active == null || tool == null || tool.isBlank()) {
            return;
        }
        put(
                activeKey,
                new Incident(
                        active.planId(),
                        active.jobId(),
                        active.kind(),
                        active.detail(),
                        active.evidence(),
                        active.failures(),
                        withRemedy(active.remedies(), tool)));
    }

    /** A planner consultation that produced nothing is as much a spent remedy as a task that failed. */
    public void noProgress(long planId) {
        String key = incidentKeyFor(planId);
        if (key == null) {
            return;
        }
        Incident incident = incidents.get(key);
        put(key, charge(incident, "planner", Set.of()));
        activeKey = key;
    }

    /** A repair was applied, so the incident starts its budget again even if the plan still has work left. */
    public void repaired(long planId) {
        String key = incidentKeyFor(planId);
        if (key == null) {
            return;
        }
        Incident incident = incidents.get(key);
        put(
                key,
                new Incident(
                        incident.planId(),
                        incident.jobId(),
                        incident.kind(),
                        incident.detail(),
                        Set.of(),
                        0,
                        List.of()));
        activeKey = key;
    }

    /** A task succeeded: the incident's own job settles, and progress elsewhere resets its budget. */
    public void succeeded(long planId) {
        Incident active = activeIncident();
        if (active == null) {
            return;
        }
        if (active.planId() == planId) {
            remove(activeKey);
            return;
        }
        String key = activeKey;
        put(
                key,
                new Incident(
                        active.planId(),
                        active.jobId(),
                        active.kind(),
                        active.detail(),
                        Set.of(),
                        0,
                        active.remedies()));
        activeKey = key;
    }

    /** The plan that owned the incident ended, however it ended. */
    public void settled(long planId) {
        List<String> keys = new ArrayList<>();
        for (var entry : incidents.entrySet()) {
            if (entry.getValue().planId() == planId) {
                keys.add(entry.getKey());
            }
        }
        keys.forEach(this::remove);
    }

    /** The owner stopped the work: the episode is over and a later failure starts a fresh one. */
    public void cancelled() {
        incidents.clear();
        activeKey = null;
    }

    /** An explicit owner line makes another attempt eligible; it is the owner's decision, not the model's. */
    public void ownerSpoke() {
        Incident active = activeIncident();
        if (active == null) {
            return;
        }
        String key = activeKey;
        put(key, new Incident(active.planId(), active.jobId(), active.kind(), active.detail(), Set.of(), 0, List.of()));
        activeKey = key;
    }

    public Optional<View> active() {
        return Optional.ofNullable(activeIncident())
                .map(incident -> new View(
                        incident.planId(),
                        incident.jobId(),
                        incident.kind(),
                        incident.detail(),
                        incident.failures(),
                        incident.remedies()));
    }

    /** The open incident has spent its whole budget without a relevant fact changing. */
    public boolean paused() {
        Incident active = activeIncident();
        return active != null && active.failures() >= FAILURE_BUDGET;
    }

    /** One model-facing line for a paused incident, or empty while the incident may still be worked on. */
    public String modelLine() {
        View view = active().filter(ignored -> paused()).orElse(null);
        if (view == null) {
            return "";
        }
        StringBuilder line = new StringBuilder("[recovery] paused");
        if (view.planId() > 0) {
            line.append(" at plan #").append(view.planId());
        }
        if (view.jobId() > 0) {
            line.append(view.planId() > 0 ? " job #" : " at job #").append(view.jobId());
        }
        line.append(": ")
                .append(view.detail())
                .append("; ")
                .append(view.failures())
                .append(" attempts made no progress");
        if (!view.remedies().isEmpty()) {
            line.append(" (").append(String.join(", ", view.remedies())).append(')');
        }
        line.append(
                ". Do not start, queue or edit more work for it; say in one short line what blocks you and which decision you need from the owner.");
        return line.toString();
    }

    /** The typed cause, short enough to be an argument of the owner-facing fallback line. */
    public String detail() {
        return active().map(View::detail).orElse("");
    }

    /** How many incidents are remembered; the bound keeps a long session from growing this book. */
    int size() {
        return incidents.size();
    }

    private Incident activeIncident() {
        return activeKey == null ? null : incidents.get(activeKey);
    }

    private String incidentKeyFor(long planId) {
        if (activeKey != null) {
            Incident active = incidents.get(activeKey);
            if (active != null && active.planId() == planId) {
                return activeKey;
            }
        }
        for (var entry : incidents.entrySet()) {
            if (entry.getValue().planId() == planId) {
                return entry.getKey();
            }
        }
        return null;
    }

    private void put(String key, Incident incident) {
        incidents.remove(key);
        incidents.put(key, incident);
        while (incidents.size() > MAX_INCIDENTS) {
            String eldest = incidents.keySet().iterator().next();
            incidents.remove(eldest);
            if (eldest.equals(activeKey)) {
                activeKey = null;
            }
        }
    }

    private void remove(String key) {
        incidents.remove(key);
        if (key.equals(activeKey)) {
            activeKey = null;
        }
    }

    /**
     * One failure. A fact label this incident has not seen before is real progress and starts the budget again;
     * anything else is another attempt at the same blocker. The first failure only establishes the baseline, because
     * there is nothing yet to compare it with.
     */
    private static Incident charge(Incident incident, String tool, Set<String> evidence) {
        boolean newEvidence = !incident.evidence().isEmpty()
                && evidence.stream().anyMatch(label -> !incident.evidence().contains(label));
        Set<String> known = new LinkedHashSet<>(incident.evidence());
        known.addAll(evidence);
        int failures = newEvidence ? 1 : incident.failures() + 1;
        return new Incident(
                incident.planId(),
                incident.jobId(),
                incident.kind(),
                incident.detail(),
                known,
                failures,
                withRemedy(incident.remedies(), tool));
    }

    private static List<String> withRemedy(List<String> remedies, String tool) {
        if (tool == null || tool.isBlank() || remedies.contains(tool)) {
            return remedies;
        }
        List<String> next = new ArrayList<>(remedies);
        if (next.size() >= MAX_REMEDIES) {
            next.remove(0);
        }
        next.add(tool);
        return List.copyOf(next);
    }

    private static String detail(Diagnosis diagnosis) {
        String detail = diagnosis.detail() == null ? "" : diagnosis.detail().strip();
        if (detail.length() > MAX_DETAIL) {
            detail = detail.substring(0, MAX_DETAIL);
        }
        return detail.isEmpty() ? "unclear cause" : detail;
    }

    /**
     * The fact labels of an outcome: {@code facts: +2 cobblestone, empty slots 12 (was 15)} becomes {@code [+
     * cobblestone, empty slots (was )]}. Counts are erased so the same fact at a different size is not progress.
     */
    static Set<String> evidenceOf(String extra) {
        if (extra == null) {
            return Set.of();
        }
        int at = extra.indexOf(FACTS_PREFIX);
        if (at < 0) {
            return Set.of();
        }
        String facts = extra.substring(at + FACTS_PREFIX.length());
        int end = facts.indexOf("; ");
        if (end >= 0) {
            facts = facts.substring(0, end);
        }
        Set<String> labels = new LinkedHashSet<>();
        for (String fact : facts.split(",")) {
            String label = eraseCounts(fact).replaceAll("\\s+", " ").strip();
            if (!label.isEmpty()) {
                labels.add(label);
            }
        }
        return labels;
    }

    private static String normalize(String text) {
        return text == null
                ? ""
                : eraseCounts(text).replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
    }

    /**
     * Erases a standalone number, so a shifted coordinate, a count or a stack size reads as the same text, while a
     * digit that is part of a name ({@code minecraft:item_3}, {@code oak_log2}) stays and keeps the names distinct.
     */
    private static String eraseCounts(String text) {
        return text.replaceAll("(?<!\\w)\\d+(?!\\w)", "");
    }
}
