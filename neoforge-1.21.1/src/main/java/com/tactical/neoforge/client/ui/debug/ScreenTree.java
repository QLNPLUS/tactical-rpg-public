package com.tactical.neoforge.client.ui.debug;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Development-only report of the screen that is <em>not</em> ours.
 *
 * <p>The native tactical screens publish their own layout, but the game's own screens (the language
 * list, the options pages) draw with the vanilla widgets, so a driver that has to operate them with
 * real mouse input has no way to know where a row or a button is. This report answers only that: the
 * screen class, its widget rectangles and the rows of any selection list, in GUI pixels.</p>
 *
 * <p>It is a read-only mirror: nothing is clicked, no screen is created, no state is written. Row
 * positions come from the list's own {@code getRowTop}, so a scrolled list reports where its rows
 * really are instead of a recomputed guess. Only the read-only development probe calls it, and with the
 * probe off no call reaches this class.</p>
 */
final class ScreenTree {

    private ScreenTree() {
    }

    /** The active screen as one JSON object; {@code {"class":"none"}} when nothing is open. */
    static String json() {
        Minecraft minecraft = Minecraft.getInstance();
        Screen screen = minecraft == null ? null : minecraft.screen;
        if (screen == null) return "{\"class\":\"none\"}";
        StringBuilder out = new StringBuilder(1024);
        out.append("{\"class\":\"").append(escape(screen.getClass().getSimpleName()))
                .append("\",\"title\":\"").append(escape(text(screen.getTitle())))
                .append("\",\"width\":").append(screen.width)
                .append(",\"height\":").append(screen.height)
                .append(",\"nodes\":[");
        boolean first = true;
        try {
            for (var child : screen.children()) {
                String node = node(child);
                if (node == null) continue;
                if (!first) out.append(',');
                first = false;
                out.append(node);
            }
        } catch (RuntimeException ignored) {
            // A screen that mutates its children while being read only costs a partial report.
        }
        return out.append("]}").toString();
    }

    private static String node(Object child) {
        if (child instanceof AbstractSelectionList<?> list) return list(list);
        if (child instanceof AbstractWidget widget) {
            return "{\"kind\":\"widget\",\"type\":\"" + escape(widget.getClass().getSimpleName())
                    + "\",\"x\":" + widget.getX() + ",\"y\":" + widget.getY()
                    + ",\"w\":" + widget.getWidth() + ",\"h\":" + widget.getHeight()
                    + ",\"active\":" + widget.active + ",\"visible\":" + widget.visible
                    + ",\"text\":\"" + escape(text(widget.getMessage())) + "\"}";
        }
        return null;
    }

    private static String list(AbstractSelectionList<?> list) {
        StringBuilder out = new StringBuilder(256);
        out.append("{\"kind\":\"list\",\"type\":\"").append(escape(list.getClass().getSimpleName()))
                .append("\",\"x\":").append(list.getX()).append(",\"y\":").append(list.getY())
                .append(",\"w\":").append(list.getWidth()).append(",\"h\":").append(list.getHeight())
                .append(",\"rowX\":").append(list.getRowLeft())
                .append(",\"rowW\":").append(list.getRowWidth())
                .append(",\"rows\":[");
        boolean first = true;
        var entries = list.children();
        for (int index = 0; index < entries.size(); index++) {
            Object entry = entries.get(index);
            int top = rowTop(list, index);
            if (top < 0) continue;
            if (!first) out.append(',');
            first = false;
            out.append("{\"index\":").append(index)
                    .append(",\"x\":").append(list.getRowLeft())
                    .append(",\"y\":").append(top)
                    .append(",\"w\":").append(list.getRowWidth())
                    .append(",\"h\":").append(rowHeight(list))
                    .append(",\"code\":\"").append(escape(code(entry)))
                    .append("\",\"text\":\"").append(escape(narration(entry))).append("\"}");
        }
        return out.append("]}").toString();
    }

    /** The list's own row top, read through the protected accessor of the runtime class. */
    private static int rowTop(Object list, int index) {
        Method method = declared(list.getClass(), "getRowTop", int.class);
        if (method == null) return -1;
        try {
            Object value = method.invoke(list, index);
            return value instanceof Integer top ? top : -1;
        } catch (ReflectiveOperationException | RuntimeException failed) {
            return -1;
        }
    }

    private static int rowHeight(Object list) {
        Object value = fieldValue(list, "itemHeight");
        return value instanceof Integer height ? height : -1;
    }

    private static Method declared(Class<?> type, String name, Class<?>... parameters) {
        Class<?> current = type;
        while (current != null) {
            try {
                Method method = current.getDeclaredMethod(name, parameters);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException missing) {
                current = current.getSuperclass();
            } catch (RuntimeException failed) {
                return null;
            }
        }
        return null;
    }

    private static Object fieldValue(Object target, String name) {
        Class<?> current = target.getClass();
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException missing) {
                current = current.getSuperclass();
            } catch (ReflectiveOperationException | RuntimeException failed) {
                return null;
            }
        }
        return null;
    }

    /** The entry's own language code when it carries one; the list contract does not require it. */
    private static String code(Object entry) {
        Object value = fieldValue(entry, "code");
        return value instanceof String text ? text : "";
    }

    /** The entry's own narration: a row that carries no code is still identifiable by its wording. */
    private static String narration(Object entry) {
        Method method = declared(entry.getClass(), "getNarration");
        if (method == null) return "";
        try {
            Object value = method.invoke(entry);
            return value instanceof Component component ? text(component) : String.valueOf(value);
        } catch (ReflectiveOperationException | RuntimeException failed) {
            return "";
        }
    }

    private static String text(Component component) {
        return component == null ? "" : component.getString();
    }

    private static String escape(String text) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 0x20) out.append(String.format("\\u%04x", (int) ch));
                    else out.append(ch);
                }
            }
        }
        return out.toString();
    }
}
