package com.tactical.neoforge.client.ui.layout;

/** A point in GUI pixels. Replaces the AUI dependency's position type at the platform boundary. */
public record Point(double x, double y) {

    public static final Point NONE = new Point(-1, -1);

    public static Point of(double x, double y) {
        return new Point(x, y);
    }
}
