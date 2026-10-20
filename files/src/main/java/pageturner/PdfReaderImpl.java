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
 * full coordinate-mapped text selection for highlights and underlines.
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

    private final Map<Integer, List<TextLine>> textLineCache = new ConcurrentHashMap<>();
    private List<Highlight> savedHighlights = new ArrayList<>();

    record TextLine(String text, float x, float y, float width, float height) {}

    static class LineExtractor extends PDFTextStripper {
        final List<TextLine> lines = new ArrayList<>();
        LineExtractor() throws IOException { super(); }
        @Override
        protected void writeString(String string, List<TextPosition> textPositions) throws IOException {
            if (!textPositions.isEmpty() && !string.trim().isEmpty()) {
                TextPosition first = textPositions.get(0);
                TextPosition last = textPositions.get(textPositions.size() - 1);
                float x = first.getXDirAdj();
                float y = first.getYDirAdj();
                float w = (last.getXDirAdj() + last.getWidthDirAdj()) - first.getXDirAdj();
                float h = first.getHeightDir();
                lines.add(new TextLine(string, x, y, Math.max(1f, w), Math.max(1f, h)));
            }
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
        textLineCache.clear();

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
        List<TextLine> lines = textLineCache.computeIfAbsent(pageIndex, idx -> {
            try {
                LineExtractor ex = new LineExtractor();
                ex.setStartPage(idx + 1);
                ex.setEndPage(idx + 1);
                ex.getText(doc);
                return ex.lines;
            } catch (Exception e) {
                return List.of();
            }
        });

        float pageW = doc.getPage(pageIndex).getMediaBox().getWidth();
        float pageH = doc.getPage(pageIndex).getMediaBox().getHeight();

        StringBuilder sb = new StringBuilder();
        for (TextLine tl : lines) {
            float leftPct = (tl.x() / pageW) * 100f;
            float topPct  = ((tl.y() - tl.height()) / pageH) * 100f;
            float wPct    = (tl.width() / pageW) * 100f;
            float hPct    = (tl.height() / pageH) * 100f;
            sb.append("<span class=\"pdf-text-line\" style=\"")
              .append(String.format(Locale.US, "left:%.2f%%; top:%.2f%%; width:%.2f%%; height:%.2f%%;", leftPct, topPct, wPct, hPct))
              .append("\">")
              .append(escapeHtml(tl.text()))
              .append("</span>\n");
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
          .pdf-text-line {
            position: absolute;
            color: transparent;
            white-space: pre;
            cursor: text;
            font-family: Georgia, serif;
            transform-origin: 0 0;
          }
          .pdf-text-line::selection {
            background: rgba(43, 114, 219, 0.35);
          }
          mark.pt-highlight {
            background-color: rgba(255, 229, 143, 0.6) !important;
            color: transparent !important;
            border-radius: 2px;
          }
          span.pt-underline {
            text-decoration: underline !important;
            text-decoration-color: #d46b08 !important;
            text-decoration-thickness: 2.5px !important;
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
        window.addEventListener('keydown', function(e) {
          if (e.key === 'ArrowRight' || e.key === 'ArrowLeft' || e.key === 'PageDown' || e.key === 'PageUp') {
            e.preventDefault();
          }
        }, true);

        window.__ptRestore = function(items) {
          if (!items || !items.length) return;
          for (var i = 0; i < items.length; i++) {
            var h = items[i];
            try {
              var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
              var cur = 0, startNode = null, startOff = 0, endNode = null, endOff = 0;
              var node;
              while ((node = walker.nextNode())) {
                var len = node.nodeValue.length;
                if (!startNode && cur + len >= h.charStart) {
                  startNode = node;
                  startOff = Math.max(0, h.charStart - cur);
                }
                if (cur + len >= h.charEnd) {
                  endNode = node;
                  endOff = Math.min(len, h.charEnd - cur);
                  break;
                }
                cur += len;
              }
              if (startNode && endNode) {
                var range = document.createRange();
                range.setStart(startNode, startOff);
                range.setEnd(endNode, endOff);
                var tag = (h.type === 'underline') ? 'span' : 'mark';
                var cls = (h.type === 'underline') ? 'pt-underline' : 'pt-highlight';
                var el = document.createElement(tag);
                el.className = cls;
                el.dataset.hlId = h.id;
                if (h.color) el.style.setProperty((h.type === 'underline' ? 'text-decoration-color' : 'background-color'), h.color, 'important');
                try { range.surroundContents(el); } catch(ex) {
                  var fragment = range.extractContents();
                  el.appendChild(fragment);
                  range.insertNode(el);
                }
              }
            } catch(err) { console.error('Restore highlight error', err); }
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
                "  var preRange = range.cloneRange();" +
                "  preRange.selectNodeContents(document.body);" +
                "  preRange.setEnd(range.startContainer, range.startOffset);" +
                "  var charStart = preRange.toString().length;" +
                "  var charEnd = charStart + text.length;" +
                "  var id = 'hl_' + Date.now() + '_' + Math.floor(Math.random() * 10000);" +
                "  var tag = ('" + type + "' === 'underline') ? 'span' : 'mark';" +
                "  var cls = ('" + type + "' === 'underline') ? 'pt-underline' : 'pt-highlight';" +
                "  var el = document.createElement(tag);" +
                "  el.className = cls;" +
                "  el.dataset.hlId = id;" +
                "  el.style.setProperty(('" + type + "' === 'underline' ? 'text-decoration-color' : 'background-color'), '" + color + "', 'important');" +
                "  try {" +
                "    range.surroundContents(el);" +
                "  } catch(e) {" +
                "    var fragment = range.extractContents();" +
                "    el.appendChild(fragment);" +
                "    range.insertNode(el);" +
                "  }" +
                "  sel.removeAllRanges();" +
                "  return id + '\t' + '" + type + "' + '\t' + '" + color + "' + '\t' + charStart + '\t' + charEnd + '\t' + text;" +
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
                "  if (!sel || !sel.anchorNode) return null;" +
                "  var node = sel.anchorNode;" +
                "  while (node && node !== document.body) {" +
                "    if (node.dataset && node.dataset.hlId) {" +
                "      var id = node.dataset.hlId;" +
                "      var parent = node.parentNode;" +
                "      while (node.firstChild) parent.insertBefore(node.firstChild, node);" +
                "      parent.removeChild(node);" +
                "      return id;" +
                "    }" +
                "    node = node.parentNode;" +
                "  }" +
                "  return null;" +
                "})();";

            Object res = view.getEngine().executeScript(script);
            if (res instanceof String id && !id.isEmpty()) {
                savedHighlights.removeIf(h -> Objects.equals(h.id(), id));
                return id;
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
