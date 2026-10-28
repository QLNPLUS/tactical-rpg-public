package com.tactical.core.combat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Active-time turn scheduler. Every combatant fills a gauge at a rate derived
 * from its initiative; whoever reaches a full gauge first takes the turn, then
 * the gauge resets. This makes fast combatants act more often and gives the HUD
 * a truthful "who is next" progress display.
 */
public final class TurnScheduler {

    private final Map<String, Double> gauge = new LinkedHashMap<>();
    private final Map<String, Double> rate = new LinkedHashMap<>();
    private String current;
    private int turnCount;

    public void rebuild(List<CombatantSnapshot> combatants) {
        gauge.clear();
        rate.clear();
        for (CombatantSnapshot snapshot : combatants) {
            gauge.put(snapshot.id(), 0.0D);
            rate.put(snapshot.id(), validRate(snapshot.initiative()));
        }
        current = null;
        turnCount = 0;
    }

    public void add(String id, double initiative) {
        gauge.putIfAbsent(id, 0.0D);
        rate.put(id, validRate(initiative));
    }

    /** Changes fill speed without resetting accumulated gauge or the current actor. */
    public void updateRate(String id, double initiative) {
        if (gauge.containsKey(id)) rate.put(id, validRate(initiative));
    }

    public double rateOf(String id) {
        return rate.getOrDefault(id, 0.0D);
    }

    private static double validRate(double value) {
        return Double.isFinite(value) && value > 0.0D ? value : 1.0D;
    }

    public void remove(String id) {
        gauge.remove(id);
        rate.remove(id);
        if (id.equals(current)) {
            current = null;
        }
    }

    public String current() {
        return current;
    }

    public String advance() {
        if (gauge.isEmpty()) {
            return null;
        }
        double step = Double.MAX_VALUE;
        for (Map.Entry<String, Double> entry : gauge.entrySet()) {
            double time = (1.0D - entry.getValue()) / rate.get(entry.getKey());
            if (time < step) {
                step = time;
            }
        }
        for (Map.Entry<String, Double> entry : gauge.entrySet()) {
            entry.setValue(entry.getValue() + rate.get(entry.getKey()) * step);
        }

        String picked = null;
        for (Map.Entry<String, Double> entry : gauge.entrySet()) {
            if (entry.getValue() >= 1.0D - 1.0E-6D
                    && (picked == null || rate.get(entry.getKey()) > rate.get(picked))) {
                picked = entry.getKey();
            }
        }
        if (picked == null) {
            picked = gauge.keySet().iterator().next();
        }
        gauge.put(picked, 0.0D);
        current = picked;
        turnCount++;
        return picked;
    }

    /** Display gauge in 0..1; the acting combatant always reads as full. */
    public double gaugeOf(String id) {
        if (id.equals(current)) {
            return 1.0D;
        }
        return Math.min(1.0D, gauge.getOrDefault(id, 0.0D));
    }

    public List<String> order() {
        return new ArrayList<>(gauge.keySet());
    }

    public int turnCount() {
        return turnCount;
    }
}
