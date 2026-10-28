package com.tactical.neoforge.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TurnChargeRailLayoutTest {
    @Test void gaugeClampsAndRejectsNaN() {
        assertEquals(0, TurnChargeRailLayout.gauge(-.2));
        assertEquals(1, TurnChargeRailLayout.gauge(1.2));
        assertEquals(0, TurnChargeRailLayout.gauge(Double.NaN));
        assertEquals(.42, TurnChargeRailLayout.gauge(.42));
    }

    @Test void coordinatesAreProportionalAndCurrentAlwaysAtRightEnd() {
        var positions = TurnChargeRailLayout.place(new int[]{1, 2, 3, 4}, new int[]{-20, 50, 140, 12}, 4, 436, 36);
        assertEquals(0, positions[0].left());
        assertEquals(200, positions[1].left());
        assertEquals(400, positions[2].left());
        assertEquals(400, positions[3].left());
        assertTrue(positions[3].top() > positions[2].top());
    }

    @Test void seventeenTiedParticipantsAllRemainPresentWithoutFakeProgress() {
        int[] ids = new int[17];
        int[] gauges = new int[17];
        for (int i = 0; i < ids.length; i++) { ids[i] = i; gauges[i] = 50; }
        for (double scale : new double[]{.6, 1, 1.6}) {
            var positions = TurnChargeRailLayout.place(ids, gauges, -1, 900 * scale, 36 * scale);
            assertEquals(17, positions.length);
            for (int i = 0; i < ids.length; i++) {
                assertEquals(i, positions[i].entityId());
                assertEquals(432 * scale, positions[i].left(), .00001);
                assertEquals(i, positions[i].lane());
            }
        }
    }

    @Test void separateGaugePositionsReuseSameVerticalLane() {
        var positions = TurnChargeRailLayout.place(new int[]{1, 2, 3}, new int[]{0, 50, 100}, -1, 436, 36);
        for (var portrait : positions) assertEquals(0, portrait.lane());
    }
}
