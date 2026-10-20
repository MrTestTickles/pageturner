package pageturner;

import javafx.scene.web.WebView;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Unified book reader supporting both EPUB and PDF formats.
 * Routes operations to the appropriate reader implementation based on file type
 * while sharing the underlying WebView and exposing a consistent interface.
 */
public class UnifiedBookReader implements EpubReader {

    private final WebView view = new WebView();
    private final EpubReaderImpl epubReader = new EpubReaderImpl(view);
    private final PdfReaderImpl  pdfReader  = new PdfReaderImpl(view);
    private EpubReader activeReader = epubReader;

    @Override
    public void load(Path file) throws Exception {
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".pdf")) {
            epubReader.unload();
            activeReader = pdfReader;
            pdfReader.load(file);
        } else {
            pdfReader.unload();
            activeReader = epubReader;
            epubReader.load(file);
        }
    }

    @Override public void goTo(BookPosition pos) { activeReader.goTo(pos); }
    @Override public void goToSpread(int chapter, int spread) { activeReader.goToSpread(chapter, spread); }
    @Override public void nextPage() { activeReader.nextPage(); }
    @Override public void prevPage() { activeReader.prevPage(); }
    @Override public String bookTitle() { return activeReader.bookTitle(); }
    @Override public String chapterTitle(int chapter) { return activeReader.chapterTitle(chapter); }
    @Override public int chapterCount() { return activeReader.chapterCount(); }
    @Override public int currentChapter() { return activeReader.currentChapter(); }
    @Override public int currentSpread() { return activeReader.currentSpread(); }
    @Override public int totalSpreads() { return activeReader.totalSpreads(); }
    @Override public int currentBookPage() { return activeReader.currentBookPage(); }
    @Override public int totalBookPages() { return activeReader.totalBookPages(); }
    @Override public double bookProgress() { return activeReader.bookProgress(); }
    @Override public Highlight createHighlight(String type, String color) { return activeReader.createHighlight(type, color); }
    @Override public String removeActiveHighlight() { return activeReader.removeActiveHighlight(); }
    @Override public void restoreHighlights(List<Highlight> highlights) { activeReader.restoreHighlights(highlights); }

    @Override
    public void onChapterChange(Consumer<Integer> listener) {
        epubReader.onChapterChange(listener);
        pdfReader.onChapterChange(listener);
    }

    @Override
    public void onPageChange(PageChangeListener listener) {
        epubReader.onPageChange(listener);
        pdfReader.onPageChange(listener);
    }

    @Override public WebView getView() { return view; }
    @Override public WebView getView2() { return null; }
    @Override public Path currentEpubPath() { return activeReader.currentEpubPath(); }
}
