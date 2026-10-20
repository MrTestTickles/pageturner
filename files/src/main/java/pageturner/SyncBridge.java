package pageturner;

import javafx.util.Duration;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

interface SyncBridge {
    BookPosition audioToBook(Duration pos);
    Duration     bookToAudio(int chapter);
    boolean      isLoaded();
}

class SyncBridgeImpl implements SyncBridge {

    private final List<Duration> timestamps = new ArrayList<>();

    /** Load a plain-text sidecar file: one line per chapter, format: {@code index=HH:MM:SS} */
    void load(Path syncFile) throws IOException {
        timestamps.clear();
        for (String line : Files.readAllLines(syncFile)) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("=", 2);
            if (parts.length == 2) timestamps.add(parseDuration(parts[1].trim()));
        }
    }

    @Override
    public BookPosition audioToBook(Duration pos) {
        // ponytail: linear scan — fast enough for <=500 chapters
        int ch = 0;
        for (int i = 0; i < timestamps.size(); i++)
            if (pos.compareTo(timestamps.get(i)) >= 0) ch = i;
        return new BookPosition(ch, 0);
    }

    @Override
    public Duration bookToAudio(int chapter) {
        if (chapter < 0 || chapter >= timestamps.size()) return Duration.ZERO;
        return timestamps.get(chapter);
    }

    @Override
    public boolean isLoaded() { return !timestamps.isEmpty(); }

    private Duration parseDuration(String s) {
        String[] p = s.split(":");
        double secs = switch (p.length) {
            case 3  -> Integer.parseInt(p[0]) * 3600.0 + Integer.parseInt(p[1]) * 60.0 + Double.parseDouble(p[2]);
            case 2  -> Integer.parseInt(p[0]) * 60.0   + Double.parseDouble(p[1]);
            default -> Double.parseDouble(s);
        };
        return Duration.seconds(secs);
    }
}
