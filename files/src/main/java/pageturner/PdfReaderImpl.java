package pageturner;

import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.web.WebView;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Dual-page PDF reader rendering high-resolution spreads side-by-side with
 * central book spine shadow, page badges, background disk caching, and
 * precise word-level coordinate mapping for highlights and underlines.
 */
public class PdfReaderImpl implements EpubReader {

    private final WebView view;
    private PDDocument doc;
    private PDFRenderer renderer;
    private Path currentPdfPath;
    private Path cacheDir;
    private String bookTitle = "";

    private int totalPages = 0;
    private int totalSpreads = 1;
    private int currentSpread = 0;
    private boolean active = false;

    private Consumer<Integer> onChapterChange;
    private PageChangeListener onPageChange;

    private final ExecutorService prefetchExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pdf-prefetch");
        t.setDaemon(true);
        return t;
    });

    private final Map<Integer, List<Word>> wordCache = new ConcurrentHashMap<>();
    private List<Highlight> savedHighlights = new ArrayList<>();

    record Word(String text, float x, float y, float width, float height, int charStart, int charEnd) {}

    static class WordExtractor extends PDFTextStripper {
        final List<Word> words = new ArrayList<>();
        int charCounter = 0;
        WordExtractor() throws IOException { super(); }

        @Override
        protected void writeString(String string, List<TextPosition> textPositions) throws IOException {
            StringBuilder curWord = new StringBuilder();
            float startX = 0, startY = 0, h = 0, endX = 0;
            int startChar = 0;
            boolean inWord = false;

            for (TextPosition tp : textPositions) {
                String c = tp.getUnicode();
                if (c == null || c.isBlank()) {
                    if (inWord) {
                        words.add(new Word(curWord.toString(), startX, startY, Math.max(1f, endX - startX), h, startChar, charCounter));
                        curWord.setLength(0);
                        inWord = false;
                    }
                    charCounter++;
                } else {
                    if (!inWord) {
                        startX = tp.getXDirAdj();
                        startY = tp.getYDirAdj();
                        h = tp.getHeightDir();
                        startChar = charCounter;
                        inWord = true;
                    }
                    curWord.append(c);
                    endX = tp.getXDirAdj() + tp.getWidthDirAdj();
                    h = Math.max(h, tp.getHeightDir());
                    charCounter++;
                }
            }
            if (inWord && curWord.length() > 0) {
                words.add(new Word(curWord.toString(), startX, startY, Math.max(1f, endX - startX), h, startChar, charCounter));
            }
            charCounter++;
        }
    }

    public PdfReaderImpl(WebView view) {
        this.view = view;
        view.getEngine().getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            if (active && newState == Worker.State.SUCCEEDED) {
                Platform.runLater(this::restoreHighlightsForCurrentSpread);
            }
        });
    }

    public void unload() {
        this.active = false;
        if (doc != null) {
            try { doc.close(); } catch (Exception ignored) {}
            doc = null;
        }
    }

    @Override
    public void load(Path pdf) throws Exception {
        unload();
        currentPdfPath = pdf;
        active = true;
        wordCache.clear();

        doc = Loader.loadPDF(pdf.toFile());
        renderer = new PDFRenderer(doc);
        totalPages = doc.getNumberOfPages();
        totalSpreads = Math.max(1, (totalPages + 1) / 2);
        currentSpread = 0;

        String t = null;
        if (doc.getDocumentInformation() != null) {
            t = doc.getDocumentInformation().getTitle();
        }
        if (t == null || t.isBlank()) {
            t = pdf.getFileName().toString().replaceAll("(?i)\\.pdf$", "").replace('_', ' ');
        }
        bookTitle = t.trim();

        String hash = Integer.toHexString(pdf.toAbsolutePath().toString().hashCode());
        cacheDir = Path.of(System.getProperty("user.home"), ".pageturner", "cache", "pdf", hash);
        Files.createDirectories(cacheDir);

        renderSpread(0);
        firePageChange();
        prefetchRemainingPages();
    }

    private void prefetchRemainingPages() {
        File file = currentPdfPath.toFile();
        prefetchExecutor.submit(() -> {
            try (PDDocument bgDoc = Loader.loadPDF(file)) {
                PDFRenderer bgRenderer = new PDFRenderer(bgDoc);
                int total = bgDoc.getNumberOfPages();
                for (int i = 0; i < total; i++) {
                    if (!active) break;
                    File imgFile = cacheDir.resolve("page_" + i + ".png").toFile();
                    if (!imgFile.exists()) {
                        BufferedImage img = bgRenderer.renderImageWithDPI(i, 144);
                        ImageIO.write(img, "PNG", imgFile);
                    }
                }
            } catch (Exception ignored) {}
        });
    }

    private void renderSpread(int sp) {
        if (!active || doc == null || totalPages == 0) return;

        int leftIdx = sp * 2;
        int rightIdx = sp * 2 + 1;

        File leftImg = ensurePageImage(leftIdx);
        String leftText = buildTextOverlay(leftIdx);

        File rightImg = null;
        String rightText = "";
        if (rightIdx < totalPages) {
            rightImg = ensurePageImage(rightIdx);
            rightText = buildTextOverlay(rightIdx);
        }

        String html = buildSpreadHtml(sp, leftIdx, leftImg, leftText, rightIdx, rightImg, rightText);
        view.getEngine().loadContent(html);
    }

    private File ensurePageImage(int pageIndex) {
        File imgFile = cacheDir.resolve("page_" + pageIndex + ".png").toFile();
        if (!imgFile.exists()) {
            try {
                BufferedImage img = renderer.renderImageWithDPI(pageIndex, 144);
                ImageIO.write(img, "PNG", imgFile);
            } catch (Exception e) {
                System.err.println("Failed to render page " + pageIndex + ": " + e.getMessage());
            }
        }
        return imgFile;
    }

    private String buildTextOverlay(int pageIndex) {
        if (pageIndex >= totalPages) return "";
        List<Word> words = wordCache.computeIfAbsent(pageIndex, idx -> {
            try {
                WordExtractor ex = new WordExtractor();
                ex.setStartPage(idx + 1);
                ex.setEndPage(idx + 1);
                ex.getText(doc);
                return ex.words;
            } catch (Exception e) {
                return List.of();
            }
        });

        float pageW = doc.getPage(pageIndex).getMediaBox().getWidth();
        float pageH = doc.getPage(pageIndex).getMediaBox().getHeight();

        StringBuilder sb = new StringBuilder();
        for (Word w : words) {
            float leftPct = (w.x() / pageW) * 100f;
            float topPct  = ((w.y() - w.height()) / pageH) * 100f;
            float wPct    = (w.width() / pageW) * 100f;
            float hPct    = (w.height() / pageH) * 100f;
            sb.append("<span class=\"pdf-word\" data-cstart=\"")
              .append(w.charStart())
              .append("\" data-cend=\"")
              .append(w.charEnd())
              .append("\" style=\"")
              .append(String.format(Locale.US, "left:%.2f%%; top:%.2f%%; width:%.2f%%; height:%.2f%%;", leftPct, topPct, wPct, hPct))
              .append("\">")
              .append(escapeHtml(w.text()))
              .append(" </span>\n");
        }
        return sb.toString();
    }

    private String buildSpreadHtml(int sp, int leftIdx, File leftImg, String leftText,
                                   int rightIdx, File rightImg, String rightText) {
        String leftUri = leftImg != null ? leftImg.toURI().toString() : "";
        String rightUri = rightImg != null ? rightImg.toURI().toString() : "";

        String rightPanel;
        if (rightImg != null) {
            rightPanel = """
              <div class="page-wrapper">
                <div class="page-frame">
                  <img src="__RIGHT_URI__" />
                  <div class="text-layer">
                    __RIGHT_TEXT__
                  </div>
                </div>
                <div class="page-num">Page __RIGHT_NUM__</div>
              </div>
            """
            .replace("__RIGHT_URI__", rightUri)
            .replace("__RIGHT_TEXT__", rightText)
            .replace("__RIGHT_NUM__", String.valueOf(rightIdx + 1));
        } else {
            rightPanel = """
              <div class="page-wrapper">
                <div class="page-frame blank-frame">
                  <div class="blank-text">End of Document</div>
                </div>
                <div class="page-num"></div>
              </div>
            """;
        }

        String template = """
        <!DOCTYPE html>
        <html>
        <head>
        <meta charset="utf-8">
        <style>
          * { box-sizing: border-box; }
          html, body {
            margin: 0; padding: 0;
            width: 100vw; height: 100vh;
            background: #fbf7ee;
            overflow: hidden;
            display: flex;
            flex-direction: column;
            justify-content: center;
            align-items: center;
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
          }
          .spread-container {
            display: flex;
            flex-direction: row;
            align-items: center;
            justify-content: center;
            width: 98vw;
            height: 94vh;
          }
          .page-wrapper {
            flex: 1;
            height: 100%;
            display: flex;
            flex-direction: column;
            align-items: center;
            justify-content: center;
            padding: 8px 12px;
          }
          .page-frame {
            position: relative;
            max-width: 100%;
            max-height: calc(94vh - 28px);
            box-shadow: 0 4px 18px rgba(40, 30, 20, 0.12), 0 1px 4px rgba(40, 30, 20, 0.08);
            border-radius: 3px;
            background: #ffffff;
            overflow: hidden;
            display: inline-flex;
          }
          .page-frame img {
            max-width: 100%;
            max-height: calc(94vh - 28px);
            width: auto;
            height: auto;
            display: block;
            user-select: none;
            -webkit-user-drag: none;
          }
          .blank-frame {
            width: 80%;
            height: 80%;
            background: rgba(255, 255, 255, 0.5);
            border: 1px dashed #dfd7c8;
            align-items: center;
            justify-content: center;
          }
          .blank-text {
            color: #b5aa98;
            font-size: 14px;
            font-weight: 500;
          }
          .text-layer {
            position: absolute;
            top: 0; left: 0; width: 100%; height: 100%;
            pointer-events: auto;
          }
          .pdf-word {
            position: absolute;
            color: transparent;
            white-space: nowrap;
            cursor: text;
            line-height: 1;
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
            user-select: text;
            box-sizing: border-box;
          }
          .pdf-word::selection {
            background: rgba(43, 114, 219, 0.35);
          }
          .pdf-word.pt-highlight {
            background-color: rgba(255, 229, 143, 0.6) !important;
            border-radius: 2px !important;
            box-shadow: 0 0 0 1px rgba(220, 180, 0, 0.3) !important;
          }
          .pdf-word.pt-underline {
            border-bottom: 2.5px solid #d46b08 !important;
          }
          .spine-line {
            width: 2px;
            height: 88vh;
            background: linear-gradient(to bottom, #eae2d3, #b8ae9d 50%, #eae2d3);
            box-shadow: -1px 0 3px rgba(0,0,0,0.08), 1px 0 3px rgba(0,0,0,0.08);
            flex-shrink: 0;
            margin: 0 8px;
            border-radius: 1px;
          }
          .page-num {
            margin-top: 6px;
            font-size: 11.5px;
            font-weight: 600;
            color: #8c8273;
            letter-spacing: 0.5px;
            user-select: none;
          }
        </style>
        <script>
        document.addEventListener('click', function(e) {
          var p = e.target;
          while (p && p !== document.body) {
            if (p.dataset && p.dataset.hlId) {
              window.__ptLastClickedHlId = p.dataset.hlId;
              return;
            }
            p = p.parentElement;
          }
        }, true);

        window.addEventListener('keydown', function(e) {
          if (e.key === 'ArrowRight' || e.key === 'ArrowLeft' || e.key === 'PageDown' || e.key === 'PageUp') {
            e.preventDefault();
          }
        }, true);

        window.__ptRestore = function(items) {
          if (!items || !items.length) return;
          var words = document.querySelectorAll('.pdf-word');
          for (var i = 0; i < items.length; i++) {
            var h = items[i];
            var cls = (h.type === 'underline') ? 'pt-underline' : 'pt-highlight';
            for (var j = 0; j < words.length; j++) {
              var w = words[j];
              var cs = parseInt(w.dataset.cstart);
              var ce = parseInt(w.dataset.cend);
              if (cs >= h.charStart && ce <= h.charEnd) {
                w.classList.add(cls);
                w.dataset.hlId = h.id;
                if (h.type === 'underline') {
                  if (h.color) w.style.setProperty('border-bottom-color', h.color, 'important');
                } else {
                  if (h.color) w.style.setProperty('background-color', h.color, 'important');
                }
              }
            }
          }
        };
        </script>
        </head>
        <body>
          <div class="spread-container">
            <div class="page-wrapper">
              <div class="page-frame">
                <img src="__LEFT_URI__" />
                <div class="text-layer">
                  __LEFT_TEXT__
                </div>
              </div>
              <div class="page-num">Page __LEFT_NUM__</div>
            </div>
            <div class="spine-line"></div>
            __RIGHT_PANEL__
          </div>
        </body>
        </html>
        """;

        return template
            .replace("__LEFT_URI__", leftUri)
            .replace("__LEFT_TEXT__", leftText)
            .replace("__LEFT_NUM__", String.valueOf(leftIdx + 1))
            .replace("__RIGHT_PANEL__", rightPanel);
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private void restoreHighlightsForCurrentSpread() {
        if (!active || currentPdfPath == null) return;
        List<Highlight> spreadHls = savedHighlights.stream()
            .filter(h -> Objects.equals(h.epubPath(), currentPdfPath.toString()) && h.chapter() == currentSpread)
            .toList();
        if (spreadHls.isEmpty()) return;

        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < spreadHls.size(); i++) {
            Highlight h = spreadHls.get(i);
            json.append("{")
                .append("\"id\":\"").append(escapeJson(h.id())).append("\",")
                .append("\"type\":\"").append(escapeJson(h.type())).append("\",")
                .append("\"color\":\"").append(escapeJson(h.color())).append("\",")
                .append("\"charStart\":").append(h.charStart()).append(",")
                .append("\"charEnd\":").append(h.charEnd())
                .append("}");
            if (i + 1 < spreadHls.size()) json.append(",");
        }
        json.append("]");

        try {
            view.getEngine().executeScript("window.__ptRestore(" + json + ");");
        } catch (Exception e) {
            System.err.println("Failed to restore PDF highlights: " + e.getMessage());
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void firePageChange() {
        if (onChapterChange != null) {
            onChapterChange.accept(currentSpread);
        }
        if (onPageChange != null) {
            onPageChange.onPageChange(currentSpread, currentSpread, totalSpreads, currentBookPage(), totalBookPages());
        }
    }

    @Override
    public void goTo(BookPosition pos) {
        if (pos == null) return;
        int targetSpread = Math.max(0, Math.min(pos.chapter(), totalSpreads - 1));
        goToSpread(0, targetSpread);
    }

    @Override
    public void goToSpread(int chapter, int sp) {
        int target = Math.max(0, Math.min(sp, totalSpreads - 1));
        if (target != currentSpread) {
            currentSpread = target;
            renderSpread(currentSpread);
            firePageChange();
        }
    }

    @Override
    public void nextPage() {
        if (currentSpread + 1 < totalSpreads) {
            currentSpread++;
            renderSpread(currentSpread);
            firePageChange();
        }
    }

    @Override
    public void prevPage() {
        if (currentSpread > 0) {
            currentSpread--;
            renderSpread(currentSpread);
            firePageChange();
        }
    }

    @Override
    public String bookTitle() {
        if (bookTitle != null && !bookTitle.isEmpty()) return bookTitle;
        if (currentPdfPath != null) {
            return currentPdfPath.getFileName().toString().replaceAll("(?i)\\.pdf$", "").replace('_', ' ');
        }
        return "Untitled PDF";
    }

    @Override
    public String chapterTitle(int ch) {
        int left = ch * 2 + 1;
        int right = ch * 2 + 2;
        if (right <= totalPages) {
            return "Pages " + left + " \u2013 " + right + " of " + totalPages;
        }
        return "Page " + left + " of " + totalPages;
    }

    @Override public int     chapterCount()        { return totalSpreads; }
    @Override public int     currentChapter()      { return currentSpread; }
    @Override public int     currentSpread()       { return currentSpread; }
    @Override public int     totalSpreads()        { return totalSpreads; }
    @Override public WebView getView()             { return view; }
    @Override public WebView getView2()            { return null; }
    @Override public Path    currentEpubPath()     { return currentPdfPath; }
    @Override public void    onChapterChange(Consumer<Integer> l) { this.onChapterChange = l; }
    @Override public void    onPageChange(PageChangeListener l)   { this.onPageChange = l; }

    @Override
    public int totalBookPages() {
        return Math.max(1, totalPages);
    }

    @Override
    public int currentBookPage() {
        if (totalPages <= 0) return 1;
        int right = currentSpread * 2 + 2;
        return Math.min(right, totalPages);
    }

    @Override
    public double bookProgress() {
        return totalPages > 0 ? (double) currentBookPage() / totalPages : 0.0;
    }

    @Override
    public Highlight createHighlight(String type, String color) {
        if (currentPdfPath == null) return null;
        try {
            String script =
                "(function() {" +
                "  var sel = window.getSelection();" +
                "  if (!sel || sel.isCollapsed || !sel.rangeCount) return null;" +
                "  var range = sel.getRangeAt(0);" +
                "  var text = sel.toString();" +
                "  if (!text || !text.trim()) return null;" +
                "  var id = 'hl_' + Date.now() + '_' + Math.floor(Math.random() * 10000);" +
                "  var cls = ('" + type + "' === 'underline') ? 'pt-underline' : 'pt-highlight';" +
                "  var words = document.querySelectorAll('.pdf-word');" +
                "  var matched = [];" +
                "  var firstChar = -1, lastChar = -1;" +
                "  for (var i = 0; i < words.length; i++) {" +
                "    var w = words[i];" +
                "    if (range.intersectsNode(w)) {" +
                "      w.classList.add(cls);" +
                "      w.dataset.hlId = id;" +
                "      if ('" + type + "' === 'underline') {" +
                "        w.style.setProperty('border-bottom-color', '" + color + "', 'important');" +
                "      } else {" +
                "        w.style.setProperty('background-color', '" + color + "', 'important');" +
                "      }" +
                "      matched.push(w);" +
                "      var cs = parseInt(w.dataset.cstart);" +
                "      var ce = parseInt(w.dataset.cend);" +
                "      if (firstChar === -1 || cs < firstChar) firstChar = cs;" +
                "      if (ce > lastChar) lastChar = ce;" +
                "    }" +
                "  }" +
                "  if (matched.length === 0) return null;" +
                "  sel.removeAllRanges();" +
                "  return id + '\t' + '" + type + "' + '\t' + '" + color + "' + '\t' + firstChar + '\t' + lastChar + '\t' + text;" +
                "})();";

            Object res = view.getEngine().executeScript(script);
            if (res instanceof String s && s.contains("\t")) {
                String[] parts = s.split("\t", 6);
                if (parts.length == 6) {
                    Highlight h = new Highlight(
                        parts[0],
                        currentPdfPath.toString(),
                        currentSpread,
                        parts[1],
                        parts[2],
                        Integer.parseInt(parts[3]),
                        Integer.parseInt(parts[4]),
                        parts[5]
                    );
                    savedHighlights.add(h);
                    return h;
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to create PDF highlight: " + e.getMessage());
        }
        return null;
    }

    @Override
    public String removeActiveHighlight() {
        try {
            String script =
                "(function() {" +
                "  var sel = window.getSelection();" +
                "  var ids = [];" +
                "  if (sel && sel.rangeCount > 0 && !sel.isCollapsed) {" +
                "    var range = sel.getRangeAt(0);" +
                "    var words = document.querySelectorAll('.pdf-word.pt-highlight, .pdf-word.pt-underline');" +
                "    for (var i = 0; i < words.length; i++) {" +
                "      if (range.intersectsNode(words[i])) {" +
                "        var hid = words[i].dataset.hlId;" +
                "        if (hid && ids.indexOf(hid) === -1) ids.push(hid);" +
                "      }" +
                "    }" +
                "  }" +
                "  if (ids.length === 0 && sel && sel.anchorNode) {" +
                "    var p = sel.anchorNode.nodeType === 3 ? sel.anchorNode.parentElement : sel.anchorNode;" +
                "    while (p && p !== document.body) {" +
                "      if (p.dataset && p.dataset.hlId) {" +
                "        ids.push(p.dataset.hlId); break;" +
                "      }" +
                "      p = p.parentElement;" +
                "    }" +
                "  }" +
                "  if (ids.length === 0 && sel && sel.focusNode) {" +
                "    var p = sel.focusNode.nodeType === 3 ? sel.focusNode.parentElement : sel.focusNode;" +
                "    while (p && p !== document.body) {" +
                "      if (p.dataset && p.dataset.hlId) {" +
                "        ids.push(p.dataset.hlId); break;" +
                "      }" +
                "      p = p.parentElement;" +
                "    }" +
                "  }" +
                "  if (ids.length === 0 && window.__ptLastClickedHlId) {" +
                "    ids.push(window.__ptLastClickedHlId);" +
                "  }" +
                "  if (ids.length === 0) return null;" +
                "  for (var k = 0; k < ids.length; k++) {" +
                "    var targetId = ids[k];" +
                "    var matching = document.querySelectorAll('[data-hl-id=\"' + targetId + '\"]');" +
                "    for (var j = 0; j < matching.length; j++) {" +
                "      var el = matching[j];" +
                "      el.classList.remove('pt-highlight', 'pt-underline');" +
                "      el.style.removeProperty('background-color');" +
                "      el.style.removeProperty('border-bottom-color');" +
                "      delete el.dataset.hlId;" +
                "    }" +
                "  }" +
                "  window.__ptLastClickedHlId = null;" +
                "  return ids.join(',');" +
                "})();";

            Object res = view.getEngine().executeScript(script);
            if (res instanceof String idStr && !idStr.isEmpty()) {
                String[] ids = idStr.split(",");
                for (String singleId : ids) {
                    String clean = singleId.trim();
                    savedHighlights.removeIf(h -> Objects.equals(h.id(), clean));
                }
                return idStr;
            }
        } catch (Exception e) {
            System.err.println("Failed to remove active PDF highlight: " + e.getMessage());
        }
        return null;
    }

    @Override
    public void restoreHighlights(List<Highlight> highlights) {
        this.savedHighlights = new ArrayList<>(highlights != null ? highlights : List.of());
        restoreHighlightsForCurrentSpread();
    }
}
