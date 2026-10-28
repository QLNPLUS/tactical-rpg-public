package com.tactical.neoforge.client;

/** Pure menu geometry: the book stays still, only the command list scrolls. */
public final class MenuLayout {
    private MenuLayout() { }

    public record Geometry(double inset, double width, double height, double pageLeft,
                           double pageWidth, double paddingY, double paddingX,
                           double headingHeight, double apHeight, double weaponHeight,
                           double stateHeight, double actionsHeight, double sideTop,
                           double sideHeight, double sideWidth, boolean compact) {
        public double actionsTop() {
            return 7 + paddingY + headingHeight + apHeight + weaponHeight + stateHeight;
        }

        public SideContent sideContent(double scale) {
            double top = (compact ? 10 : 27) * scale;
            double bottom = (compact ? 6 : 14) * scale;
            // The leather frame has two fixed 7px borders; CSS heights are border-box.
            double available = Math.max(0, sideHeight - top - bottom - 14);
            double labelLine = (compact ? 13.2 : 18) * scale;
            double valueLine = (compact ? 15 : 18) * scale;
            double labelGap = (compact ? 2 : 7) * scale;
            double blockGap = (compact ? 5 : 16) * scale;
            double roundLine = 28.8 * scale;
            double required = estimateSideContent(labelLine, valueLine, roundLine,
                    labelGap, blockGap, 2, true);
            // Preserve ordinary viewport typography. A few pixels of spacing can be
            // reclaimed before dropping secondary text or reducing any font size.
            boolean dense = required - available + 1 > 5 * blockGap;
            int actorLines = dense ? 1 : 2;
            boolean stance = !dense;
            // Keep 1px spare for independently rounded hundredth-pixel CSS metrics.
            double budget = Math.max(0, available - 1);
            if (!dense) blockGap = Math.max(0, blockGap - Math.max(0, required - budget) / 5);
            double needed = estimateSideContent(labelLine, valueLine, roundLine,
                    labelGap, blockGap, actorLines, stance);
            double factor = dense ? Math.min(1, budget / needed) : 1;
            return new SideContent(top, bottom, available, 12 * scale * factor,
                    labelLine * factor, valueLine * factor, 24 * scale * factor,
                    roundLine * factor, labelGap * factor, blockGap * factor, actorLines, stance);
        }
    }

    /** Explicit px metrics shared by the CSS writer and pure content-budget tests. */
    public record SideContent(double paddingTop, double paddingBottom, double contentHeight,
                              double fontSize, double labelLineHeight, double valueLineHeight,
                              double roundFontSize, double roundLineHeight, double labelGap,
                              double blockGap, int actorLines, boolean showStance) {
        public double actorHeight() { return actorLines * valueLineHeight; }
        public double estimatedContentHeight() {
            return estimateSideContent(labelLineHeight, valueLineHeight, roundLineHeight,
                    labelGap, blockGap, actorLines, showStance);
        }
    }

    private static double estimateSideContent(double labelLine, double valueLine, double roundLine,
                                             double labelGap, double blockGap, int actorLines,
                                             boolean stance) {
        // Five labels, actor + HP/recovery/MP, the larger round numeral and optional stance.
        return 5 * (labelLine + labelGap + blockGap)
                + (actorLines + 3 + (stance ? 1 : 0)) * valueLine + roundLine;
    }

    public static Geometry calculate(double viewportWidth, double viewportHeight, double scale) {
        scale = Math.max(.6, Math.min(1.6, scale));
        double inset = Math.min(16 * scale, 12);
        double width = Math.max(1, Math.min(420 * scale, viewportWidth - 2 * inset));
        double height = Math.max(1, Math.min(670 * scale, viewportHeight - 2 * inset));
        boolean compact = height < 670 * scale;
        double pageLeft = width * 100 / 420;
        double pageWidth = width - pageLeft;
        double paddingY = (compact ? 10 : 24) * scale;
        double paddingX = (compact ? 14 : 21) * scale;
        double heading = 32 * scale;
        double ap = 35 * scale;
        double weapon = 30 * scale;
        double state = (compact ? 28 : 62) * scale;
        double actions = Math.max(1, height - 14 - 2 * paddingY - heading - ap - weapon - state);
        double sideTop = (compact ? 20 : 46) * scale;
        double sideHeight = Math.max(1, Math.min(390 * scale, height - sideTop - (compact ? 0 : 84 * scale)));
        return new Geometry(inset, width, height, pageLeft, pageWidth, paddingY, paddingX,
                heading, ap, weapon, state, actions, sideTop, sideHeight,
                Math.min(110 * scale, pageLeft + 10 * scale), compact);
    }
}
