package pageturner;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.*;

public class PageTurnerApp extends Application {

    private final EpubReader     reader = new UnifiedBookReader();
    private final AudioPlayer    player = new JavaFXAudioPlayer();
    private final SyncBridgeImpl sync   = new SyncBridgeImpl();

    private final List<LibraryEntry> library       = new ArrayList<>();
    private final List<Highlight>    allHighlights = new ArrayList<>();
    private String currentBookId = null;
    private Path   currentSyncPath = null;

    // Main UI elements
    private Stage       primaryStage;
    private Label       chapterLabel;
    private Label       pageLabel;
    private Label       trackLabel;
    private ProgressBar bookProgressBar;
    private Label       bookProgressLabel;
    private Button      playPauseBtn;
    private Button      skipPrevBtn;
    private Button      skipNextBtn;
    private Slider      seekBar;
    private Label       timeCurrent;
    private Label       timeDuration;

    private Duration totalDuration = Duration.UNKNOWN;
    private boolean  seeking       = false;
    private long     lastSaveTimeMs = 0;

    @Override
    public void start(Stage stage) {
        this.primaryStage = stage;

        // Load persisted library & highlights from disk
        var savedState = StateStore.load();
        if (savedState.library() != null) {
            library.addAll(savedState.library());
        }
        if (savedState.highlights() != null) {
            allHighlights.addAll(savedState.highlights());
        }
        currentBookId = savedState.currentBookId();

        var scene = new Scene(buildRoot(), 1280, 820);

        // Intercept keys at the Scene level BEFORE WebView consumes them
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.RIGHT || e.getCode() == KeyCode.PAGE_DOWN) {
                reader.nextPage();
                e.consume();
            } else if (e.getCode() == KeyCode.LEFT || e.getCode() == KeyCode.PAGE_UP) {
                reader.prevPage();
                e.consume();
            } else if (e.getCode() == KeyCode.SPACE && e.getTarget() instanceof Scene) {
                togglePlay();
                e.consume();
            } else if (e.getCode() == KeyCode.H && e.isShortcutDown()) {
                triggerHighlight();
                e.consume();
            } else if (e.getCode() == KeyCode.U && e.isShortcutDown()) {
                triggerUnderline();
                e.consume();
            } else if (e.getCode() == KeyCode.L && e.isShortcutDown()) {
                openLibraryDialog();
                e.consume();
            }
        });

        stage.setTitle("PageTurner");
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> saveCurrentState());
        stage.show();

        // ── listener wiring ──────────────────────────────────────────────────

        // Audio playback progress -> sync EPUB + update seek bar + throttled auto-save
        player.onProgress(pos -> Platform.runLater(() -> {
            timeCurrent.setText(fmt(pos));
            if (!seeking && !totalDuration.isUnknown() && totalDuration.greaterThan(Duration.ZERO)) {
                seekBar.setValue(pos.toSeconds() / totalDuration.toSeconds() * 100.0);
            }
            if (sync.isLoaded()) {
                reader.goTo(sync.audioToBook(pos));
            }
            long now = System.currentTimeMillis();
            if (now - lastSaveTimeMs > 4000) {
                lastSaveTimeMs = now;
                saveCurrentState();
            }
        }));

        // Track auto-advance -> update labels & seek bar
        player.onTrackChange(i -> Platform.runLater(() -> {
            updateAudioControls();
            seekBar.setValue(0);
            timeCurrent.setText("00:00:00");
            if (sync.isLoaded()) {
                reader.goTo(sync.audioToBook(sync.bookToAudio(i)));
            }
            saveCurrentState();
        }));

        // Chapter title change
        reader.onChapterChange(ch -> Platform.runLater(() -> {
            chapterLabel.setText(reader.chapterTitle(ch));
            saveCurrentState();
        }));

        // Page spread change -> update chapter indicator & overall book progress bar
        reader.onPageChange((ch, sp, totalSpreads, curBookPage, totalBookPages) -> Platform.runLater(() -> {
            int p1 = sp * 2 + 1;
            int p2 = Math.min(sp * 2 + 2, totalSpreads * 2);
            pageLabel.setText("Chapter Spread: " + (sp + 1) + " / " + totalSpreads + "  (Pages " + p1 + "\u2013" + p2 + ")");

            double progress = (double) curBookPage / Math.max(1, totalBookPages);
            bookProgressBar.setProgress(progress);
            int pct = (int) Math.round(progress * 100);
            bookProgressLabel.setText("Book Page " + curBookPage + " of " + totalBookPages + " (" + pct + "%)");

            saveCurrentState();
        }));

        // ── restore active book from library on startup ───────────────────────
        LibraryEntry active = savedState.getCurrentBook();
        if (active != null) {
            restoreBook(active);
        }
    }

    private void restoreBook(LibraryEntry entry) {
        if (entry == null || entry.epubPath() == null) return;
        Path epub = Path.of(entry.epubPath());
        if (!Files.exists(epub)) return;

        try {
            currentBookId = entry.id();
            reader.load(epub);
            chapterLabel.setText(reader.chapterTitle(entry.chapter()));
            if (entry.chapter() > 0 || entry.spread() > 0) {
                Platform.runLater(() -> reader.goToSpread(entry.chapter(), entry.spread()));
            }
        } catch (Exception ex) {
            System.err.println("Could not restore EPUB: " + ex.getMessage());
        }

        // Restore Sync
        if (entry.syncPath() != null && Files.exists(Path.of(entry.syncPath()))) {
            try {
                sync.load(Path.of(entry.syncPath()));
                currentSyncPath = Path.of(entry.syncPath());
            } catch (Exception ex) {
                System.err.println("Could not restore sync: " + ex.getMessage());
            }
        } else {
            currentSyncPath = null;
        }

        // Restore Audiobook
        if (entry.audioPath() != null && Files.exists(Path.of(entry.audioPath()))) {
            try {
                Path aPath = Path.of(entry.audioPath());
                Duration resumePos = Duration.seconds(entry.audioPositionSeconds());
                if (entry.isFolder()) {
                    player.loadFolder(aPath, dur -> Platform.runLater(() -> {
                        totalDuration = dur;
                        timeDuration.setText(fmt(dur));
                        updateAudioControls();
                        if (resumePos.greaterThan(Duration.ZERO)) {
                            timeCurrent.setText(fmt(resumePos));
                            if (dur.greaterThan(Duration.ZERO)) {
                                seekBar.setValue(resumePos.toSeconds() / dur.toSeconds() * 100.0);
                            }
                        }
                    }));
                    if (entry.trackIndex() > 0) {
                        player.jumpTrack(entry.trackIndex(), resumePos);
                    }
                } else {
                    player.load(aPath, dur -> Platform.runLater(() -> {
                        totalDuration = dur;
                        timeDuration.setText(fmt(dur));
                        updateAudioControls();
                        if (resumePos.greaterThan(Duration.ZERO)) {
                            player.seek(resumePos);
                            timeCurrent.setText(fmt(resumePos));
                            if (dur.greaterThan(Duration.ZERO)) {
                                seekBar.setValue(resumePos.toSeconds() / dur.toSeconds() * 100.0);
                            }
                        }
                    }));
                }
            } catch (Exception ex) {
                System.err.println("Could not restore audio: " + ex.getMessage());
            }
        }
    }

    private void saveCurrentState() {
        if (reader.currentEpubPath() == null) return;
        String epubStr = reader.currentEpubPath().toString();
        currentBookId = epubStr;

        LibraryEntry updated = new LibraryEntry(
            epubStr,
            reader.bookTitle(),
            epubStr,
            reader.currentChapter(),
            reader.chapterTitle(reader.currentChapter()),
            reader.currentSpread(),
            reader.currentBookPage(),
            reader.totalBookPages(),
            player.currentPath() != null ? player.currentPath().toString() : null,
            player.isFolder(),
            player.currentTrack(),
            player.position().toSeconds(),
            currentSyncPath != null ? currentSyncPath.toString() : null,
            System.currentTimeMillis()
        );

        // Put most recently updated book at the top of the library
        library.removeIf(e -> Objects.equals(e.id(), epubStr) || Objects.equals(e.epubPath(), epubStr));
        library.add(0, updated);

        StateStore.save(new StateStore.AppState(currentBookId, new ArrayList<>(library), new ArrayList<>(allHighlights)));
    }

    // ── Library Outline Dialog ────────────────────────────────────────────────

    private void openLibraryDialog() {
        // Save current progress before viewing library
        saveCurrentState();

        var dialog = new Stage();
        dialog.initOwner(primaryStage);
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("Library \u2014 Saved Books & Reading Places");

        var headerTitle = new Label("Reading Library");
        headerTitle.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #1c1815;");

        var headerSub = new Label("Switch between saved books. Each book preserves its exact page, spread, and audio place.");
        headerSub.setStyle("-fx-font-size: 11px; -fx-text-fill: #665e55;");

        var bookmarkBtn = btn("+ Save Current Book", () -> {
            saveCurrentState();
            refreshLibraryList(dialog);
        });
        bookmarkBtn.setStyle("-fx-font-size: 11px; -fx-padding: 4 10;");

        var headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        var headerBar = new HBox(12, new VBox(2, headerTitle, headerSub), headerSpacer, bookmarkBtn);
        headerBar.setAlignment(Pos.CENTER_LEFT);
        headerBar.setPadding(new Insets(14, 18, 14, 18));
        headerBar.setStyle("-fx-background-color: #faf6eb; -fx-border-color: #dfd7c8; -fx-border-width: 0 0 1 0;");

        var listContainer = new VBox(12);
        listContainer.setPadding(new Insets(16));
        listContainer.setStyle("-fx-background-color: #fbf7ee;");

        buildLibraryCards(listContainer, dialog);

        var scrollPane = new ScrollPane(listContainer);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background: #fbf7ee;");

        var closeBtn = btn("Close", dialog::close);
        closeBtn.setStyle("-fx-font-size: 12px; -fx-padding: 5 18;");
        var footerSpacer = new Region();
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);
        var footerBar = new HBox(footerSpacer, closeBtn);
        footerBar.setPadding(new Insets(10, 18, 10, 18));
        footerBar.setStyle("-fx-background-color: #faf6eb; -fx-border-color: #dfd7c8; -fx-border-width: 1 0 0 0;");

        var root = new BorderPane();
        root.setTop(headerBar);
        root.setCenter(scrollPane);
        root.setBottom(footerBar);

        var dialogScene = new Scene(root, 700, 520);
        dialog.setScene(dialogScene);
        dialog.show();
    }

    private void refreshLibraryList(Stage dialog) {
        if (dialog.getScene() != null && dialog.getScene().getRoot() instanceof BorderPane bp) {
            if (bp.getCenter() instanceof ScrollPane sp && sp.getContent() instanceof VBox listContainer) {
                buildLibraryCards(listContainer, dialog);
            }
        }
    }

    private void buildLibraryCards(VBox container, Stage dialog) {
        container.getChildren().clear();

        if (library.isEmpty()) {
            var emptyLabel = new Label("No books in your library yet.\nOpen an EPUB or PDF file to start your reading outline!");
            emptyLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #888; -fx-text-alignment: center;");
            emptyLabel.setAlignment(Pos.CENTER);
            var emptyBox = new VBox(emptyLabel);
            emptyBox.setAlignment(Pos.CENTER);
            emptyBox.setPadding(new Insets(60, 20, 60, 20));
            container.getChildren().add(emptyBox);
            return;
        }

        SimpleDateFormat dateFormat = new SimpleDateFormat("MMM d, yyyy h:mm a");

        for (LibraryEntry entry : library) {
            boolean isActive = currentBookId != null && (currentBookId.equals(entry.id()) || currentBookId.equals(entry.epubPath()));

            var titleLabel = new Label(entry.title());
            titleLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #1c1815;");

            var statusBadge = new Label(isActive ? "[ Currently Reading ]" : "");
            statusBadge.setStyle("-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: #c48b3f;");

            var titleRow = new HBox(8, titleLabel, statusBadge);
            titleRow.setAlignment(Pos.CENTER_LEFT);

            // Page info
            String pageInfoText = "Chapter " + (entry.chapter() + 1);
            if (entry.chapterTitle() != null && !entry.chapterTitle().isEmpty()) {
                pageInfoText += " (" + entry.chapterTitle() + ")";
            }
            if (entry.totalBookPages() > 0) {
                pageInfoText += "  \u2022  Page " + entry.currentBookPage() + " of " + entry.totalBookPages() + " (" + entry.progressPercent() + "%)";
            } else {
                pageInfoText += "  \u2022  Spread " + (entry.spread() + 1);
            }
            var pageInfo = new Label(pageInfoText);
            pageInfo.setStyle("-fx-font-size: 11px; -fx-text-fill: #443f38;");

            // Progress bar
            var bar = new ProgressBar(entry.progress());
            bar.setMaxWidth(Double.MAX_VALUE);
            bar.setPrefHeight(6);
            bar.setStyle("-fx-accent: #c48b3f;");

            // Audio info
            String audioInfoText;
            if (entry.audioPath() != null) {
                String aName = Path.of(entry.audioPath()).getFileName().toString();
                String timeStr = fmt(Duration.seconds(entry.audioPositionSeconds()));
                if (entry.isFolder()) {
                    audioInfoText = "\u266B Audio Playlist: Track " + (entry.trackIndex() + 1) + " @ " + timeStr + "  (" + aName + ")";
                } else {
                    audioInfoText = "\u266B Audio File @ " + timeStr + "  (" + aName + ")";
                }
            } else {
                audioInfoText = "No audiobook attached";
            }
            var audioInfo = new Label(audioInfoText);
            audioInfo.setStyle("-fx-font-size: 10.5px; -fx-text-fill: #665e55;");

            // Last accessed timestamp
            String dateStr = entry.lastReadTimestamp() > 0 ? dateFormat.format(new Date(entry.lastReadTimestamp())) : "Recently";
            var dateLabel = new Label("Last read: " + dateStr);
            dateLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #999;");

            var leftCol = new VBox(4, titleRow, pageInfo, bar, audioInfo, dateLabel);
            HBox.setHgrow(leftCol, Priority.ALWAYS);

            // Action buttons
            var resumeBtn = btn("\u25B6 Resume", () -> {
                dialog.close();
                switchToBook(entry);
            });
            resumeBtn.setStyle("-fx-background-color: #c48b3f; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 11px; -fx-padding: 6 14; -fx-background-radius: 4;");

            var removeBtn = btn("\u2715 Remove", () -> {
                library.remove(entry);
                StateStore.save(new StateStore.AppState(currentBookId, new ArrayList<>(library), new ArrayList<>(allHighlights)));
                buildLibraryCards(container, dialog);
            });
            removeBtn.setStyle("-fx-font-size: 10px; -fx-padding: 3 8; -fx-text-fill: #777;");

            var btnCol = new VBox(6, resumeBtn, removeBtn);
            btnCol.setAlignment(Pos.CENTER_RIGHT);

            var card = new HBox(14, leftCol, btnCol);
            card.setAlignment(Pos.CENTER_LEFT);
            card.setPadding(new Insets(12, 14, 12, 14));
            card.setStyle(isActive
                ? "-fx-background-color: #f7eedb; -fx-border-color: #c48b3f; -fx-border-width: 1.5; -fx-border-radius: 6; -fx-background-radius: 6;"
                : "-fx-background-color: #faf6eb; -fx-border-color: #dfd7c8; -fx-border-width: 1; -fx-border-radius: 6; -fx-background-radius: 6;");

            // Double click to resume
            card.setOnMouseClicked(ev -> {
                if (ev.getClickCount() == 2) {
                    dialog.close();
                    switchToBook(entry);
                }
            });

            container.getChildren().add(card);
        }
    }

    private void switchToBook(LibraryEntry target) {
        if (target == null) return;
        saveCurrentState(); // Save current before switching
        if (player.isPlaying()) player.pause();
        restoreBook(target);
    }

    // ── UI ────────────────────────────────────────────────────────────────────

    private Parent buildRoot() {
        // Book view wrapped with a bottom page navigation bar
        VBox.setVgrow(reader.getView(), Priority.ALWAYS);

        var prevPageBtn = btn("\u25C0 Prev 2 Pages", reader::prevPage);
        var nextPageBtn = btn("Next 2 Pages \u25B6", reader::nextPage);
        prevPageBtn.setStyle("-fx-font-size:11px; -fx-padding: 4 10;");
        nextPageBtn.setStyle("-fx-font-size:11px; -fx-padding: 4 10;");

        var navSpacer = new Region();
        HBox.setHgrow(navSpacer, Priority.ALWAYS);
        var navBar = new HBox(12, prevPageBtn, navSpacer, nextPageBtn);
        navBar.setAlignment(Pos.CENTER);
        navBar.setPadding(new Insets(6, 16, 6, 16));
        navBar.setStyle("-fx-background-color: #f4eee1; -fx-border-color: #dfd7c8; -fx-border-width: 1 0 0 0;");

        var webWrapper = new VBox(reader.getView(), navBar);

        var split = new SplitPane(webWrapper, buildPlayerPanel());
        split.setDividerPositions(0.72);
        SplitPane.setResizableWithParent(split.getItems().get(1), false);

        var root = new BorderPane();
        root.setTop(buildToolbar());
        root.setCenter(split);
        return root;
    }

    private ToolBar buildToolbar() {
        var libraryBtn = btn("\u2630 Library", this::openLibraryDialog);
        libraryBtn.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-background-color: #eae1cf; -fx-border-color: #c4b69c;");
        libraryBtn.setTooltip(new Tooltip("Open Library: saved books, reading places & audiobooks [Ctrl/Cmd+L]"));

        var speeds   = FXCollections.observableArrayList("0.75x", "1.0x", "1.25x", "1.5x", "2.0x");
        var speedBox = new ComboBox<>(speeds);
        speedBox.setValue("1.0x");
        speedBox.setOnAction(e ->
            player.setRate(Double.parseDouble(speedBox.getValue().replace("x", ""))));

        // Use standard unicode characters for clean typography instead of emojis
        var hlBtn = btn("\u270E Highlight", this::triggerHighlight);
        hlBtn.setStyle("-fx-background-color: #fff08a; -fx-border-color: #d4b106; -fx-font-size: 11px; -fx-font-weight: bold;");
        hlBtn.setTooltip(new Tooltip("Highlight selected text (persists across reloads) [Ctrl/Cmd+H]"));

        var ulBtn = btn("U\u0332 Underline", this::triggerUnderline);
        ulBtn.setStyle("-fx-background-color: #ffe8e8; -fx-border-color: #d46b08; -fx-font-size: 11px; -fx-font-weight: bold;");
        ulBtn.setTooltip(new Tooltip("Underline selected text (persists across reloads) [Ctrl/Cmd+U]"));

        var clrBtn = btn("\u2715 Clear", this::triggerClearHighlight);
        clrBtn.setStyle("-fx-font-size: 11px;");
        clrBtn.setTooltip(new Tooltip("Remove highlight under selection"));

        return new ToolBar(
            libraryBtn,
            new Separator(),
            btn("Open Book",   this::openEpub),
            btn("Open Audio",  this::openAudio),
            btn("Open Folder", this::openFolder),
            btn("Open Sync",   this::openSync),
            new Separator(),
            new Label("Speed:"), speedBox,
            new Separator(),
            hlBtn, ulBtn, clrBtn
        );
    }

    private VBox buildPlayerPanel() {
        // Book progress indicators
        chapterLabel = new Label("No book loaded");
        chapterLabel.setStyle("-fx-font-size:13px; -fx-font-weight:bold; -fx-text-fill: #1c1815;");
        chapterLabel.setWrapText(true);
        chapterLabel.setMaxWidth(250);
        chapterLabel.setAlignment(Pos.CENTER);
        chapterLabel.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);

        pageLabel = new Label("");
        pageLabel.setStyle("-fx-font-size:11px; -fx-text-fill:#665e55;");

        bookProgressBar = new ProgressBar(0.0);
        bookProgressBar.setMaxWidth(Double.MAX_VALUE);
        bookProgressBar.setPrefHeight(10);
        bookProgressBar.setStyle("-fx-accent: #c48b3f;");

        bookProgressLabel = new Label("Book Progress: 0%");
        bookProgressLabel.setStyle("-fx-font-size:11px; -fx-text-fill:#443f38; -fx-font-weight:bold;");

        var bookSection = new VBox(6, chapterLabel, pageLabel, bookProgressBar, bookProgressLabel);
        bookSection.setAlignment(Pos.CENTER);
        bookSection.setPadding(new Insets(10, 12, 12, 12));
        bookSection.setStyle("-fx-background-color: #faf6eb; -fx-border-color: #e5ded0; -fx-border-radius: 6; -fx-background-radius: 6;");

        // Audio controls
        trackLabel = new Label("");
        trackLabel.setStyle("-fx-font-size:11px; -fx-text-fill:#777;");

        playPauseBtn = btn("\u25B6", this::togglePlay);
        playPauseBtn.setStyle("-fx-font-size:20px; -fx-min-width: 48px; -fx-min-height: 40px;");

        skipPrevBtn = btn("\u23EE", this::handleSkipPrev);
        skipNextBtn = btn("\u23ED", this::handleSkipNext);
        skipPrevBtn.setStyle("-fx-font-size:16px;");
        skipNextBtn.setStyle("-fx-font-size:16px;");
        updateAudioControls();

        var controls = new HBox(12, skipPrevBtn, playPauseBtn, skipNextBtn);
        controls.setAlignment(Pos.CENTER);

        var keyHint = new Label("\u2190 \u2192 flip 2 pages  \u2022  Space play/pause");
        keyHint.setStyle("-fx-font-size:10px; -fx-text-fill:#888;");

        seekBar = new Slider(0, 100, 0);
        seekBar.setMaxWidth(Double.MAX_VALUE);
        seekBar.setOnMousePressed(e  -> seeking = true);
        seekBar.setOnMouseReleased(e -> {
            if (!totalDuration.isUnknown() && totalDuration.greaterThan(Duration.ZERO)) {
                player.seek(Duration.seconds(seekBar.getValue() / 100.0 * totalDuration.toSeconds()));
            }
            seeking = false;
            saveCurrentState();
        });

        timeCurrent  = new Label("00:00:00");
        timeDuration = new Label("00:00:00");
        timeCurrent.setStyle("-fx-font-family: monospace; -fx-font-size: 11px;");
        timeDuration.setStyle("-fx-font-family: monospace; -fx-font-size: 11px;");
        var spacer   = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        var timeRow  = new HBox(timeCurrent, spacer, timeDuration);

        var audioSection = new VBox(10, trackLabel, controls, keyHint, seekBar, timeRow);
        audioSection.setAlignment(Pos.CENTER);
        audioSection.setPadding(new Insets(12));
        audioSection.setStyle("-fx-background-color: #fafafa; -fx-border-color: #e0e0e0; -fx-border-radius: 6; -fx-background-radius: 6;");

        var pane = new VBox(16, bookSection, audioSection);
        pane.setPadding(new Insets(18));
        pane.setAlignment(Pos.TOP_CENTER);
        pane.setMinWidth(260);
        pane.setMaxWidth(300);
        return pane;
    }

    private void updateAudioControls() {
        if (!player.isLoaded()) {
            trackLabel.setText("No audio loaded");
            skipPrevBtn.setTooltip(new Tooltip("Previous chapter"));
            skipNextBtn.setTooltip(new Tooltip("Next chapter"));
        } else if (player.trackCount() > 1) {
            trackLabel.setText("Track " + (player.currentTrack() + 1) + " / " + player.trackCount());
            skipPrevBtn.setTooltip(new Tooltip("Skip to previous chapter file in playlist"));
            skipNextBtn.setTooltip(new Tooltip("Skip to next chapter file in playlist"));
        } else {
            trackLabel.setText("Single Audio File");
            skipPrevBtn.setTooltip(new Tooltip("Skip backward 2 minutes"));
            skipNextBtn.setTooltip(new Tooltip("Skip forward 2 minutes"));
        }
    }

    // ── action handlers ───────────────────────────────────────────────────────

    private void handleSkipNext() {
        if (player.isLoaded()) {
            player.skipNext();
            updateAudioControls();
            saveCurrentState();
        } else if (reader.chapterCount() > 0) {
            // Fallback when no audio is loaded: turn to next book chapter
            int nextCh = Math.min(reader.chapterCount() - 1, reader.currentChapter() + 1);
            reader.goTo(new BookPosition(nextCh, 0));
        }
    }

    private void handleSkipPrev() {
        if (player.isLoaded()) {
            player.skipPrev();
            updateAudioControls();
            saveCurrentState();
        } else if (reader.chapterCount() > 0) {
            // Fallback when no audio is loaded: turn to previous book chapter
            int prevCh = Math.max(0, reader.currentChapter() - 1);
            reader.goTo(new BookPosition(prevCh, 0));
        }
    }

    private void triggerHighlight() {
        Highlight h = reader.createHighlight("highlight", "#ffe58f");
        if (h != null) {
            allHighlights.add(h);
            saveCurrentState();
        }
    }

    private void triggerUnderline() {
        Highlight h = reader.createHighlight("underline", "#d46b08");
        if (h != null) {
            allHighlights.add(h);
            saveCurrentState();
        }
    }

    private void triggerClearHighlight() {
        String removedId = reader.removeActiveHighlight();
        if (removedId != null && !removedId.isBlank()) {
            for (String id : removedId.split(",")) {
                String clean = id.trim();
                allHighlights.removeIf(h -> h.id().equals(clean));
            }
            saveCurrentState();
        }
    }

    private void openEpub() {
        var fc = new FileChooser();
        fc.setTitle("Open Book (EPUB / PDF)");
        fc.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("Supported Books (*.epub, *.pdf)", "*.epub", "*.pdf"),
            new FileChooser.ExtensionFilter("EPUB Files (*.epub)", "*.epub"),
            new FileChooser.ExtensionFilter("PDF Files (*.pdf)", "*.pdf"),
            new FileChooser.ExtensionFilter("All Files (*.*)", "*.*")
        );
        var f = fc.showOpenDialog(null);
        if (f == null) return;
        try {
            saveCurrentState(); // Save previous book before opening new one
            if (player.isPlaying()) player.pause();

            reader.load(f.toPath());
            chapterLabel.setText(reader.chapterTitle(0));

            // Check if this book already has an entry in library
            String bookStr = f.toPath().toString();
            LibraryEntry existing = null;
            for (LibraryEntry e : library) {
                if (bookStr.equals(e.id()) || bookStr.equals(e.epubPath())) {
                    existing = e;
                    break;
                }
            }
            if (existing != null) {
                restoreBook(existing);
            } else {
                currentBookId = bookStr;
                saveCurrentState();
            }
        } catch (Exception ex) { alert("Cannot open book", ex.getMessage()); }
    }

    private void openAudio() {
        var fc = new FileChooser();
        fc.setTitle("Open Audio File");
        fc.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("Audio Files", "*.mp3", "*.m4a", "*.wav", "*.ogg"));
        var f = fc.showOpenDialog(null);
        if (f == null) return;
        player.load(f.toPath(), dur -> Platform.runLater(() -> {
            totalDuration = dur;
            timeDuration.setText(fmt(dur));
            updateAudioControls();
            saveCurrentState();
        }));
    }

    private void openFolder() {
        var dc = new DirectoryChooser();
        dc.setTitle("Open Audiobook Folder");
        var dir = dc.showDialog(null);
        if (dir == null) return;
        try {
            player.loadFolder(dir.toPath(), dur -> Platform.runLater(() -> {
                totalDuration = dur;
                timeDuration.setText(fmt(dur));
                updateAudioControls();
                saveCurrentState();
            }));
        } catch (Exception ex) { alert("Cannot load folder", ex.getMessage()); }
    }

    private void openSync() {
        var fc = new FileChooser();
        fc.setTitle("Open Sync File");
        fc.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("Sync Files", "*.sync", "*.txt"));
        var f = fc.showOpenDialog(null);
        if (f == null) return;
        try {
            sync.load(f.toPath());
            currentSyncPath = f.toPath();
            saveCurrentState();
        } catch (Exception ex) { alert("Cannot load sync file", ex.getMessage()); }
    }

    private void togglePlay() {
        if (!player.isLoaded()) return;
        if (player.isPlaying()) {
            player.pause();
            playPauseBtn.setText("\u25B6");
        } else {
            player.play();
            playPauseBtn.setText("\u23F8");
        }
        saveCurrentState();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static Button btn(String text, Runnable action) {
        var b = new Button(text);
        b.setOnAction(e -> action.run());
        return b;
    }

    private static String fmt(Duration d) {
        if (d == null || d.isUnknown()) return "00:00:00";
        long s = (long) d.toSeconds();
        return String.format("%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
    }

    private void alert(String title, String msg) {
        var a = new Alert(Alert.AlertType.ERROR);
        a.setTitle(title);
        a.setContentText(msg != null ? msg : "Unknown error");
        a.showAndWait();
    }

    public static void main(String[] args) { launch(args); }
}
