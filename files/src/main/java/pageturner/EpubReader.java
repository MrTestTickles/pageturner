package pageturner;

import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.web.WebView;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.*;
import java.util.zip.ZipFile;

/**
 * Dual-page EPUB reader with two pages visible side-by-side in a single view.
 * Uses CSS multi-column layout with 2 columns per spread and a spine divider.
 * Flips two pages at a time instantaneously without creeping scrolls,
 * auto-advances across chapters, computes total book pages, and persists highlights.
 */
interface EpubReader {
    void      load(Path epub) throws Exception;
    void      goTo(BookPosition pos);
    void      goToSpread(int chapter, int spread);
    void      nextPage();
    void      prevPage();
    String    bookTitle();
    String    chapterTitle(int chapter);
    int       chapterCount();
    int       currentChapter();
    int       currentSpread();
    int       totalSpreads();
    int       currentBookPage();
    int       totalBookPages();
    double    bookProgress();
    Highlight createHighlight(String type, String color);
    String    removeActiveHighlight();
    void      restoreHighlights(List<Highlight> highlights);
    void      onChapterChange(Consumer<Integer> listener);
    void      onPageChange(PageChangeListener listener);
    WebView   getView();
    WebView   getView2(); // preserved for interface compatibility
    Path      currentEpubPath();
}

@FunctionalInterface
interface PageChangeListener {
    void onPageChange(int chapter, int spread, int totalSpreads, int currentBookPage, int totalBookPages);
}

class EpubReaderImpl implements EpubReader {

    private final WebView view;
    private boolean active = false;

    private final List<String> rawHtml = new ArrayList<>();
    private final List<String> titles  = new ArrayList<>();
    private Path currentEpubPath = null;
    private String bookTitle = "";

    private int   current       = 0; // chapter index
    private int   spread        = 0; // spread index (each spread = 2 pages)
    private int   totalSpreads  = 1;
    private int   pendingTarget = 0; // 0 = first spread, -1 = last spread

    private int[]  chapterCharCounts;
    private int[]  chapterSpreads;
    private double charsPerSpread = 2400.0; // default estimated chars per 2-page spread

    private Consumer<Integer>   onChapterChange;
    private PageChangeListener  onPageChange;

    public EpubReaderImpl() {
        this(new WebView());
    }

    public EpubReaderImpl(WebView view) {
        this.view = view;
        // Single listener attached once in constructor to prevent memory/listener leaks
        view.getEngine().getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            if (active && newState == Worker.State.SUCCEEDED) {
                Platform.runLater(this::measureAndScroll);
            }
        });

        // Re-align pages if window is resized
        view.widthProperty().addListener((obs, o, n) -> {
            if (active && n.doubleValue() > 0 && totalSpreads > 0) {
                Platform.runLater(this::measureAndScroll);
            }
        });
    }

    public void unload() {
        this.active = false;
    }

    @Override
    public void load(Path epub) throws Exception {
        this.active = true;
        currentEpubPath = epub;
        rawHtml.clear();
        titles.clear();
        bookTitle = "";
        current = 0;
        spread = 0;
        totalSpreads = 1;
        pendingTarget = 0;

        try (var zip = new ZipFile(epub.toFile())) {
            String opfPath = findOpfPath(zip);
            String opfDir  = opfPath.contains("/")
                    ? opfPath.substring(0, opfPath.lastIndexOf('/') + 1) : "";
            parseSpine(zip, opfDir, opfPath);
        }

        // Initialize chapter sizes & estimated spreads
        chapterCharCounts = new int[rawHtml.size()];
        chapterSpreads    = new int[rawHtml.size()];
        for (int i = 0; i < rawHtml.size(); i++) {
            String clean = rawHtml.get(i).replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
            chapterCharCounts[i] = clean.length();
            chapterSpreads[i]    = Math.max(1, (int) Math.round((double) chapterCharCounts[i] / charsPerSpread));
        }

        if (!rawHtml.isEmpty()) {
            renderChapter(0, 0);
        }
    }

    @Override
    public void goTo(BookPosition pos) {
        int ch = pos.chapter();
        if (ch < 0 || ch >= rawHtml.size()) return;
        if (ch != current) {
            renderChapter(ch, 0);
        } else {
            spread = 0;
            scrollToSpread(0);
            firePageChange();
        }
    }

    @Override
    public void goToSpread(int ch, int sp) {
        if (ch < 0 || ch >= rawHtml.size()) return;
        renderChapter(ch, Math.max(0, sp));
    }

    @Override
    public void nextPage() {
        if (spread + 1 < totalSpreads) {
            spread++;
            scrollToSpread(spread);
            firePageChange();
        } else if (current + 1 < rawHtml.size()) {
            // End of chapter reached: turn to next chapter's first spread
            renderChapter(current + 1, 0);
        }
    }

    @Override
    public void prevPage() {
        if (spread > 0) {
            spread--;
            scrollToSpread(spread);
            firePageChange();
        } else if (current > 0) {
            // Beginning of chapter: turn to last spread of previous chapter
            renderChapter(current - 1, -1);
        }
    }

    @Override
    public String bookTitle() {
        if (bookTitle != null && !bookTitle.isEmpty()) return bookTitle;
        if (currentEpubPath != null) {
            return currentEpubPath.getFileName().toString().replace(".epub", "").replace("_", " ");
        }
        return "Untitled Book";
    }

    @Override public String  chapterTitle(int ch) { return ch >= 0 && ch < titles.size() ? titles.get(ch) : ""; }
    @Override public int     chapterCount()        { return rawHtml.size(); }
    @Override public int     currentChapter()      { return current; }
    @Override public int     currentSpread()       { return spread; }
    @Override public int     totalSpreads()        { return totalSpreads; }
    @Override public WebView getView()             { return view; }
    @Override public WebView getView2()            { return null; }
    @Override public Path    currentEpubPath()     { return currentEpubPath; }
    @Override public void    onChapterChange(Consumer<Integer> l) { this.onChapterChange = l; }
    @Override public void    onPageChange(PageChangeListener l)   { this.onPageChange = l; }

    @Override
    public int totalBookPages() {
        if (chapterSpreads == null || chapterSpreads.length == 0) return 1;
        int sum = 0;
        for (int s : chapterSpreads) sum += s * 2;
        return Math.max(1, sum);
    }

    @Override
    public int currentBookPage() {
        if (chapterSpreads == null || chapterSpreads.length == 0) return 1;
        int pagesBefore = 0;
        for (int i = 0; i < current && i < chapterSpreads.length; i++) {
            pagesBefore += chapterSpreads[i] * 2;
        }
        int cur = pagesBefore + (spread * 2 + 2);
        return Math.min(cur, totalBookPages());
    }

    @Override
    public double bookProgress() {
        int total = totalBookPages();
        return total > 0 ? (double) currentBookPage() / total : 0.0;
    }

    // ── highlighting & underlining ───────────────────────────────────────────

    @Override
    public Highlight createHighlight(String type, String color) {
        if (currentEpubPath == null) return null;
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
                "  el.style.setProperty('" + (type.equals("underline") ? "text-decoration-color" : "background-color") + "', '" + color + "', 'important');" +
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
                    return new Highlight(
                        parts[0],
                        currentEpubPath.toString(),
                        current,
                        parts[1],
                        parts[2],
                        Integer.parseInt(parts[3]),
                        Integer.parseInt(parts[4]),
                        parts[5]
                    );
                }
            }
        } catch (Exception ex) {
            System.err.println("Highlight error: " + ex.getMessage());
        }
        return null;
    }

    @Override
    public String removeActiveHighlight() {
        try {
            String script =
                "(function() {" +
                "  var sel = window.getSelection();" +
                "  var targets = [];" +
                "  if (sel && sel.rangeCount > 0 && !sel.isCollapsed) {" +
                "    var range = sel.getRangeAt(0);" +
                "    var all = document.querySelectorAll('.pt-highlight, .pt-underline');" +
                "    for (var i = 0; i < all.length; i++) {" +
                "      if (range.intersectsNode(all[i])) targets.push(all[i]);" +
                "    }" +
                "  }" +
                "  if (targets.length === 0 && sel && sel.anchorNode) {" +
                "    var p = sel.anchorNode.nodeType === 3 ? sel.anchorNode.parentElement : sel.anchorNode;" +
                "    while (p && p !== document.body) {" +
                "      if (p.classList && (p.classList.contains('pt-highlight') || p.classList.contains('pt-underline'))) {" +
                "        targets.push(p); break;" +
                "      }" +
                "      p = p.parentElement;" +
                "    }" +
                "  }" +
                "  if (targets.length === 0 && sel && sel.focusNode) {" +
                "    var p = sel.focusNode.nodeType === 3 ? sel.focusNode.parentElement : sel.focusNode;" +
                "    while (p && p !== document.body) {" +
                "      if (p.classList && (p.classList.contains('pt-highlight') || p.classList.contains('pt-underline'))) {" +
                "        targets.push(p); break;" +
                "      }" +
                "      p = p.parentElement;" +
                "    }" +
                "  }" +
                "  if (targets.length === 0 && window.__ptLastClickedHighlight && document.body.contains(window.__ptLastClickedHighlight)) {" +
                "    targets.push(window.__ptLastClickedHighlight);" +
                "  }" +
                "  if (targets.length > 0) {" +
                "    var ids = [];" +
                "    for (var i = 0; i < targets.length; i++) {" +
                "      var t = targets[i];" +
                "      var id = t.dataset.hlId;" +
                "      if (id && ids.indexOf(id) === -1) ids.push(id);" +
                "      var parent = t.parentNode;" +
                "      if (parent) {" +
                "        while (t.firstChild) parent.insertBefore(t.firstChild, t);" +
                "        parent.removeChild(t);" +
                "      }" +
                "    }" +
                "    window.__ptLastClickedHighlight = null;" +
                "    return ids.join(',');" +
                "  }" +
                "  return null;" +
                "})();";
            Object res = view.getEngine().executeScript(script);
            return res instanceof String s ? s : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override
    public void restoreHighlights(List<Highlight> highlights) {
        if (highlights == null || highlights.isEmpty()) return;
        try {
            StringBuilder js = new StringBuilder();
            js.append("(function() { var list = [");
            for (int i = 0; i < highlights.size(); i++) {
                Highlight h = highlights.get(i);
                if (h.chapter() != current) continue;
                js.append("{id:'").append(h.id())
                  .append("',type:'").append(h.type())
                  .append("',color:'").append(h.color())
                  .append("',charStart:").append(h.charStart())
                  .append(",charEnd:").append(h.charEnd())
                  .append("},");
            }
            js.append("];");
            js.append("if (window.__ptRestore) window.__ptRestore(list); })();");
            view.getEngine().executeScript(js.toString());
        } catch (Exception ex) {
            System.err.println("Error restoring highlights: " + ex.getMessage());
        }
    }

    // ── rendering & pagination ───────────────────────────────────────────────

    private void renderChapter(int ch, int targetSpread) {
        current = ch;
        pendingTarget = targetSpread;
        spread = 0;
        totalSpreads = 1;

        String html = injectDualPageStyles(rawHtml.get(ch));
        Platform.runLater(() -> view.getEngine().loadContent(html, "text/html"));

        if (onChapterChange != null) onChapterChange.accept(ch);
    }

    private void measureAndScroll() {
        try {
            Object winWObj = view.getEngine().executeScript("window.innerWidth");
            Object scrollWObj = view.getEngine().executeScript("document.documentElement.scrollWidth || document.body.scrollWidth");

            double winW = winWObj instanceof Number ? ((Number) winWObj).doubleValue() : view.getWidth();
            double scrollW = scrollWObj instanceof Number ? ((Number) scrollWObj).doubleValue() : winW;

            if (winW <= 0) winW = 800;
            totalSpreads = Math.max(1, (int) Math.round(scrollW / winW));

            if (chapterSpreads != null && current < chapterSpreads.length) {
                chapterSpreads[current] = totalSpreads;
                // Refine characters per spread calibration
                if (chapterCharCounts != null && chapterCharCounts[current] > 0) {
                    charsPerSpread = (double) chapterCharCounts[current] / totalSpreads;
                }
            }

            if (pendingTarget < 0) {
                spread = totalSpreads - 1;
            } else {
                spread = Math.min(pendingTarget, totalSpreads - 1);
            }
            pendingTarget = spread;

            scrollToSpread(spread);
            firePageChange();

            // Re-apply saved highlights for this chapter
            if (currentEpubPath != null) {
                var state = StateStore.load();
                restoreHighlights(state.getHighlightsFor(currentEpubPath.toString(), current));
            }
        } catch (Exception ignored) {}
    }

    private void scrollToSpread(int s) {
        try {
            // Immediate jump to spread — mathematically aligned to 100vw pitch with zero drift
            view.getEngine().executeScript("window.scrollTo(Math.round(" + s + " * window.innerWidth), 0);");
        } catch (Exception ignored) {}
    }

    private String injectDualPageStyles(String src) {
        // Pixel-perfect spread geometry:
        // Gap = 80px (between left and right pages at central book spine).
        // Outer margin = Gap / 2 = 40px (left and right screen margins).
        // Column width = (100vw - 160px) / 2.
        // Total spread pitch = 40px + colWidth + 80px + colWidth + 40px = 100vw.
        // Every spread aligns with zero cumulative drift across all chapters.
        String css =
            "<style id='__pageturner_css__'>" +
            "*, *::before, *::after { box-sizing: border-box; }" +
            "html {" +
            "  margin: 0 !important; padding: 0 !important;" +
            "  width: 100vw !important; height: 100vh !important;" +
            "  overflow-x: hidden !important; overflow-y: hidden !important;" +
            "  scroll-behavior: auto !important;" +
            "  background: #fbf7ee !important;" +
            "}" +
            "body {" +
            "  margin: 0 !important;" +
            "  padding: 36px 40px !important;" +
            "  width: 100vw !important; height: 100vh !important;" +
            "  column-width: calc((100vw - 160px) / 2) !important;" +
            "  column-gap: 80px !important;" +
            "  column-fill: auto !important;" +
            "  column-rule: 1px solid #dfd7c8 !important;" +
            "  font-family: Georgia, 'Times New Roman', serif !important;" +
            "  font-size: 16.5px !important;" +
            "  line-height: 1.75 !important;" +
            "  color: #2b2723 !important;" +
            "  background: #fbf7ee !important;" +
            "}" +
            "::-webkit-scrollbar { display: none !important; }" +
            "img { max-width: 100% !important; height: auto !important; break-inside: avoid !important; }" +
            "p { margin: 0 0 0.9em 0 !important; orphans: 2 !important; widows: 2 !important; text-align: justify !important; }" +
            "h1, h2, h3, h4 { margin-top: 0 !important; break-after: avoid !important; color: #1c1815 !important; font-family: Georgia, serif !important; }" +
            "table, pre, blockquote { break-inside: avoid !important; }" +
            "mark.pt-highlight {" +
            "  background-color: #ffe58f !important;" +
            "  color: inherit !important;" +
            "  border-radius: 2px !important;" +
            "  padding: 1px 2px !important;" +
            "  box-shadow: 0 0 0 1px rgba(220,180,0,0.3) !important;" +
            "}" +
            "span.pt-underline {" +
            "  text-decoration: underline !important;" +
            "  text-decoration-color: #d46b08 !important;" +
            "  text-decoration-thickness: 2.5px !important;" +
            "  text-underline-offset: 3px !important;" +
            "}" +
            "</style>" +
            "<script>" +
            "document.addEventListener('click', function(e) {\n" +
            "  var p = e.target;\n" +
            "  while (p && p !== document.body) {\n" +
            "    if (p.classList && (p.classList.contains('pt-highlight') || p.classList.contains('pt-underline'))) {\n" +
            "      window.__ptLastClickedHighlight = p;\n" +
            "      return;\n" +
            "    }\n" +
            "    p = p.parentElement;\n" +
            "  }\n" +
            "}, true);\n" +
            "// Suppress default webkit arrow scrolling to ensure instantaneous 2-page turns\n" +
            "window.addEventListener('keydown', function(e) {\n" +
            "  if (e.key === 'ArrowRight' || e.key === 'ArrowLeft' || e.key === 'PageDown' || e.key === 'PageUp') {\n" +
            "    e.preventDefault();\n" +
            "  }\n" +
            "}, true);\n" +
            "window.__ptRestore = function(items) {\n" +
            "  if (!items || !items.length) return;\n" +
            "  for (var i = 0; i < items.length; i++) {\n" +
            "    var h = items[i];\n" +
            "    try {\n" +
            "      var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);\n" +
            "      var cur = 0, startNode = null, startOff = 0, endNode = null, endOff = 0;\n" +
            "      var node;\n" +
            "      while ((node = walker.nextNode())) {\n" +
            "        var len = node.nodeValue.length;\n" +
            "        if (!startNode && cur + len >= h.charStart) {\n" +
            "          startNode = node;\n" +
            "          startOff = Math.max(0, h.charStart - cur);\n" +
            "        }\n" +
            "        if (cur + len >= h.charEnd) {\n" +
            "          endNode = node;\n" +
            "          endOff = Math.min(len, h.charEnd - cur);\n" +
            "          break;\n" +
            "        }\n" +
            "        cur += len;\n" +
            "      }\n" +
            "      if (startNode && endNode) {\n" +
            "        var range = document.createRange();\n" +
            "        range.setStart(startNode, startOff);\n" +
            "        range.setEnd(endNode, endOff);\n" +
            "        var tag = (h.type === 'underline') ? 'span' : 'mark';\n" +
            "        var cls = (h.type === 'underline') ? 'pt-underline' : 'pt-highlight';\n" +
            "        var el = document.createElement(tag);\n" +
            "        el.className = cls;\n" +
            "        el.dataset.hlId = h.id;\n" +
            "        if (h.color) el.style.setProperty((h.type === 'underline' ? 'text-decoration-color' : 'background-color'), h.color, 'important');\n" +
            "        try { range.surroundContents(el); } catch(ex) {\n" +
            "          var fragment = range.extractContents();\n" +
            "          el.appendChild(fragment);\n" +
            "          range.insertNode(el);\n" +
            "        }\n" +
            "      }\n" +
            "    } catch(err) { console.error('Restore highlight error', err); }\n" +
            "  }\n" +
            "};\n" +
            "</script>";

        if (src.contains("</head>")) {
            return src.replace("</head>", css + "</head>");
        }
        if (src.contains("<head>")) {
            return src.replace("<head>", "<head>" + css);
        }
        return css + src;
    }

    private void firePageChange() {
        if (onPageChange != null) {
            onPageChange.onPageChange(current, spread, totalSpreads, currentBookPage(), totalBookPages());
        }
    }

    // ── EPUB parsing ──────────────────────────────────────────────────────────

    private String findOpfPath(ZipFile zip) throws Exception {
        var bytes = zip.getInputStream(zip.getEntry("META-INF/container.xml")).readAllBytes();
        var doc   = parseXml(bytes);
        return ((Element) doc.getElementsByTagName("rootfile").item(0)).getAttribute("full-path");
    }

    private void parseSpine(ZipFile zip, String dir, String opfPath) throws Exception {
        var doc = parseXml(zip.getInputStream(zip.getEntry(opfPath)).readAllBytes());

        // Extract book title
        var titleNodes = doc.getElementsByTagName("dc:title");
        if (titleNodes.getLength() == 0) titleNodes = doc.getElementsByTagName("title");
        if (titleNodes.getLength() > 0) {
            bookTitle = titleNodes.item(0).getTextContent().trim();
        } else if (currentEpubPath != null) {
            bookTitle = currentEpubPath.getFileName().toString().replace(".epub", "").replace("_", " ");
        }

        var manifest = new HashMap<String, String>();
        var items = doc.getElementsByTagName("item");
        for (int i = 0; i < items.getLength(); i++) {
            var el   = (Element) items.item(i);
            String href = el.getAttribute("href");
            if (href.contains("?")) href = href.substring(0, href.indexOf('?'));
            if (href.contains("#")) href = href.substring(0, href.indexOf('#'));
            manifest.put(el.getAttribute("id"), dir + href);
        }

        var itemrefs = doc.getElementsByTagName("itemref");
        for (int i = 0; i < itemrefs.getLength(); i++) {
            String path = manifest.get(((Element) itemrefs.item(i)).getAttribute("idref"));
            if (path == null) continue;
            var entry = zip.getEntry(path);
            if (entry == null) continue;
            String content = new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
            rawHtml.add(content);
            titles.add(extractTitle(content, "Chapter " + rawHtml.size()));
        }
    }

    private org.w3c.dom.Document parseXml(byte[] bytes) throws Exception {
        var dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(false);
        dbf.setValidating(false);
        dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        return dbf.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }

    private String extractTitle(String src, String fallback) {
        var m = Pattern.compile("<title[^>]*>([^<]+)</title>", Pattern.CASE_INSENSITIVE).matcher(src);
        if (m.find()) return m.group(1).trim();
        m = Pattern.compile("<h[1-3][^>]*>([^<]+)</h[1-3]>", Pattern.CASE_INSENSITIVE).matcher(src);
        if (m.find()) return m.group(1).trim();
        return fallback;
    }
}
