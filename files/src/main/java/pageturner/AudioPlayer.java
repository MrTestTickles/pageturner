package pageturner;

import javafx.scene.media.*;
import javafx.util.Duration;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

interface AudioPlayer {
    void     load(Path audio, Consumer<Duration> onDurationKnown);
    void     loadFolder(Path dir, Consumer<Duration> onDurationKnown) throws IOException;
    void     play();
    void     pause();
    void     seek(Duration pos);
    void     skipNext();
    void     skipPrev();
    void     jumpTrack(int index, Duration initialSeek);
    Duration position();
    void     setRate(double rate);
    void     onProgress(Consumer<Duration> listener);
    void     onTrackChange(Consumer<Integer> listener);
    boolean  isPlaying();
    boolean  isLoaded();
    int      currentTrack();
    int      trackCount();
    Path     currentPath();
    boolean  isFolder();
}

class JavaFXAudioPlayer implements AudioPlayer {

    private MediaPlayer        mp;
    private Consumer<Duration> progressListener;
    private Consumer<Integer>  trackChangeListener;
    private Consumer<Duration> durationCallback;
    private boolean            playing    = false;
    private List<Path>         playlist   = new ArrayList<>();
    private Path               sourcePath = null;
    private boolean            folderMode = false;
    private int                trackIndex = 0;
    private double             rate       = 1.0;
    private Duration           pendingSeek = null;

    @Override
    public void load(Path audio, Consumer<Duration> onDurationKnown) {
        sourcePath = audio;
        folderMode = false;
        playlist   = List.of(audio);
        trackIndex = 0;
        loadTrack(0, onDurationKnown, Duration.ZERO);
    }

    @Override
    public void loadFolder(Path dir, Consumer<Duration> onDurationKnown) throws IOException {
        sourcePath = dir;
        folderMode = true;
        // Sort by filename so chapters play in order (01_chapter.mp3, 02_chapter.mp3, ...)
        playlist = Files.list(dir)
            .filter(p -> p.toString().toLowerCase().matches(".*\\.(mp3|m4a|wav|ogg)$"))
            .sorted(Comparator.comparing(p -> p.getFileName().toString()))
            .collect(Collectors.toList());
        if (playlist.isEmpty()) throw new IOException("No audio files found in folder");
        trackIndex = 0;
        loadTrack(0, onDurationKnown, Duration.ZERO);
    }

    @Override
    public void jumpTrack(int index, Duration initialSeek) {
        if (index >= 0 && index < playlist.size()) {
            boolean wasPlaying = playing;
            loadTrack(index, durationCallback, initialSeek);
            if (wasPlaying) play();
            if (trackChangeListener != null) trackChangeListener.accept(trackIndex);
        }
    }

    @Override
    public void skipNext() {
        if (!isLoaded()) return;
        if (playlist.size() > 1) {
            // Playlist: skip the file in the playlist
            if (trackIndex + 1 < playlist.size()) {
                boolean wasPlaying = playing;
                loadTrack(trackIndex + 1, durationCallback, Duration.ZERO);
                if (wasPlaying) play();
                if (trackChangeListener != null) trackChangeListener.accept(trackIndex);
            }
        } else {
            // Single file: skip forward uniform 2 minutes
            Duration target = position().add(Duration.minutes(2));
            if (mp != null && mp.getTotalDuration() != null && target.greaterThan(mp.getTotalDuration())) {
                target = mp.getTotalDuration();
            }
            seek(target);
        }
    }

    @Override
    public void skipPrev() {
        if (!isLoaded()) return;
        if (playlist.size() > 1) {
            // Playlist: if >5s into track, rewind to start of track; else go to previous file
            if (position().toSeconds() > 5.0) {
                seek(Duration.ZERO);
            } else if (trackIndex > 0) {
                boolean wasPlaying = playing;
                loadTrack(trackIndex - 1, durationCallback, Duration.ZERO);
                if (wasPlaying) play();
                if (trackChangeListener != null) trackChangeListener.accept(trackIndex);
            } else {
                seek(Duration.ZERO);
            }
        } else {
            // Single file: skip backward uniform 2 minutes
            Duration target = position().subtract(Duration.minutes(2));
            if (target.lessThan(Duration.ZERO)) target = Duration.ZERO;
            seek(target);
        }
    }

    private void loadTrack(int index, Consumer<Duration> onDurationKnown, Duration initialSeek) {
        if (mp != null) { mp.dispose(); mp = null; }
        playing    = false;
        trackIndex = index;
        durationCallback = onDurationKnown;
        pendingSeek = initialSeek;

        var media = new Media(playlist.get(index).toUri().toString());
        mp = new MediaPlayer(media);
        mp.setRate(rate);

        mp.setOnReady(() -> {
            if (durationCallback != null) durationCallback.accept(mp.getTotalDuration());
            if (pendingSeek != null && pendingSeek.greaterThan(Duration.ZERO)) {
                mp.seek(pendingSeek);
                pendingSeek = null;
            }
        });

        mp.currentTimeProperty().addListener((obs, old, now) -> {
            if (progressListener != null) progressListener.accept(now);
        });

        mp.setOnEndOfMedia(() -> {
            playing = false;
            if (trackIndex + 1 < playlist.size()) {
                int next = trackIndex + 1;
                loadTrack(next, durationCallback, Duration.ZERO);
                mp.play();
                playing = true;
                if (trackChangeListener != null) trackChangeListener.accept(next);
            }
        });
    }

    @Override public void play()                             { if (mp != null) { mp.play();  playing = true;  } }
    @Override public void pause()                            { if (mp != null) { mp.pause(); playing = false; } }
    @Override public void seek(Duration pos)                 { if (mp != null)   mp.seek(pos); }
    @Override public void setRate(double r)                  { rate = r; if (mp != null) mp.setRate(r); }
    @Override public boolean isPlaying()                     { return playing; }
    @Override public boolean isLoaded()                      { return mp != null; }
    @Override public int currentTrack()                      { return trackIndex; }
    @Override public int trackCount()                        { return playlist.size(); }
    @Override public Duration position()                     { return mp != null ? mp.getCurrentTime() : Duration.ZERO; }
    @Override public void onProgress(Consumer<Duration> l)   { this.progressListener    = l; }
    @Override public void onTrackChange(Consumer<Integer> l) { this.trackChangeListener = l; }
    @Override public Path currentPath()                      { return sourcePath; }
    @Override public boolean isFolder()                      { return folderMode; }
}
