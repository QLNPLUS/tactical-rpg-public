package com.tactical.neoforge.skill;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.tactical.core.skill.SkillArea;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Compiles area JSON once; dimensions are resolved from the same cast-local formula bindings. */
public final class SkillAreas {
    public static final double MAX_EXTENT = 64;
    public static final int MAX_SAMPLED_TILES = 4096;
    public static final int MAX_NODES = 100;
    private SkillAreas() {}

    @FunctionalInterface
    public interface Compiled {
        SkillArea resolve(Map<String, Double> variables);
    }

    @FunctionalInterface
    private interface Scalar { double resolve(Map<String, Double> variables); }

    public static Compiled compile(JsonElement definition, Set<String> variables) {
        if (variables == null) throw new IllegalArgumentException("Area variables are required");
        return compileNode(definition, Set.copyOf(variables), new int[] {0}, 0);
    }

    private static Compiled compileNode(JsonElement definition, Set<String> variables, int[] nodes, int depth) {
        if (++nodes[0] > MAX_NODES || depth > 32) {
            throw new IllegalArgumentException("Area exceeds 100 nodes or 32 composition levels");
        }
        if (definition == null || !definition.isJsonObject()) {
            throw new IllegalArgumentException("Area definition must be an object");
        }
        JsonObject object = definition.getAsJsonObject();
        JsonElement typeElement = object.get("type");
        if (typeElement == null || !typeElement.isJsonPrimitive()
                || !typeElement.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Area type must be a string");
        }
        String type = typeElement.getAsString();
        Compiled shape = switch (type) {
            case "circle" -> {
                Scalar radius = dimension(object, "radius", variables);
                yield values -> SkillArea.circle(radius.resolve(values));
            }
            case "rectangle", "line" -> {
                Scalar width = dimension(object, "width", variables);
                Scalar length = dimension(object, "length", variables);
                yield values -> SkillArea.rectangle(width.resolve(values), length.resolve(values));
            }
            case "triangle", "right_triangle" -> {
                if (object.has("points")) {
                    JsonElement pointsElement = object.get("points");
                    if (!pointsElement.isJsonArray() || pointsElement.getAsJsonArray().size() != 3) {
                        throw new IllegalArgumentException("Triangle points must contain exactly three [x,z] pairs");
                    }
                    Scalar[] coordinates = new Scalar[6];
                    JsonArray points = pointsElement.getAsJsonArray();
                    for (int i = 0; i < 3; i++) {
                        if (!points.get(i).isJsonArray() || points.get(i).getAsJsonArray().size() != 2) {
                            throw new IllegalArgumentException("Triangle points must be [x,z] pairs");
                        }
                        coordinates[i * 2] = coordinate(points.get(i).getAsJsonArray().get(0), variables);
                        coordinates[i * 2 + 1] = coordinate(points.get(i).getAsJsonArray().get(1), variables);
                    }
                    yield values -> SkillArea.triangle(coordinates[0].resolve(values), coordinates[1].resolve(values),
                        coordinates[2].resolve(values), coordinates[3].resolve(values),
                        coordinates[4].resolve(values), coordinates[5].resolve(values));
                }
                Scalar size = dimension(object, "size", variables);
                yield values -> SkillArea.rightTriangle(size.resolve(values));
            }
            case "union", "intersection" -> {
                List<Compiled> children = children(object.get("children"), variables, nodes, depth);
                if (children.isEmpty()) throw new IllegalArgumentException("Composite area needs at least one child");
                yield values -> {
                    List<SkillArea> resolved = children.stream().map(child -> child.resolve(values)).toList();
                    return type.equals("union") ? SkillArea.union(resolved) : SkillArea.intersection(resolved);
                };
            }
            case "difference" -> {
                Compiled base = compileNode(object.get("base"), variables, nodes, depth + 1);
                List<Compiled> subtract = children(object.get("subtract"), variables, nodes, depth);
                if (subtract.isEmpty()) throw new IllegalArgumentException("Difference needs at least one subtract area");
                yield values -> SkillArea.difference(base.resolve(values),
                    subtract.stream().map(child -> child.resolve(values)).toList());
            }
            case "transform" -> compileNode(object.get("area"), variables, nodes, depth + 1);
            default -> throw new IllegalArgumentException("Unknown area type: " + type);
        };
        Scalar offsetX = optionalCoordinate(object, "offset_x", variables);
        Scalar offsetZ = optionalCoordinate(object, "offset_z", variables);
        Scalar yaw = object.has("yaw") ? scalar(object.get("yaw"), variables) : values -> 0;
        return values -> {
            if (values == null) throw new IllegalArgumentException("Area bindings are required");
            SkillArea resolved = shape.resolve(values);
            double dx = offsetX.resolve(values), dz = offsetZ.resolve(values), degrees = yaw.resolve(values);
            if (dx != 0 || dz != 0 || degrees != 0) resolved = resolved.transformed(dx, dz, degrees);
            validateBounds(resolved.bounds());
            return resolved;
        };
    }

    private static List<Compiled> children(JsonElement element, Set<String> variables, int[] nodes, int depth) {
        if (element == null || !element.isJsonArray()) {
            throw new IllegalArgumentException("Composite children must be an array");
        }
        if (element.getAsJsonArray().size() > MAX_NODES) throw new IllegalArgumentException("Too many area children");
        List<Compiled> children = new ArrayList<>();
        for (JsonElement child : element.getAsJsonArray()) {
            children.add(compileNode(child, variables, nodes, depth + 1));
        }
        return List.copyOf(children);
    }

    private static Scalar dimension(JsonObject object, String field, Set<String> variables) {
        Scalar scalar = scalar(object.get(field), variables);
        return values -> bounded(scalar.resolve(values), 0, MAX_EXTENT, field);
    }

    private static Scalar coordinate(JsonElement element, Set<String> variables) {
        Scalar scalar = scalar(element, variables);
        return values -> bounded(scalar.resolve(values), -MAX_EXTENT, MAX_EXTENT, "coordinate");
    }

    private static Scalar optionalCoordinate(JsonObject object, String field, Set<String> variables) {
        return object.has(field) ? coordinate(object.get(field), variables) : values -> 0;
    }

    private static Scalar scalar(JsonElement element, Set<String> variables) {
        if (element == null || !element.isJsonPrimitive()) {
            throw new IllegalArgumentException("Area value must be a number or formula string");
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isNumber()) {
            double value = primitive.getAsDouble();
            if (!Double.isFinite(value)) throw new IllegalArgumentException("Area value must be finite");
            return values -> value;
        }
        if (primitive.isString()) {
            SkillFormula.Compiled formula = SkillFormula.compile(primitive.getAsString(), variables);
            return formula::evaluate;
        }
        throw new IllegalArgumentException("Area value must be a number or formula string");
    }

    private static double bounded(double value, double min, double max, String label) {
        if (!Double.isFinite(value) || value < min || value > max) {
            throw new IllegalArgumentException("Area " + label + " must be in [" + min + "," + max + "]");
        }
        return value;
    }

    private static void validateBounds(SkillArea.Bounds bounds) {
        if (bounds.isEmpty()) return;
        double epsilon = SkillArea.EPSILON;
        if (bounds.minX() < -MAX_EXTENT - epsilon || bounds.minZ() < -MAX_EXTENT - epsilon
                || bounds.maxX() > MAX_EXTENT + epsilon || bounds.maxZ() > MAX_EXTENT + epsilon) {
            throw new IllegalArgumentException("Area bounds exceed the local +/-64 extent");
        }
        // Bound integer cell-center candidates even when the actual shape has holes.
        long columns = (long) (Math.ceil(bounds.maxX() - epsilon) - Math.floor(bounds.minX() + epsilon) + 1);
        long rows = (long) (Math.ceil(bounds.maxZ() - epsilon) - Math.floor(bounds.minZ() + epsilon) + 1);
        if (columns * rows > MAX_SAMPLED_TILES) {
            throw new IllegalArgumentException("Area exceeds 4096 sampled tile candidates");
        }
    }
}
