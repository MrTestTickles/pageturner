package pageturner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Handles persistent storage for the library outline, multi-book reading states,
 * audio playback positions, and highlights across application launches.
 */
public class StateStore {

    private static final Path STORE_DIR = Path.of(System.getProperty("user.home"), ".pageturner");
    private static final Path STATE_FILE = STORE_DIR.resolve("state.json");

    public record AppState(
        String currentBookId,
        List<LibraryEntry> library,
        List<Highlight> highlights
    ) {
        public static AppState empty() {
            return new AppState(null, new ArrayList<>(), new ArrayList<>());
        }

        public LibraryEntry getCurrentBook() {
            if (library == null || library.isEmpty()) return null;
            if (currentBookId != null) {
                for (LibraryEntry e : library) {
                    if (currentBookId.equals(e.id()) || currentBookId.equals(e.epubPath())) return e;
                }
            }
            return library.get(0);
        }

        public List<Highlight> getHighlightsFor(String book, int ch) {
            if (highlights == null) return List.of();
            return highlights.stream()
                .filter(h -> Objects.equals(h.epubPath(), book) && h.chapter() == ch)
                .toList();
        }
    }

    public static synchronized void save(AppState state) {
        try {
            Files.createDirectories(STORE_DIR);
            String json = serialize(state);
            Path tmp = STORE_DIR.resolve("state.json.tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            Files.move(tmp, STATE_FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            System.err.println("Failed to save state: " + e.getMessage());
        }
    }

    public static synchronized AppState load() {
        if (!Files.exists(STATE_FILE)) return AppState.empty();
        try {
            String content = Files.readString(STATE_FILE, StandardCharsets.UTF_8);
            Object parsed = parseJson(content);
            if (!(parsed instanceof Map<?, ?> map)) return AppState.empty();

            String currentBookId = (String) map.get("currentBookId");

            // Parse library entries
            List<LibraryEntry> library = new ArrayList<>();
            if (map.get("library") instanceof List<?> libList) {
                for (Object item : libList) {
                    if (item instanceof Map<?, ?> bm) {
                        library.add(new LibraryEntry(
                            (String) bm.get("id"),
                            (String) bm.get("title"),
                            (String) bm.get("epubPath"),
                            toInt(bm.get("chapter"), 0),
                            (String) bm.get("chapterTitle"),
                            toInt(bm.get("spread"), 0),
                            toInt(bm.get("currentBookPage"), 0),
                            toInt(bm.get("totalBookPages"), 0),
                            (String) bm.get("audioPath"),
                            Boolean.TRUE.equals(bm.get("isFolder")),
                            toInt(bm.get("trackIndex"), 0),
                            toDouble(bm.get("audioPositionSeconds"), 0.0),
                            (String) bm.get("syncPath"),
                            toLong(bm.get("lastReadTimestamp"), 0L)
                        ));
                    }
                }
            } else if (map.get("epubPath") != null) {
                // Backward-compatible migration from legacy single-book state
                String epubPath = (String) map.get("epubPath");
                currentBookId = epubPath;
                String filename = Path.of(epubPath).getFileName().toString().replace(".epub", "").replace("_", " ");
                library.add(new LibraryEntry(
                    epubPath,
                    filename,
                    epubPath,
                    toInt(map.get("chapter"), 0),
                    "Chapter " + (toInt(map.get("chapter"), 0) + 1),
                    toInt(map.get("spread"), 0),
                    0,
                    0,
                    (String) map.get("audioPath"),
                    Boolean.TRUE.equals(map.get("isFolder")),
                    toInt(map.get("trackIndex"), 0),
                    toDouble(map.get("audioPositionSeconds"), 0.0),
                    (String) map.get("syncPath"),
                    System.currentTimeMillis()
                ));
            }

            // Parse highlights
            List<Highlight> highlights = new ArrayList<>();
            if (map.get("highlights") instanceof List<?> hList) {
                for (Object item : hList) {
                    if (item instanceof Map<?, ?> hm) {
                        highlights.add(new Highlight(
                            (String) hm.get("id"),
                            (String) hm.get("epubPath"),
                            toInt(hm.get("chapter"), 0),
                            (String) hm.get("type"),
                            (String) hm.get("color"),
                            toInt(hm.get("charStart"), 0),
                            toInt(hm.get("charEnd"), 0),
                            (String) hm.get("text")
                        ));
                    }
                }
            }

            return new AppState(currentBookId, library, highlights);
        } catch (Exception e) {
            System.err.println("Failed to load state: " + e.getMessage());
            return AppState.empty();
        }
    }

    // ── JSON serialization ───────────────────────────────────────────────────

    private static String serialize(AppState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        appendField(sb, "currentBookId", state.currentBookId(), true);

        // Serialize library list
        sb.append("  \"library\": [\n");
        List<LibraryEntry> lib = state.library() != null ? state.library() : List.of();
        for (int i = 0; i < lib.size(); i++) {
            LibraryEntry e = lib.get(i);
            sb.append("    {\n");
            appendField(sb, "      \"id\"", e.id(), true, false);
            appendField(sb, "      \"title\"", e.title(), true, false);
            appendField(sb, "      \"epubPath\"", e.epubPath(), true, false);
            appendField(sb, "      \"chapter\"", e.chapter(), true, false);
            appendField(sb, "      \"chapterTitle\"", e.chapterTitle(), true, false);
            appendField(sb, "      \"spread\"", e.spread(), true, false);
            appendField(sb, "      \"currentBookPage\"", e.currentBookPage(), true, false);
            appendField(sb, "      \"totalBookPages\"", e.totalBookPages(), true, false);
            appendField(sb, "      \"audioPath\"", e.audioPath(), true, false);
            appendField(sb, "      \"isFolder\"", e.isFolder(), true, false);
            appendField(sb, "      \"trackIndex\"", e.trackIndex(), true, false);
            appendField(sb, "      \"audioPositionSeconds\"", e.audioPositionSeconds(), true, false);
            appendField(sb, "      \"syncPath\"", e.syncPath(), true, false);
            appendField(sb, "      \"lastReadTimestamp\"", e.lastReadTimestamp(), false, false);
            sb.append("    }");
            if (i + 1 < lib.size()) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ],\n");

        // Serialize highlights
        sb.append("  \"highlights\": [\n");
        List<Highlight> list = state.highlights() != null ? state.highlights() : List.of();
        for (int i = 0; i < list.size(); i++) {
            Highlight h = list.get(i);
            sb.append("    {");
            sb.append("\"id\":\"").append(esc(h.id())).append("\",");
            sb.append("\"epubPath\":\"").append(esc(h.epubPath())).append("\",");
            sb.append("\"chapter\":").append(h.chapter()).append(",");
            sb.append("\"type\":\"").append(esc(h.type())).append("\",");
            sb.append("\"color\":\"").append(esc(h.color())).append("\",");
            sb.append("\"charStart\":").append(h.charStart()).append(",");
            sb.append("\"charEnd\":").append(h.charEnd()).append(",");
            sb.append("\"text\":\"").append(esc(h.text())).append("\"");
            sb.append("}");
            if (i + 1 < list.size()) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static void appendField(StringBuilder sb, String key, Object val, boolean comma) {
        appendField(sb, "  \"" + key + "\"", val, comma, false);
    }

    private static void appendField(StringBuilder sb, String quotedKey, Object val, boolean comma, boolean dummy) {
        sb.append(quotedKey).append(": ");
        if (val == null) {
            sb.append("null");
        } else if (val instanceof String s) {
            sb.append("\"").append(esc(s)).append("\"");
        } else {
            sb.append(val);
        }
        if (comma) sb.append(",");
        sb.append("\n");
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static int toInt(Object o, int def) {
        if (o instanceof Number n) return n.intValue();
        return def;
    }

    private static long toLong(Object o, long def) {
        if (o instanceof Number n) return n.longValue();
        return def;
    }

    private static double toDouble(Object o, double def) {
        if (o instanceof Number n) return n.doubleValue();
        return def;
    }

    // ── JSON deserialization ─────────────────────────────────────────────────

    private static Object parseJson(String s) {
        return new Parser(s.trim()).parseValue();
    }

    private static class Parser {
        private final String src;
        private int pos = 0;
        Parser(String s) { this.src = s; }

        private void skipWs() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
        }

        Object parseValue() {
            skipWs();
            if (pos >= src.length()) return null;
            char c = src.charAt(pos);
            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (c == '"') return parseString();
            if (c == 't' || c == 'f') return parseBoolean();
            if (c == 'n') { pos += 4; return null; }
            return parseNumber();
        }

        Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++; // '{'
            skipWs();
            if (pos < src.length() && src.charAt(pos) == '}') { pos++; return map; }
            while (pos < src.length()) {
                skipWs();
                String key = parseString();
                skipWs();
                if (pos < src.length() && src.charAt(pos) == ':') pos++;
                Object val = parseValue();
                map.put(key, val);
                skipWs();
                if (pos < src.length() && src.charAt(pos) == ',') pos++;
                else if (pos < src.length() && src.charAt(pos) == '}') { pos++; break; }
            }
            return map;
        }

        List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            pos++; // '['
            skipWs();
            if (pos < src.length() && src.charAt(pos) == ']') { pos++; return list; }
            while (pos < src.length()) {
                list.add(parseValue());
                skipWs();
                if (pos < src.length() && src.charAt(pos) == ',') pos++;
                else if (pos < src.length() && src.charAt(pos) == ']') { pos++; break; }
            }
            return list;
        }

        String parseString() {
            skipWs();
            if (pos >= src.length() || src.charAt(pos) != '"') return "";
            pos++;
            StringBuilder sb = new StringBuilder();
            while (pos < src.length()) {
                char c = src.charAt(pos++);
                if (c == '"') break;
                if (c == '\\' && pos < src.length()) {
                    char esc = src.charAt(pos++);
                    switch (esc) {
                        case '"'  -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case 'n'  -> sb.append('\n');
                        case 'r'  -> sb.append('\r');
                        case 't'  -> sb.append('\t');
                        default   -> sb.append(esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        Boolean parseBoolean() {
            if (src.startsWith("true", pos))  { pos += 4; return true; }
            if (src.startsWith("false", pos)) { pos += 5; return false; }
            return false;
        }

        Number parseNumber() {
            int start = pos;
            if (pos < src.length() && (src.charAt(pos) == '-' || src.charAt(pos) == '+')) pos++;
            while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.' || src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) pos++;
            String num = src.substring(start, pos);
            if (num.contains(".")) return Double.parseDouble(num);
            try { return Long.parseLong(num); } catch (Exception e) { return Double.parseDouble(num); }
        }
    }
}
