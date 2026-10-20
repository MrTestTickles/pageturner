package pageturner;

/**
 * Represents a saved book in the user's reading library outline.
 * Stores reading position, total pages, audiobook pair, and timestamp.
 */
public record LibraryEntry(
    String id,                    // Unique identifier (usually epubPath)
    String title,                 // Book title
    String epubPath,              // Absolute path to the EPUB file
    int chapter,                  // Current chapter index
    String chapterTitle,          // Title of current chapter
    int spread,                   // Current spread (2-page view) index
    int currentBookPage,          // Current book-wide page number
    int totalBookPages,           // Total pages calculated for the entire book
    String audioPath,             // Path to audiobook file or directory
    boolean isFolder,             // True if audioPath is a multi-track folder/playlist
    int trackIndex,               // Current audio track index in playlist
    double audioPositionSeconds,  // Playback timestamp in seconds
    String syncPath,              // Path to .sync sidecar file (if any)
    long lastReadTimestamp        // Epoch milliseconds when last read/listened
) {
    public double progress() {
        return totalBookPages > 0 ? (double) currentBookPage / totalBookPages : 0.0;
    }

    public int progressPercent() {
        return (int) Math.round(progress() * 100);
    }
}
