package com.tactical.neoforge.skill;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import net.yiran.expressionlib.expr.Expression;
import net.yiran.expressionlib.expr.ExpressionBuilder;
import net.yiran.expressionlib.expr.Function;

/** Compiled numeric formulas with explicit inputs and deterministic, finite results. */
public final class SkillFormula {
    public static final int MAX_SOURCE_LENGTH = 4096;
    private static final Pattern RANDOM_CALL = Pattern.compile("\\brandom\\s*\\(");

    private SkillFormula() {}

    public static Compiled compile(String source, Set<String> allowedVariables) {
        if (source == null || source.isBlank() || source.length() > MAX_SOURCE_LENGTH) {
            throw new IllegalArgumentException("Formula must contain 1.." + MAX_SOURCE_LENGTH + " characters");
        }
        if (allowedVariables == null) throw new IllegalArgumentException("Allowed variables are required");
        if (RANDOM_CALL.matcher(source).find()) {
            throw new IllegalArgumentException("Unseeded random() is disabled in skill formulas");
        }
        // Bound recursive parser depth independently of the total source length.
        int depth = 0, conditionals = 0, punctuation = 0;
        for (int i = 0; i < source.length(); i++) {
            char token = source.charAt(i);
            if (!Character.isLetterOrDigit(token) && !Character.isWhitespace(token)
                    && token != '_' && token != '.' && ++punctuation > 256) {
                throw new IllegalArgumentException("Formula operator complexity exceeds 256");
            }
            if (token == '(' && ++depth > 64) throw new IllegalArgumentException("Formula nesting exceeds 64");
            if (token == ')') depth--;
            if (token == '?' && ++conditionals > 64) throw new IllegalArgumentException("Formula conditionals exceed 64");
        }
        try {
            Expression expression = new ExpressionBuilder(source)
                .function(new Function("clamp", 3) {
                    @Override public double apply(double... args) {
                        return clamp(args[0], args[1], args[2]);
                    }
                    @Override public double apply3(double value, double min, double max) {
                        return clamp(value, min, max);
                    }
                }).build();
            Set<String> variables = Set.copyOf(expression.getVariableNames());
            if (!allowedVariables.containsAll(variables)) {
                Set<String> unknown = new java.util.HashSet<>(variables);
                unknown.removeAll(allowedVariables);
                throw new IllegalArgumentException("Unknown formula variables: " + unknown);
            }
            return new Compiled(source, variables, expression);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid skill formula: " + source, exception);
        }
    }

    private static double clamp(double value, double min, double max) {
        if (min > max) throw new IllegalArgumentException("clamp minimum exceeds maximum");
        return Math.max(min, Math.min(max, value));
    }

    /** Safe to cache and share: evaluation never mutates the underlying expression's bindings. */
    public static final class Compiled {
        private final String source;
        private final Set<String> variables;
        private final Expression expression;

        private Compiled(String source, Set<String> variables, Expression expression) {
            this.source = source;
            this.variables = variables;
            this.expression = expression;
        }

        public String source() { return source; }
        public Set<String> variables() { return variables; }

        public double evaluate(Map<String, Double> bindings) {
            if (bindings == null) throw new IllegalArgumentException("Formula bindings are required");
            Map<String, Double> local = new HashMap<>();
            for (String variable : variables) {
                Double value = bindings.get(variable);
                if (value == null || !Double.isFinite(value)) {
                    throw new IllegalArgumentException("Missing or non-finite formula input: " + variable);
                }
                local.put(variable, value);
            }
            try {
                double result = expression.evaluate(local);
                if (!Double.isFinite(result)) throw new IllegalArgumentException("Formula result is not finite");
                return result;
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("Could not evaluate skill formula: " + source, exception);
            }
        }
    }
}
