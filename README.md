# smt

A dual-page book reader and synchronized audiobook player built with JavaFX and Apache PDFBox. Supports EPUB and PDF formats with side-by-side spread layouts, persistent text highlighting, progress calculation, playlist navigation, and multi-book library management.

## Releases

You can download the pre-built standalone JAR directly from the Releases page:

- Release Download: https://github.com/MrTestTickles/smt/releases/download/v1.0.0/smt-1.0.0.jar

To run the downloaded JAR:

```bash
java -jar smt-1.0.0.jar
```

## Features

- Dual-Page Spread Layout: Renders two pages side by side with a central spine divider, paper shadows, and page numbers, replicating the feel of a physical book.
- Instant Two-Page Turning: Flips two pages at a time using arrow keys or on-screen controls, eliminating slow vertical scrolling or column drift.
- PDF and EPUB Support: Reads both EPUB and PDF documents seamlessly through a unified reader engine.
- Coordinate-Mapped PDF Text Selection: Extracts precise line and word bounding boxes from PDF documents so text can be selected with the mouse, highlighted, or underlined directly over page images.
- Highlighting and Underlining: Mark text with yellow highlights or orange underlines. Highlights are serialized and restored automatically when reopening a book.
- Synchronized Audiobook Player: Connect audio tracks to books. Supports uniform 2-minute seeking on single-track files and chapter skipping on multi-file playlists.
- Library Outline (Cmd+L / Ctrl+L): Saves multiple books in a library outline with their last read page, total pages, progress percentage, paired audio track, and timestamp.
- Background Pre-rendering: PDF pages are pre-rendered and cached in the background, ensuring smooth and instantaneous page flips.

## Project Structure

- README.md: Project documentation and quick start guide.
- .gitignore: Git ignore rules for build artifacts.
- files/: Complete source code and build files for the project:
  - files/pom.xml: Maven build configuration with JavaFX and PDFBox dependencies.
  - files/run.sh: Shell script to launch the application with configured JDK and Maven paths.
  - files/src/main/java/pageturner/:
    - Main.java: Entry point launcher for executable JAR.
    - PageTurnerApp.java: Main JavaFX user interface, player controls, and library dialog.
    - EpubReader.java: Interface and implementation for EPUB rendering.
    - PdfReaderImpl.java: PDFBox rendering engine, text overlay layer, and pre-rendering cache.
    - UnifiedBookReader.java: Dynamic router delegating to EPUB or PDF engines based on file type.
    - AudioPlayer.java: Audio playback engine for MP3, M4A, WAV, and OGG formats.
    - LibraryEntry.java: Immutable data model for saved book states.
    - StateStore.java: JSON persistence store for library outline and highlights.
    - SyncBridge.java: Audio-to-book position synchronization engine.
    - Highlight.java: Data record representing text highlights and underlines.
    - BookPosition.java: Data record representing chapter and page positions.
  - files/src/test/java/pageturner/:
    - VerifyPdfSupport.java: Automated test suite verifying PDF rendering, spread flipping, state persistence, and format switching.

## Building and Running from Source

Prerequisites:
- Java 17 or Java 21 (JDK)
- Apache Maven 3.8+

Run with the helper script:

```bash
./files/run.sh
```

Or build and run with Maven directly:

```bash
mvn -f files/pom.xml clean compile javafx:run
```

To build a standalone executable JAR:

```bash
mvn -f files/pom.xml clean package -DskipTests
```

The resulting JAR will be generated in `files/target/smt-1.0.0.jar`.

## License

Standard open-source license.
