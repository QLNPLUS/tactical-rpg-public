package com.tactical.neoforge.client.ui.hud;

/**
 * Test fixtures for the journal's pixel viewport: the same {@link JournalViewport.Content} the layout
 * builds from the retained history, without a game window.
 */
public final class JournalFixture {

    private JournalFixture() {
    }

    /**
     * Content from line counts newest first, with sequence numbers 1..n oldest first - the order the
     * retained history uses.
     */
    public static JournalViewport.Content of(int[] linesNewestFirst, int lineHeight, int gap, int bodyHeight) {
        int count = linesNewestFirst.length;
        long[] sequences = new long[count];
        int[] heights = new int[count];
        for (int index = 0; index < count; index++) {
            sequences[index] = index + 1L;
            heights[index] = Math.max(1, linesNewestFirst[count - 1 - index]) * lineHeight;
        }
        return new JournalViewport.Content(sequences, heights, gap, lineHeight, bodyHeight);
    }

    /** {@code count} entries of one text line each, the newest one carrying {@code lastSequence}. */
    public static JournalViewport.Content uniform(int count, long lastSequence, int lineHeight, int gap,
                                                  int bodyHeight) {
        long[] sequences = new long[count];
        int[] heights = new int[count];
        for (int index = 0; index < count; index++) {
            sequences[index] = lastSequence - (count - 1 - index);
            heights[index] = lineHeight;
        }
        return new JournalViewport.Content(sequences, heights, gap, lineHeight, bodyHeight);
    }
}
