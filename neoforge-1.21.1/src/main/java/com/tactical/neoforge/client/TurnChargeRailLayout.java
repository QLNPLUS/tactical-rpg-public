package com.tactical.neoforge.client;

/** Pure rail geometry: horizontal position encodes charge, never collision avoidance. */
final class TurnChargeRailLayout {
    record Portrait(int entityId, double left, double top, int lane) { }
    private TurnChargeRailLayout() { }

    static double gauge(double value) {
        return Double.isNaN(value) ? 0 : Math.max(0, Math.min(1, value));
    }

    static Portrait[] place(int[] ids, int[] gauges, int actor, double width, double size) {
        Portrait[] result = new Portrait[ids.length];
        double travel = Math.max(0, width - size);
        double step = Math.max(6, size * .28);
        for (int i = 0; i < ids.length; i++) {
            double fraction = ids[i] == actor ? 1 : gauge(i < gauges.length ? gauges[i] / 100.0 : 0);
            double left = travel * fraction;
            int lane = 0;
            boolean occupied;
            do {
                occupied = false;
                for (int j = 0; j < i; j++) {
                    if (result[j].lane() == lane && Math.abs(result[j].left() - left) < size + 3) {
                        lane++;
                        occupied = true;
                        break;
                    }
                }
            } while (occupied);
            result[i] = new Portrait(ids[i], left, lane * step, lane);
        }
        return result;
    }
}
