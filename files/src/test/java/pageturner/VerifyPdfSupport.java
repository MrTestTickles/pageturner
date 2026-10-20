package pageturner;
import javafx.application.Platform;
import pageturner.*;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class VerifyPdfSupport {
    public static void main(String[] args) throws Exception {
        // Initialize JavaFX Toolkit
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException ignored) {}

        CountDownLatch latch = new CountDownLatch(1);
        final Throwable[] error = new Throwable[1];

        Platform.runLater(() -> {
            try {
                System.out.println("=== Testing UnifiedBookReader with PDF ===");
                UnifiedBookReader reader = new UnifiedBookReader();
                Path pdfPath = Path.of("/Users/s260555/Downloads/I_Have_No_Mouth_and_I_Must_Scream_-_Harlan_Ellison.pdf");
                
                reader.load(pdfPath);
                System.out.println("Book Title: " + reader.bookTitle());
                System.out.println("Total Book Pages: " + reader.totalBookPages());
                System.out.println("Total Spreads: " + reader.totalSpreads());
                System.out.println("Current Spread: " + reader.currentSpread());
                System.out.println("Current Book Page: " + reader.currentBookPage());
                System.out.println("Progress: " + String.format("%.2f%%", reader.bookProgress() * 100));
                System.out.println("Chapter/Spread Title: " + reader.chapterTitle(reader.currentSpread()));

                assert "I Have No Mouth and I Must Scream".equals(reader.bookTitle()) : "Title mismatch: " + reader.bookTitle();
                assert reader.totalBookPages() == 14 : "Expected 14 pages, got " + reader.totalBookPages();
                assert reader.totalSpreads() == 7 : "Expected 7 spreads, got " + reader.totalSpreads();
                assert reader.currentBookPage() == 2 : "Expected page 2 for spread 0, got " + reader.currentBookPage();

                // Test nextPage() -> Spread 1
                System.out.println("\n--- Turning to next 2 pages (Spread 1) ---");
                reader.nextPage();
                System.out.println("Current Spread: " + reader.currentSpread());
                System.out.println("Current Book Page: " + reader.currentBookPage());
                System.out.println("Progress: " + String.format("%.2f%%", reader.bookProgress() * 100));
                System.out.println("Chapter/Spread Title: " + reader.chapterTitle(reader.currentSpread()));
                assert reader.currentSpread() == 1 : "Expected spread 1, got " + reader.currentSpread();
                assert reader.currentBookPage() == 4 : "Expected page 4, got " + reader.currentBookPage();

                // Turn through all spreads to the end
                System.out.println("\n--- Advancing to final spread ---");
                while (reader.currentSpread() < reader.totalSpreads() - 1) {
                    reader.nextPage();
                }
                System.out.println("Final Spread: " + reader.currentSpread());
                System.out.println("Current Book Page: " + reader.currentBookPage());
                System.out.println("Progress: " + String.format("%.2f%%", reader.bookProgress() * 100));
                assert reader.currentSpread() == 6 : "Expected spread 6, got " + reader.currentSpread();
                assert reader.currentBookPage() == 14 : "Expected page 14, got " + reader.currentBookPage();
                assert reader.bookProgress() == 1.0 : "Expected 100% progress, got " + reader.bookProgress();

                // Test prevPage()
                System.out.println("\n--- Turning back 2 pages ---");
                reader.prevPage();
                System.out.println("Spread after prevPage: " + reader.currentSpread());
                System.out.println("Current Book Page: " + reader.currentBookPage());
                assert reader.currentSpread() == 5 : "Expected spread 5, got " + reader.currentSpread();
                assert reader.currentBookPage() == 12 : "Expected page 12, got " + reader.currentBookPage();

                // Test LibraryEntry and StateStore serialization
                System.out.println("\n=== Testing StateStore Persistence for PDF ===");
                LibraryEntry entry = new LibraryEntry(
                    pdfPath.toString(),
                    reader.bookTitle(),
                    pdfPath.toString(),
                    reader.currentSpread(),
                    reader.chapterTitle(reader.currentSpread()),
                    reader.currentSpread(),
                    reader.currentBookPage(),
                    reader.totalBookPages(),
                    "/Users/s260555/Downloads/sample_audio.mp3",
                    false,
                    0,
                    125.5,
                    null,
                    System.currentTimeMillis()
                );
                
                Highlight hl = new Highlight(
                    "hl_pdf_1",
                    pdfPath.toString(),
                    reader.currentSpread(),
                    "highlight",
                    "#ffe58f",
                    10,
                    55,
                    "Limp, the body of Gorrister hung from the pink palette"
                );

                StateStore.AppState state = new StateStore.AppState(
                    pdfPath.toString(),
                    List.of(entry),
                    List.of(hl)
                );
                StateStore.save(state);
                System.out.println("Saved state to disk.");

                StateStore.AppState loaded = StateStore.load();
                assert loaded != null : "Loaded state was null";
                assert pdfPath.toString().equals(loaded.currentBookId()) : "currentBookId mismatch";
                assert loaded.library().size() == 1 : "Expected 1 library entry";
                LibraryEntry loadedEntry = loaded.library().get(0);
                assert "I Have No Mouth and I Must Scream".equals(loadedEntry.title()) : "Loaded title mismatch";
                assert loadedEntry.currentBookPage() == 12 : "Loaded currentBookPage mismatch: " + loadedEntry.currentBookPage();
                assert loadedEntry.totalBookPages() == 14 : "Loaded totalBookPages mismatch";
                assert loadedEntry.audioPositionSeconds() == 125.5 : "Loaded audio position mismatch";
                assert loaded.highlights().size() == 1 : "Expected 1 highlight";
                System.out.println("State verified successfully! Loaded entry: " + loadedEntry.title() + " at page " + loadedEntry.currentBookPage() + "/" + loadedEntry.totalBookPages() + ", progress: " + loadedEntry.progressPercent() + "%");

                // Test loading EPUB next to verify seamless format switching
                Path epubPath = Path.of("/Users/s260555/Downloads/Pride_and_Prejudice_Full.epub");
                if (epubPath.toFile().exists()) {
                    System.out.println("\n=== Testing Format Switching (PDF -> EPUB) ===");
                    reader.load(epubPath);
                    System.out.println("EPUB Title: " + reader.bookTitle());
                    System.out.println("EPUB Chapters: " + reader.chapterCount());
                    System.out.println("EPUB Total Book Pages: " + reader.totalBookPages());
                    assert reader.chapterCount() > 0 : "EPUB chapter count should be > 0";
                    System.out.println("Format switching verified successfully!");
                }

                System.out.println("\nALL TESTS PASSED!");
            } catch (Throwable t) {
                error[0] = t;
            } finally {
                latch.countDown();
            }
        });

        boolean completed = latch.await(15, TimeUnit.SECONDS);
        if (!completed) {
            System.err.println("TEST TIMED OUT!");
            System.exit(1);
        }
        if (error[0] != null) {
            error[0].printStackTrace();
            System.exit(1);
        }
        System.exit(0);
    }
}
