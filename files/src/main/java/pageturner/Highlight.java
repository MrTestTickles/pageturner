package pageturner;

/**
 * Immutable record representing a user highlight or underline in an EPUB.
 * Persists across chapter reloads and application relaunches.
 */
public record Highlight(
    String id,
    String epubPath,
    int chapter,
    String type,   // "highlight" or "underline"
    String color,  // css color string, e.g. "#ffe58f" or "#d46b08"
    int charStart,
    int charEnd,
    String text
) {}
