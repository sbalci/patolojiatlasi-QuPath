package com.patolojiatlasi.qupath.quiz;

import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Separator;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.images.ImageData;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.interfaces.ROI;

/**
 * Free-browse, hidden-answer self-study window: load a quiz pack, pick one of its slides, and see
 * every stop that belongs to it drawn at once as numbered, clickable regions on the slide — click a
 * region (or its entry in the list) to select it, then reveal its answer on demand with
 * <strong>"Cevabı göster"</strong>. Unlike {@link QuizRunnerWindow}'s guided step-by-step play, there
 * is no forced order and nothing is scored or recorded; a learner can jump straight to any stop and
 * compare its region against its neighbours before checking the answer.
 * <p>
 * Inspired by QuPath Edu's hidden-answer annotations — that project's <em>best-rated</em> feature
 * (47.5/50 usability score) and the one its survey respondents most often preferred kept concealed
 * until requested (86.7 %) rather than always visible (Yli-Hallila et al., <em>Journal of
 * Anatomy</em> 2025;246(5):846-856, doi:10.1111/joa.14172). This is a clean-room reimplementation:
 * QuPath Edu's extension/server repositories carry no license, so no code from that project is used
 * here — only the published feature idea and survey numbers, cited above.
 * <p>
 * Answers are never persisted or pre-shown: re-selecting a stop (from either the list or a slide
 * click) always starts from the hidden state again (see {@link #selectStop(int, boolean)}), and this window
 * carries no learner-attempt/scoring state at all (contrast {@link QuizRunnerWindow}'s
 * {@code annotationBaseline}/{@link QuizScoring} machinery, which this window has no need for since
 * nothing is drawn or answered here — only browsed).
 * <p>
 * Mirrors {@code com.patolojiatlasi.qupath.AtlasBrowser}'s single-window {@code show(...)} /
 * focus-if-open pattern. Slide loading replicates the <em>minimal</em> version of
 * {@link QuizRunnerWindow#applySlide}'s {@code loadToken}/{@code stillWanted}/{@code opening}
 * discipline and viewer-replace confirmation (the runner itself is not modified or reused). The
 * per-slide region overlay ({@link QuizRegionsOverlay}) and its {@code MOUSE_CLICKED} event filter
 * are attached to -- and detached from -- one pinned {@link QuPathViewer} reference at a time,
 * mirroring {@code com.patolojiatlasi.qupath.focus.FocusHeatmap}'s mouse-filter attach/detach
 * pattern (never touching {@code qupath.getViewer()} live at detach time, so a later viewer swap or
 * this window's own close can't orphan a filter/overlay on the wrong viewer).
 */
public class QuizBrowseWindow {

    private static final Logger logger = LoggerFactory.getLogger(QuizBrowseWindow.class);

    private static Stage stage;

    private final QuPathGUI qupath;

    private AtlasQuiz quiz;

    // --- top bar -------------------------------------------------------
    private final Button loadBtn = new Button("Paket yükle…");
    private final Label titleLabel = new Label("Paket yüklenmedi");
    private final ComboBox<SlideEntry> slideCombo = new ComboBox<>();

    // --- stop list -------------------------------------------------------
    private final ObservableList<QuizQuestion> stopItems = FXCollections.observableArrayList();
    private final ListView<QuizQuestion> stopsList = new ListView<>(stopItems);

    // --- detail pane -------------------------------------------------------
    private final Label detailPromptLabel = new Label();
    private final VBox optionsBox = new VBox(2);
    private final Button revealBtn = new Button("Cevabı göster");
    private final VBox revealArea = new VBox(6);
    private final Label revealAnswerLabel = new Label();
    private final Label revealExplanationLabel = new Label();

    // The stops of the currently-open slide (index-aligned with stopItems) and their parsed
    // regions (also index-aligned; a null entry means that stop has no geometry -- see
    // stopRegionRoi). Rebuilt in afterSlideReady on every slide change/load.
    private List<QuizQuestion> stops = new ArrayList<>();
    private List<ROI> regions = new ArrayList<>();

    // The slide URL that stops/regions/overlay were built for, set alongside them in
    // afterSlideReady. Read by clickFilter and selectStop as a staleness guard: if another window
    // (e.g. QuizRunnerWindow, open on the same viewer) swaps the slide underneath this one without
    // this window knowing, a click landing inside a now-meaningless region must not act on it --
    // see the guard in clickFilter/selectStop below.
    private String regionsSlideUrl;

    // The stop currently shown in the detail pane, or null if none is selected. Set (and its
    // answer re-hidden) in selectStop; read by revealAnswer.
    private QuizQuestion selectedQuestion;

    // The QuizRegionsOverlay currently on screen (one per open slide) and the viewer it was added
    // to, pinned at attach time -- read/removed only via this pinned reference (never
    // qupath.getViewer() live), mirroring QuizRunnerWindow's revealOverlay/revealOverlayViewer and
    // FocusHeatmap's currentOverlay/currentViewer. Both are reassigned together, only in
    // attachOverlayAndFilter/detachOverlayAndFilter.
    private QuizRegionsOverlay overlay;
    private QuPathViewer attachedViewer;

    // Guards overlapping slide opens: set true just before QuizSlide.openSlideAsync is called,
    // cleared in its onDone/onError (both guaranteed to run on the FX thread) -- same shape as
    // QuizRunnerWindow's `opening` guard, minimal version (only loadBtn/slideCombo to disable here,
    // no Önceki/Göster/Sonraki).
    private boolean opening = false;

    // Set true once the learner has confirmed replacing whatever was in the active viewer with a
    // quiz slide -- checked/set only in openSlide, before the FIRST QuizSlide.openSlideAsync call
    // of this window's lifetime; every later swap only ever replaces a previous quiz slide. FX
    // thread only. Same guard shape as QuizRunnerWindow.confirmedViewerReplace.
    private boolean confirmedViewerReplace = false;

    // Incremented at the start of every openSlide call; the token captured at that point is passed
    // to QuizSlide.openSlideAsync as its stillWanted check, so a load superseded by a later slide
    // selection becomes a no-op instead of clobbering newer state. FX thread only.
    private int loadToken = 0;

    // Set true from the stage's setOnHidden handler; combined into the same stillWanted check so a
    // load still in flight when the window closes can't call viewer.setImageData on a window
    // instance no longer showing anything. FX thread only.
    private boolean closed = false;

    // Set transiently by clickFilter, around its stopsList.getSelectionModel().select(idx) call in
    // the new-stop branch, so the selectedIndexProperty listener below can tell a click-originated
    // selection change from a genuine list interaction (row click / keyboard) and never fly the
    // viewer for the former. select() fires the listener synchronously on the FX thread, so a plain
    // try/finally around the call is enough -- no re-entrancy to guard against. FX thread only;
    // default false (the common/list-driven case flies, as before).
    private boolean suppressFlyOnSelect = false;

    /** One distinct slide within a loaded pack: its URL plus the display title shown in the combo
     *  box (its {@code toString()} -- JavaFX's default ComboBox cell renders an item via
     *  {@code toString()} when no custom converter/cell-factory is set). */
    private record SlideEntry(String url, String title) {
        @Override
        public String toString() {
            return title;
        }
    }

    /**
     * A single {@code MOUSE_CLICKED} event <em>filter</em> (never a handler -- filters run ahead of
     * the viewer's own pan/zoom/tool handlers and, critically, never consume the event, so normal
     * slide navigation is completely unaffected), attached to whichever viewer is currently pinned
     * in {@link #attachedViewer}. Mirrors {@code FocusHeatmap.mouseMovedHandler}'s filter pattern:
     * only acts on a genuine click ({@link MouseEvent#isStillSincePress()} -- excludes a
     * click-terminating a pan/drag), converts the event's component-space point to image-space via
     * {@link QuPathViewer#componentPointToImagePoint(double, double, Point2D, boolean)}, and hands
     * it to {@link #pickStop(List, double, double)} against the live {@link #regions} field (read
     * fresh on every click, so it always reflects whichever slide is currently open). A slide click
     * never flies the viewer, on either of its two paths: a same-stop re-click calls
     * {@link #selectStop(int, boolean)} directly with {@code flyTo=false}; a click on a different
     * stop selects it in {@link #stopsList} with {@link #suppressFlyOnSelect} held {@code true}
     * around the call, so the selection-index listener it fires computes {@code flyTo=false} too
     * (see that listener and {@link #suppressFlyOnSelect} for why). Only genuine list interaction --
     * a row click or keyboard navigation on {@link #stopsList} itself -- flies the viewer.
     */
    private final EventHandler<MouseEvent> clickFilter = e -> {
        if (!e.isStillSincePress())
            return;
        QuPathViewer v = attachedViewer;
        if (v == null)
            return;
        // Stale-region guard: if another window (e.g. QuizRunnerWindow, open on the same shared
        // viewer) swapped the slide underneath this one without this window's own openSlide/
        // afterSlideReady running, `regions` no longer describes what's on screen -- detach and
        // bail rather than hit-test/select against geometry for a slide that's no longer open.
        // (QuizRunnerWindow's symmetric exposure -- another window swapping the slide out from
        // under IT -- is a pre-existing gap, deliberately not addressed here.)
        if (!QuizSlide.currentSlideUrl(v).equals(regionsSlideUrl)) {
            detachOverlayAndFilter();
            return;
        }
        try {
            Point2D p = v.componentPointToImagePoint(e.getX(), e.getY(), null, true);
            int idx = pickStop(regions, p.getX(), p.getY());
            if (idx < 0)
                return;
            // Selecting an already-selected index fires no change event on the selection model
            // (select() is a no-op when the value doesn't change) -- so a second click on the same
            // region would otherwise silently do nothing. Route that case through selectStop
            // directly so "re-selecting re-hides (no persistence)" holds even for a same-stop
            // re-click, not only for switching to a different stop. flyTo=false here for the same
            // reason as the primary click path below.
            if (idx == stopsList.getSelectionModel().getSelectedIndex())
                selectStop(idx, false);
            else {
                // select() fires the selectedIndexProperty listener synchronously (still inside this
                // call, on this FX-thread event), so suppressFlyOnSelect only needs to be true for
                // the duration of the call itself -- the finally resets it immediately afterwards.
                // This tells that listener the change is click-originated, so it must not fly.
                suppressFlyOnSelect = true;
                try {
                    stopsList.getSelectionModel().select(idx);
                } finally {
                    suppressFlyOnSelect = false;
                }
            }
        } catch (Exception ex) {
            logger.debug("Quiz browse click handling failed: {}", ex.getMessage());
        }
        // Intentionally never consumed -- a click still reaches QuPath's own viewer handlers
        // underneath (pan/zoom/tool), exactly like FocusHeatmap's mouse filters.
    };

    private QuizBrowseWindow(QuPathGUI qupath) {
        this.qupath = qupath;
    }

    /** Show (or focus) the single browse window. */
    public static void show(QuPathGUI qupath) {
        if (stage != null) {
            stage.show();
            stage.toFront();
            return;
        }
        QuizBrowseWindow browser = new QuizBrowseWindow(qupath);
        stage = browser.buildStage();
        stage.show();
    }

    private Stage buildStage() {
        Stage s = new Stage();
        s.setTitle("Serbest inceleme (gizli cevaplar)");

        loadBtn.setOnAction(e -> promptLoad());
        slideCombo.setPromptText("Slayt seçin");
        slideCombo.setPrefWidth(260);
        slideCombo.getSelectionModel().selectedItemProperty().addListener((obs, was, now) -> {
            if (now != null)
                openSlide(now.url());
        });
        HBox top = new HBox(10, loadBtn, titleLabel, slideCombo);
        top.setPadding(new Insets(8));
        top.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(titleLabel, Priority.ALWAYS);

        stopsList.setPlaceholder(new Label("Slayt seçildiğinde durakları burada göreceksiniz"));
        stopsList.setPrefWidth(260);
        stopsList.setCellFactory(lv -> new ListCell<>() {
            private final Label label = new Label();
            {
                label.setWrapText(true);
                label.prefWidthProperty().bind(lv.widthProperty().subtract(28));
            }

            @Override
            protected void updateItem(QuizQuestion q, boolean empty) {
                super.updateItem(q, empty);
                if (empty || q == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    String prompt = q.getPrompt() == null ? "" : q.getPrompt();
                    label.setText((getIndex() + 1) + ". " + prompt);
                    setGraphic(label);
                }
            }

            {
                // Same "re-selecting re-hides" concern as clickFilter's region-click handling:
                // clicking a row that is ALREADY selected changes nothing in the selection model,
                // so the selectedIndexProperty listener never fires and the reveal area would stay
                // whatever it was. This cell-level handler runs after the ListView's own
                // (press-driven) selection update, so for an actual selection change it just
                // redundantly (harmlessly) re-runs selectStop with the same index the listener
                // already applied; for a same-row re-click it is the only thing that re-hides.
                setOnMouseClicked(ev -> {
                    if (!isEmpty())
                        selectStop(getIndex(), true);
                });
            }
        });
        // flyTo is !suppressFlyOnSelect: clickFilter's new-stop branch holds that flag true around
        // its own select(idx) call, so a slide click landing here computes flyTo=false; every other
        // trigger of this listener (row click, keyboard navigation, or a programmatic select/clear
        // elsewhere in this class) leaves the flag false and flies as before.
        stopsList.getSelectionModel().selectedIndexProperty().addListener(
                (obs, was, now) -> selectStop(now == null ? -1 : now.intValue(), !suppressFlyOnSelect));

        detailPromptLabel.setWrapText(true);
        detailPromptLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");
        optionsBox.setPadding(new Insets(4, 0, 4, 0));

        revealBtn.setOnAction(e -> revealAnswer());
        revealBtn.setDisable(true);

        revealAnswerLabel.setWrapText(true);
        revealExplanationLabel.setWrapText(true);
        revealExplanationLabel.setStyle("-fx-font-style: italic;");
        revealArea.getChildren().addAll(new Separator(), revealAnswerLabel, revealExplanationLabel);
        revealArea.setPadding(new Insets(8, 0, 0, 0));
        setRevealVisible(false);

        VBox detail = new VBox(10, detailPromptLabel, optionsBox, revealBtn, revealArea);
        detail.setPadding(new Insets(12));
        detail.setPrefWidth(320);

        BorderPane root = new BorderPane();
        root.setTop(top);
        root.setLeft(stopsList);
        root.setCenter(detail);

        s.setScene(new Scene(root, 760, 520));
        s.setOnHidden(e -> {
            // Same "leave" discipline as QuizRunnerWindow's setOnHidden: whatever overlay/filter
            // this instance attached is removed from the pinned viewer it was attached to, so
            // nothing leaks into the shared viewer once this window instance is discarded.
            detachOverlayAndFilter();
            closed = true;
            stage = null;
        });
        return s;
    }

    // --- pack / slide loading -------------------------------------------------------

    /** "Paket yükle…": pick a *.json quiz pack and populate the slide combo from it. On failure,
     *  keep the current state (mirrors {@link QuizRunnerWindow#promptLoad()}). */
    private void promptLoad() {
        if (opening)
            return; // extra guard beyond the disabled button
        FileChooser fc = new FileChooser();
        fc.setTitle("Paket yükle…");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Quiz pack (*.json)", "*.json"));
        File file = fc.showOpenDialog(stage);
        if (file == null)
            return;
        AtlasQuiz loaded;
        try {
            loaded = AtlasQuizIO.read(file);
        } catch (IOException ex) {
            logger.warn("Failed to load quiz pack {}: {}", file, ex.getMessage());
            Alert alert = new Alert(Alert.AlertType.ERROR,
                    "Sınav dosyası okunamadı:\n\n" + ex.getMessage());
            if (stage != null)
                alert.initOwner(stage);
            alert.showAndWait();
            return;
        }
        detachOverlayAndFilter(); // abandon whatever slide/overlay the previous pack left behind
        this.quiz = loaded;
        titleLabel.setText(loaded.getTitle().isBlank() ? file.getName() : loaded.getTitle());
        populateSlideCombo(loaded);
    }

    /** Populate {@link #slideCombo} with one entry per distinct {@code slideUrl} in {@code loaded},
     *  in first-seen order, labelled by that stop's {@code slideTitle} (falling back to the URL if
     *  blank). Selecting the first entry (if any) triggers {@link #openSlide(String)} via the
     *  combo's selection listener. */
    private void populateSlideCombo(AtlasQuiz loaded) {
        Map<String, String> distinct = new LinkedHashMap<>();
        for (QuizQuestion q : loaded.getQuestions()) {
            String url = q.getSlideUrl();
            if (url == null || url.isBlank())
                continue;
            String title = q.getSlideTitle();
            distinct.putIfAbsent(url, (title == null || title.isBlank()) ? url : title);
        }
        // clearSelection() first, not just relying on clear()'s own selection reset: setAll/clear
        // don't reliably guarantee the selection model lands on -1 (see afterSlideReady's identical
        // comment) -- explicit clearSelection() removes the dependency either way.
        stopsList.getSelectionModel().clearSelection();
        stopItems.clear();
        stops = new ArrayList<>();
        regions = new ArrayList<>();
        regionsSlideUrl = null;
        selectStop(-1, true);
        slideCombo.getItems().clear();
        for (Map.Entry<String, String> entry : distinct.entrySet())
            slideCombo.getItems().add(new SlideEntry(entry.getKey(), entry.getValue()));
        if (!slideCombo.getItems().isEmpty())
            slideCombo.getSelectionModel().select(0);
    }

    /**
     * Open {@code url} unless it's already the slide shown in the viewer, replicating the
     * <em>minimal</em> version of {@link QuizRunnerWindow#applySlide}'s guard discipline: a
     * {@code loadToken}/{@code stillWanted} check so a superseded load becomes a no-op, and a
     * one-time viewer-replace confirmation the first time this window swaps out whatever was
     * already open. The runner itself is not modified or reused.
     */
    private void openSlide(String url) {
        final int myToken = ++loadToken;

        String current = QuizSlide.currentSlideUrl(qupath.getViewer());
        if (url != null && url.equals(current)) {
            afterSlideReady(url);
            return;
        }

        if (!confirmedViewerReplace) {
            QuPathViewer v = qupath.getViewer();
            ImageData<BufferedImage> open = (v == null) ? null : v.getImageData();
            boolean risky = open != null && (isChangedSafe(open) || qupath.getProject() != null);
            if (risky) {
                Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                        "Sınav slaytları görüntüleyicideki görüntünün yerini alır; kaydedilmemiş "
                        + "değişiklikler kaybolabilir. Devam edilsin mi?", ButtonType.OK, ButtonType.CANCEL);
                a.setHeaderText(null);
                if (stage != null)
                    a.initOwner(stage);
                Optional<ButtonType> r = a.showAndWait();
                if (r.isEmpty() || r.get() != ButtonType.OK)
                    return; // abort this slide load; leave the viewer as-is
            }
            confirmedViewerReplace = true;
        }

        opening = true;
        setControlsDisabled(true);
        QuizSlide.openSlideAsync(qupath, url,
                () -> {
                    opening = false;
                    setControlsDisabled(false);
                    afterSlideReady(url);
                },
                ex -> {
                    opening = false;
                    setControlsDisabled(false);
                    Alert alert = new Alert(Alert.AlertType.ERROR,
                            "Slayt açılamadı:\n\n" + ex.getMessage());
                    if (stage != null)
                        alert.initOwner(stage);
                    alert.showAndWait();
                },
                () -> !closed && myToken == loadToken);
    }

    /** {@link ImageData#isChanged()}, swallowing any exception to {@code false} -- same fail-open
     *  rationale as {@link QuizRunnerWindow#isChangedSafe(ImageData)} (duplicated here rather than
     *  shared, per the "minimal guard, don't refactor the runner" constraint). */
    private static boolean isChangedSafe(ImageData<BufferedImage> d) {
        try {
            return d.isChanged();
        } catch (Throwable t) {
            return false;
        }
    }

    private void setControlsDisabled(boolean disabled) {
        loadBtn.setDisable(disabled);
        slideCombo.setDisable(disabled);
    }

    /**
     * Called once {@code url} is confirmed showing in the viewer: detaches whatever overlay/filter
     * was attached for the previous slide (slide-change cleanup -- see class Javadoc), rebuilds
     * {@link #stops}/{@link #regions} from every question in {@link #quiz} whose {@code slideUrl}
     * matches, populates {@link #stopsList}, clears the detail pane, and attaches a fresh
     * {@link QuizRegionsOverlay} + click filter to the now-active viewer.
     */
    private void afterSlideReady(String url) {
        detachOverlayAndFilter();
        stops = (quiz == null) ? List.of() : quiz.getQuestions().stream()
                .filter(q -> url != null && url.equals(q.getSlideUrl()))
                .collect(Collectors.toList());
        regions = stops.stream().map(QuizBrowseWindow::stopRegionRoi).collect(Collectors.toList());
        regionsSlideUrl = url;
        // clearSelection() before setAll: JavaFX's selection model does not reliably reset to -1
        // on an items-list replace (an index could survive/clamp onto the new, shorter/reordered
        // list), which would leave a stale row highlighted while the detail pane below has already
        // been blanked by selectStop(-1) -- and, since select() on an unchanged index is a no-op,
        // that stale row's own region click would then silently do nothing.
        stopsList.getSelectionModel().clearSelection();
        stopItems.setAll(stops);
        selectStop(-1, true);

        QuPathViewer viewer = qupath.getViewer();
        if (viewer == null)
            return;
        QuizRegionsOverlay ov = new QuizRegionsOverlay(viewer.getOverlayOptions(), regions,
                viewer.getImageData());   // pinned: stops painting if the slide is swapped underneath
        viewer.getCustomOverlayLayers().add(ov);
        viewer.getView().addEventFilter(MouseEvent.MOUSE_CLICKED, clickFilter);
        overlay = ov;
        attachedViewer = viewer;
        viewer.repaint();
    }

    /** Remove {@link #overlay} and {@link #clickFilter} from {@link #attachedViewer} (the pinned
     *  viewer they were attached to -- never {@code qupath.getViewer()} live), if anything is
     *  currently attached. Called before attaching a fresh overlay/filter on slide change, and from
     *  the stage's {@code setOnHidden}. Best-effort: any failure is logged and swallowed, mirroring
     *  {@code QuizRunnerWindow.removeRevealOverlay}/{@code FocusHeatmap.attachMouseTracking}. */
    private void detachOverlayAndFilter() {
        if (attachedViewer != null) {
            try {
                if (overlay != null)
                    attachedViewer.getCustomOverlayLayers().remove(overlay);
                attachedViewer.getView().removeEventFilter(MouseEvent.MOUSE_CLICKED, clickFilter);
                attachedViewer.repaint();
            } catch (Exception ex) {
                logger.debug("Could not detach quiz browse overlay/filter: {}", ex.getMessage());
            }
        }
        overlay = null;
        attachedViewer = null;
    }

    // --- stop selection / reveal -------------------------------------------------------

    /**
     * Select stop {@code idx} (0-based into {@link #stops}, or any out-of-range value -- including
     * {@code -1} -- to select none): always re-hides the reveal area first, so re-selecting any stop
     * (including the one already selected) starts from the hidden state again -- no persistence.
     * Updates the detail pane (prompt + MCQ options, if any) and the region overlay's highlighted
     * index; only when {@code flyTo} is {@code true} and the stop carries a
     * {@link QuizQuestion.Viewport} does it also fly the pinned viewer to it. This is the single
     * place both a {@link #stopsList} click/selection and a {@link #clickFilter} slide click
     * converge, and the two are kept strictly apart: every {@link #clickFilter} path passes (or
     * causes {@link #suppressFlyOnSelect} to force) {@code flyTo=false} -- a learner who clicked a
     * region already visible on screen must not have the viewer recentred under them -- while
     * genuine {@link #stopsList} interaction (a row click or keyboard navigation) still passes
     * {@code true}, the existing recentre-on-select UX. With {@link QuizRunnerWindow} open on the
     * same shared viewer, a slide click here (e.g. while placing an annotation vertex for a runner
     * question) was flying the viewer mid-draw and corrupting the learner's in-progress answer
     * there -- the reason no click-originated path may ever fly.
     */
    private void selectStop(int idx, boolean flyTo) {
        // Stale-region guard (see clickFilter's identical check): if attachedViewer's slide no
        // longer matches what regions/stops were built for, another window swapped the slide out
        // from under this one -- detach and leave the browse UI as-is rather than act on geometry
        // for a slide that isn't open any more.
        if (attachedViewer != null && !QuizSlide.currentSlideUrl(attachedViewer).equals(regionsSlideUrl)) {
            detachOverlayAndFilter();
            return;
        }

        setRevealVisible(false);
        revealAnswerLabel.setText("");
        revealExplanationLabel.setText("");
        optionsBox.getChildren().clear();
        selectedQuestion = null;
        revealBtn.setDisable(true);

        boolean inRange = idx >= 0 && idx < stops.size();
        QuizQuestion q = inRange ? stops.get(idx) : null;

        detailPromptLabel.setText(q == null ? "" : (q.getPrompt() == null ? "" : q.getPrompt()));
        if (q != null && q.getType() == QuizType.MCQ) {
            List<String> options = q.getOptions() == null ? List.of() : q.getOptions();
            for (String option : options) {
                Label optionLabel = new Label("•  " + option);
                optionLabel.setWrapText(true);
                optionsBox.getChildren().add(optionLabel);
            }
        }
        if (q != null) {
            selectedQuestion = q;
            revealBtn.setDisable(false);
        }

        if (overlay != null) {
            overlay.setSelectedIndex(inRange ? idx : -1);
            if (attachedViewer != null) {
                attachedViewer.repaint();
                if (flyTo && q != null) {
                    QuizQuestion.Viewport vp = q.getViewport();
                    if (vp != null)
                        attachedViewer.setDownsampleFactor(vp.downsample, vp.centerX, vp.centerY);
                }
            }
        }
    }

    /** "Cevabı göster": reveal the answer/explanation area for the currently-selected stop. No-op
     *  if nothing is selected. Idempotent -- clicking it again just re-renders the same text. */
    private void revealAnswer() {
        QuizQuestion q = selectedQuestion;
        if (q == null)
            return;
        switch (q.getType()) {
            case MCQ -> {
                List<String> options = q.getOptions() == null ? List.of() : q.getOptions();
                Integer correct = q.getCorrectIndex();
                String correctText = (correct != null && correct >= 0 && correct < options.size())
                        ? options.get(correct) : "?";
                revealAnswerLabel.setText("Doğru cevap: " + correctText);
            }
            case FREETEXT -> {
                String model = q.getModelAnswer() == null ? "" : q.getModelAnswer();
                revealAnswerLabel.setText("Model cevap: " + model);
            }
            case ANNOTATION, NAVIGATION -> revealAnswerLabel.setText("Referans/hedef bölge gösteriliyor.");
            case NARRATION -> revealAnswerLabel.setText(""); // caption-only stop; explanation still shown below
        }
        revealExplanationLabel.setText(q.getExplanation() == null ? "" : q.getExplanation());
        setRevealVisible(true);
    }

    private void setRevealVisible(boolean visible) {
        revealArea.setVisible(visible);
        revealArea.setManaged(visible);
    }

    // --- pure helpers (unit-tested; no UI/QuPathGUI dependency) -------------------------------------------------------

    /**
     * The stop region ROI to draw/click for {@code q}: its highlight geometry if present, else its
     * ANNOTATION reference geometry, else its NAVIGATION target geometry, else {@code null} (a
     * stop with no geometry -- list-only, per class Javadoc). Parsed defensively via
     * {@link QuizGeometry#fromGeoJson} -- any parse failure (malformed GeoJSON in a hand-edited
     * pack) is logged and treated the same as "no geometry" rather than thrown, since this runs for
     * every stop of a slide as soon as it opens.
     *
     * @param q the question/stop to resolve a region for; {@code null} returns {@code null}
     */
    static ROI stopRegionRoi(QuizQuestion q) {
        if (q == null)
            return null;
        String geoJson = q.getHighlightGeoJson();
        if (geoJson == null || geoJson.isBlank())
            geoJson = q.getReferenceGeometryGeoJson();
        if (geoJson == null || geoJson.isBlank())
            geoJson = q.getTargetGeometryGeoJson();
        if (geoJson == null || geoJson.isBlank())
            return null;
        try {
            return QuizGeometry.fromGeoJson(geoJson, ImagePlane.getDefaultPlane());
        } catch (Exception ex) {
            logger.debug("Could not parse quiz browse stop geometry: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * The index into {@code rois} of the <em>smallest-area</em> ROI that contains image point
     * {@code (x, y)}, or {@code -1} if none does. Entries in {@code rois} may be {@code null} (a
     * stop with no geometry) and are skipped, as is any ROI whose {@code contains}/{@code getArea}
     * call throws. "Smallest area wins" lets a small region nested inside a larger one still be
     * clickable on its own, rather than the larger region always winning by list order.
     */
    static int pickStop(List<ROI> rois, double x, double y) {
        int best = -1;
        double bestArea = Double.MAX_VALUE;
        for (int i = 0; i < rois.size(); i++) {
            ROI roi = rois.get(i);
            if (roi == null)
                continue;
            try {
                if (!roi.contains(x, y))
                    continue;
                double area = roi.getArea();
                if (area < bestArea) {
                    bestArea = area;
                    best = i;
                }
            } catch (Exception ex) {
                logger.debug("Could not hit-test quiz browse region {}: {}", i, ex.getMessage());
            }
        }
        return best;
    }
}
