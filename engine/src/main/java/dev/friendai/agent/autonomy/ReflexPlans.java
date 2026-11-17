package dev.friendai.agent.autonomy;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Fixed step lists for routine needs, so the companion handles them without asking the model. */
public final class ReflexPlans {
    // A 5x5 floor and roof, plus two layers of perimeter walls with a two-block doorway.
    private static final int SHELTER_BLOCKS = 80;

    private ReflexPlans() {}

    /** The template for a reflex need, or empty when the need has none or nowhere to go. */
    public static Optional<List<ReflexStep>> plan(Need need, AutonomyFacts f, AutonomySettings s) {
        return switch (need.kind()) {
            case HUNGER -> Optional.of(f.foodPoints() > 0 ? List.of(step("eat")) : List.of(step("get_food")));
            case SLEEP -> f.homeHere().map(home -> List.of(step("go_to_place", "name", home.name()), step("sleep")));
            case FARM_CREATE -> f.homeHere().map(home -> List.of(step("go_to_place", "name", home.name()), farmStep()));
            case UNLOAD ->
                f.storageHere()
                        .or(f::homeHere)
                        .map(place -> List.of(
                                step("go_to_place", "name", place.name()),
                                step("deposit_items", "place", place.name())));
            case SHELTER ->
                f.homeHere()
                        .map(home -> List.of(step("go_to_place", "name", home.name()), step("stay")))
                        .or(() -> {
                            if (canLightInterior(f)) return Optional.of(List.of(step("light_area")));
                            if (!s.allowBuilding() || !f.self().unlitAndExposed()) return Optional.empty();
                            return shelterMaterial(f).map(material -> List.of(buildShelterStep(material)));
                        });
            case SURVIVE -> Optional.of(survivePlan(f));
            case COLLECT ->
                f.homeHere().map(home -> List.of(step("go_to_place", "name", home.name()), step("collect_smelted")));
            default -> Optional.empty();
        };
    }

    /** What to do without the model when the budget is spent, or empty when the need has no template. */
    public static Optional<List<ReflexStep>> fallback(Need need, AutonomyFacts f) {
        return switch (need.kind()) {
            case STOCK -> stockSteps(f);
            case GEAR -> gearSteps(f);
            case PROCESS -> Optional.of(List.of(step("smelt_items")));
            case FOOD -> Optional.of(List.of(step("get_food")));
            case LIGHT -> Optional.of(List.of(step("obtain_item", "item", "minecraft:torch", "count", 16)));
            case BUILD -> shelterMaterial(f).map(material -> List.of(buildShelterStep(material)));
            case STORAGE ->
                f.homeHere()
                        .map(home -> List.of(
                                step("go_to_place", "name", home.name()),
                                step("obtain_item", "item", "minecraft:chest", "count", 1),
                                step("place_block", "block", "minecraft:chest")));
            case FARM ->
                f.farms().stream()
                        .filter(FarmRef::sameDimension)
                        .max(Comparator.comparingLong(FarmRef::millisSinceVisit))
                        .map(farm -> List.of(step("tend_farm", "place", farm.name())));
            default -> Optional.empty();
        };
    }

    /** The template for a need kind, for callers that only have the stored shift's kind. */
    public static Optional<List<ReflexStep>> fallback(NeedKind kind, AutonomyFacts f) {
        return fallback(new Need(kind, 50, Executor.DIRECTOR, ""), f);
    }

    private static List<ReflexStep> survivePlan(AutonomyFacts f) {
        List<ReflexStep> steps = new ArrayList<>();
        f.homeHere().ifPresent(home -> steps.add(step("go_to_place", "name", home.name())));
        if (f.foodPoints() > 0) {
            steps.add(step("eat"));
        }
        if (f.homeHere().isEmpty() && canLightInterior(f)) {
            steps.add(step("light_area"));
        }
        steps.add(step("stay"));
        return steps;
    }

    private static ReflexStep farmStep() {
        JsonObject args = new JsonObject();
        args.addProperty("radius", 4);
        args.addProperty("till", true);
        args.addProperty("crop", "minecraft:wheat_seeds");
        return new ReflexStep("farm", args);
    }

    static boolean canShelterWithoutHome(AutonomyFacts f, AutonomySettings settings) {
        return canLightInterior(f)
                || (f.self().unlitAndExposed()
                        && settings.allowBuilding()
                        && shelterMaterial(f).isPresent());
    }

    private static boolean canLightInterior(AutonomyFacts f) {
        return f.self().unlitInterior() && f.count(StockKind.TORCHES) > 0;
    }

    private static Optional<String> shelterMaterial(AutonomyFacts f) {
        if (f.self().cobblestone() >= SHELTER_BLOCKS) return Optional.of("minecraft:cobblestone");
        if (f.self().oakPlanks() >= SHELTER_BLOCKS) return Optional.of("minecraft:oak_planks");
        return Optional.empty();
    }

    /** A simple house built from one material; BuildTask remembers it as a home. */
    private static ReflexStep buildShelterStep(String material) {
        JsonObject args = new JsonObject();
        args.addProperty("blueprint", """
                {"name":"shelter","palette":{"B":"%s","A":"minecraft:air"},"ops":[
                  {"op":"box","from":[0,0,0],"to":[4,0,4],"block":"B"},
                  {"op":"walls","from":[0,1,0],"to":[4,2,4],"block":"B"},
                  {"op":"box","from":[2,1,4],"to":[2,2,4],"block":"A"},
                  {"op":"box","from":[0,3,0],"to":[4,3,4],"block":"B"}
                ]}
                """.formatted(material));
        return new ReflexStep("build_structure", args);
    }

    /** Gets the item the stage is short of, one step per kind, so a spent budget still moves the ladder. */
    private static Optional<List<ReflexStep>> stockSteps(AutonomyFacts f) {
        List<ReflexStep> steps = new ArrayList<>();
        ProgressionLadder.missingStock(f).forEach((kind, missing) -> {
            if (kind == StockKind.LOGS) {
                steps.add(step("fell_tree"));
                return;
            }
            kind.itemId().ifPresent(id -> {
                JsonObject args = new JsonObject();
                args.addProperty("item", id);
                args.addProperty("count", Math.max(1, Math.min(64, missing)));
                steps.add(new ReflexStep("obtain_item", args));
            });
        });
        return steps.isEmpty() ? Optional.empty() : Optional.of(List.copyOf(steps));
    }

    /** Asks for the missing tool, armor or station, so a spent budget still closes the ladder gap. */
    private static Optional<List<ReflexStep>> gearSteps(AutonomyFacts f) {
        List<ReflexStep> steps = new ArrayList<>();
        for (String item : ProgressionLadder.missingGearItemIds(f)) {
            steps.add(step("obtain_item", "item", item, "count", 1));
        }
        return steps.isEmpty() ? Optional.empty() : Optional.of(List.copyOf(steps));
    }

    private static ReflexStep step(String tool) {
        return new ReflexStep(tool, new JsonObject());
    }

    private static ReflexStep step(String tool, String key, String value) {
        JsonObject args = new JsonObject();
        args.addProperty(key, value);
        return new ReflexStep(tool, args);
    }

    private static ReflexStep step(String tool, String key, String value, String numberKey, int number) {
        JsonObject args = new JsonObject();
        args.addProperty(key, value);
        args.addProperty(numberKey, number);
        return new ReflexStep(tool, args);
    }
}
