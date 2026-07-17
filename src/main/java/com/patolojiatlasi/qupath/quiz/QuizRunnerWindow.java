package com.patolojiatlasi.qupath.quiz;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.interfaces.ROI;

/**
 * Guided sequential quiz runner: load a portable quiz pack ({@link AtlasQuizIO}/{@link AtlasQuiz}),
 * step through its questions one at a time, and self-check with a Reveal button. Nothing is
 * scored, graded, or saved — this is read-only self-study over slides that stream in read-only
 * via {@link QuizSlide}.
 * <p>
 * Mirrors {@code com.patolojiatlasi.qupath.AtlasBrowser}'s single-window {@code show(...)} /
 * focus-if-open pattern and {@code com.patolojiatlasi.qupath.ProjectBuilderDialog}'s FX-thread-only
 * in-flight boolean guard around a background operation.
 * <p>
 * Alongside MCQ/FREETEXT, this also handles ANNOTATION questions (the learner draws with QuPath's
 * normal annotation tools; Reveal overlays the stored reference geometry) and NAVIGATION questions
 * (the learner pans/zooms to a region; Reveal overlays the target geometry and recentres the
 * viewer on it). Any annotation objects the learner draws while such a question is on screen are
 * transient: they are removed again as soon as that question is left (Önceki/Sonraki or window
 * close) — see {@link #leaveQuestion(AtlasQuiz, int)} — while everything present before the
 * question was shown is left untouched.
 */
public class QuizRunnerWindow {

    private static final Logger logger = LoggerFactory.getLogger(QuizRunnerWindow.class);

    private static Stage stage;

    private final QuPathGUI qupath;

    private AtlasQuiz quiz;
    private int currentIndex = 0; // 1-based; 0 = no quiz loaded yet

    // Guards overlapping slide opens: set true just before QuizSlide.openAsync is called for the
    // question being shown, cleared in its onDone/onError callback — both of which QuizSlide
    // guarantees run on the FX thread. While true, "Sınav yükle…", Önceki, Göster and Sonraki are
    // all disabled (see setControlsDisabled), so a second navigation click can't fire a second
    // openAsync while the first is still resolving. Touched only on the JavaFX thread, mirroring
    // AtlasBrowser's "opening" / ProjectBuilderDialog's "building" guard.
    private boolean opening = false;

    private final Label titleLabel = new Label("Sınav yüklenmedi");
    private final Label progressLabel = new Label("");
    private final ProgressIndicator progress = new ProgressIndicator();
    private final Button loadBtn = new Button("Sınav yükle…");

    private final Label promptLabel = new Label();
    private final VBox inputArea = new VBox(6);

    private final VBox revealArea = new VBox(6);
    private final Label revealAnswerLabel = new Label();
    private final Label revealExplanationLabel = new Label();

    private final Button prevBtn = new Button("Önceki");
    private final Button revealBtn = new Button("Göster");
    private final Button nextBtn = new Button("Sonraki");

    // Type-specific input state for the currently-displayed question; rebuilt by buildInput() on
    // every question change so stale controls from the previous question can't leak in.
    private ToggleGroup mcqGroup;

    // ANNOTATION/NAVIGATION reveal + transient-annotation state. `revealOverlay` is added to
    // `revealOverlayViewer` (captured at add-time, not re-fetched via qupath.getViewer(), so a
    // later viewer swap can't orphan it) by revealAnswer() and removed by leaveQuestion(). All
    // touched only on the FX thread, mirroring FocusHeatmap's currentOverlay/currentViewer pair.
    private QuizRevealOverlay revealOverlay;
    private QuPathViewer revealOverlayViewer;

    // Snapshot of the hierarchy's annotation objects taken when the current ANNOTATION/NAVIGATION
    // question's slide became ready (see afterSlideReady). On leaving such a question, only
    // annotations NOT in this set (i.e. drawn by the learner while the question was shown) are
    // removed -- anything present before the question was shown, including annotations from an
    // earlier question that were never cleared (see leaveQuestion's Javadoc), is left alone.
    //
    // The baseline is pinned to a specific viewer + ImageData (annotationBaselineViewer /
    // annotationBaselineImageData / annotationBaselineValid below), mirroring how showRevealOverlay
    // pins revealOverlayViewer, rather than being re-resolved against qupath.getViewer() live. That
    // live-read pattern is what the pinning replaces: without it, closing this window while a
    // slide is still loading, or the user switching the active viewer in a multi-viewer layout,
    // would make clearTransientAnnotations() diff against a stale/wrong-slide baseline and delete
    // unrelated annotations.
    private Set<PathObject> annotationBaseline = new HashSet<>();

    // The viewer annotationBaseline was captured against (see afterSlideReady). Read only via this
    // pinned reference in clearTransientAnnotations() -- never qupath.getViewer() live there.
    private QuPathViewer annotationBaselineViewer;

    // The ImageData that was showing in annotationBaselineViewer at capture time.
    // clearTransientAnnotations() refuses to touch anything if the pinned viewer's *current*
    // ImageData no longer matches this reference (the slide underneath it changed since capture).
    private ImageData<BufferedImage> annotationBaselineImageData;

    // Whether annotationBaseline/annotationBaselineViewer/annotationBaselineImageData currently
    // describe a real, usable baseline. Cleared to false (with the pinned refs nulled) whenever a
    // new question starts being shown -- before its slide has finished loading -- so that reaching
    // clearTransientAnnotations() before afterSlideReady() has run for that question (e.g. the
    // window is closed mid-load) is a safe no-op rather than a diff against a stale baseline. Set
    // true only once afterSlideReady() has captured a fresh baseline for an ANNOTATION/NAVIGATION
    // question.
    private boolean annotationBaselineValid = false;

    private QuizRunnerWindow(QuPathGUI qupath) {
        this.qupath = qupath;
    }

    /** Show (or focus) the single runner window. */
    public static void show(QuPathGUI qupath) {
        if (stage != null) {
            stage.show();
            stage.toFront();
            return;
        }
        QuizRunnerWindow runner = new QuizRunnerWindow(qupath);
        stage = runner.buildStage();
        stage.show();
    }

    private Stage buildStage() {
        Stage s = new Stage();
        s.setTitle("Sınav/quiz çöz");

        loadBtn.setOnAction(e -> promptLoad());
        progress.setVisible(false);
        progress.setPrefSize(18, 18);

        HBox top = new HBox(10, loadBtn, titleLabel, progressLabel, progress);
        top.setPadding(new Insets(8));
        top.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(titleLabel, Priority.ALWAYS);

        promptLabel.setWrapText(true);
        promptLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");

        inputArea.setPadding(new Insets(4, 0, 4, 0));

        revealAnswerLabel.setWrapText(true);
        revealExplanationLabel.setWrapText(true);
        revealExplanationLabel.setStyle("-fx-font-style: italic;");
        revealArea.getChildren().addAll(new Separator(), revealAnswerLabel, revealExplanationLabel);
        revealArea.setPadding(new Insets(8, 0, 0, 0));
        setRevealVisible(false);

        VBox center = new VBox(10, promptLabel, inputArea, revealArea);
        center.setPadding(new Insets(12));

        prevBtn.setOnAction(e -> goPrev());
        revealBtn.setOnAction(e -> revealAnswer());
        nextBtn.setOnAction(e -> goNext());
        // Nothing loaded yet — Önceki/Göster/Sonraki start disabled; loadBtn stays enabled.
        prevBtn.setDisable(true);
        revealBtn.setDisable(true);
        nextBtn.setDisable(true);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bottom = new HBox(6, spacer, prevBtn, revealBtn, nextBtn);
        bottom.setPadding(new Insets(8));
        bottom.setAlignment(Pos.CENTER_RIGHT);

        BorderPane root = new BorderPane();
        root.setTop(top);
        root.setCenter(center);
        root.setBottom(bottom);

        s.setScene(new Scene(root, 640, 480));
        s.setOnHidden(e -> {
            // Window close is a "leave" too: whatever question was on screen (if any) gets its
            // reveal overlay removed and its transient annotations cleared, same as Önceki/Sonraki,
            // so nothing leaks into the shared viewer once this window instance is discarded.
            leaveQuestion(quiz, currentIndex);
            stage = null;
        });
        return s;
    }

    /** "Sınav yükle…": pick a *.json pack and load it. On failure, keep the current state. */
    private void promptLoad() {
        if (opening)
            return; // extra guard beyond the disabled button; mirrors ProjectBuilderDialog
        FileChooser fc = new FileChooser();
        fc.setTitle("Sınav yükle…");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Quiz pack (*.json)", "*.json"));
        File file = fc.showOpenDialog(stage);
        if (file == null)
            return;
        // The try wraps ONLY the read: state (this.quiz/currentIndex/titleLabel) is assigned
        // below, after a fully-successful read+validate, so a failed load can never leave the
        // runner half-mutated (stale quiz reference with an unmoved question/index, or vice
        // versa). AtlasQuizIO.read wraps all Gson parse failures (including trailing-garbage
        // input, which Gson reports as JsonIOException rather than JsonSyntaxException) as a
        // checked IOException, so no beyond-spec RuntimeException catch is needed here.
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
            // Current state (previously-loaded quiz, if any) is left untouched.
            return;
        }
        // Loading a new pack abandons whatever question was on screen from the old one, just like
        // Önceki/Sonraki would -- clean it up using the OLD quiz/currentIndex, strictly BEFORE the
        // `quiz` field is reassigned below (leaveQuestion resolves `index` against the AtlasQuiz
        // passed to it, so calling it after reassignment would look up the old currentIndex in the
        // NEW, possibly-shorter pack and risk an out-of-range read).
        leaveQuestion(this.quiz, this.currentIndex);
        this.quiz = loaded;
        titleLabel.setText(loaded.getTitle().isBlank() ? file.getName() : loaded.getTitle());
        showQuestion(1);
    }

    /**
     * Show question {@code i} (1-based), clamped to {@code [1, N]}: update the progress label,
     * rebuild the type-specific input, hide the reveal area, then apply the question's slide
     * (reopening only if it differs from what's currently in the viewer).
     */
    private void showQuestion(int i) {
        if (quiz == null || quiz.getQuestions().isEmpty())
            return;
        int n = quiz.getQuestions().size();
        currentIndex = Math.max(1, Math.min(i, n));
        QuizQuestion q = quiz.getQuestions().get(currentIndex - 1);

        // Entering a new question invalidates any previously-captured annotation baseline: this
        // question's slide has not finished loading yet (that only happens in afterSlideReady,
        // below, via applySlide), so there is nothing valid to diff against until then. Without
        // this, a window close (or any other path into clearTransientAnnotations()) that lands
        // between here and afterSlideReady() running would otherwise diff against a stale baseline
        // captured for a *different* question/slide.
        annotationBaselineValid = false;
        annotationBaselineViewer = null;
        annotationBaselineImageData = null;

        progressLabel.setText("Soru " + currentIndex + " / " + n);
        promptLabel.setText(q.getPrompt());
        setRevealVisible(false);
        buildInput(q);
        applySlide(q);
    }

    /** Build the type-specific input control for {@code q} into {@link #inputArea}. */
    private void buildInput(QuizQuestion q) {
        inputArea.getChildren().clear();
        mcqGroup = null;
        switch (q.getType()) {
            case MCQ -> {
                mcqGroup = new ToggleGroup();
                List<String> options = q.getOptions() == null ? List.of() : q.getOptions();
                for (int idx = 0; idx < options.size(); idx++) {
                    RadioButton rb = new RadioButton(options.get(idx));
                    rb.setToggleGroup(mcqGroup);
                    rb.setUserData(idx);
                    rb.setWrapText(true);
                    inputArea.getChildren().add(rb);
                }
            }
            case FREETEXT -> {
                TextArea freetextArea = new TextArea();
                freetextArea.setPromptText("Notlarınızı buraya yazın…");
                freetextArea.setPrefRowCount(6);
                freetextArea.setWrapText(true);
                inputArea.getChildren().add(freetextArea);
            }
            case ANNOTATION -> {
                String instruction = q.getInstruction();
                Label label = new Label((instruction == null || instruction.isBlank())
                        ? "Cevabınızı slayt üzerine çizin" : instruction);
                label.setWrapText(true);
                inputArea.getChildren().add(label);
            }
            case NAVIGATION -> {
                Label label = new Label("İlgili bölgeye gidin");
                label.setWrapText(true);
                inputArea.getChildren().add(label);
            }
        }
    }

    /**
     * Open {@code q}'s slide unless it's already the one shown in the viewer. When a reload is
     * needed, disable "Sınav yükle…"/Önceki/Göster/Sonraki and show the progress indicator until
     * {@code onDone}/{@code onError} fires (both guaranteed by {@link QuizSlide} to run on the FX
     * thread); when the slide already matches, the controls are simply left enabled.
     */
    private void applySlide(QuizQuestion q) {
        String current = QuizSlide.currentSlideUrl(qupath.getViewer());
        if (q.getSlideUrl() != null && q.getSlideUrl().equals(current)) {
            setControlsDisabled(false);
            afterSlideReady(q);
            return;
        }
        opening = true;
        progress.setVisible(true);
        setControlsDisabled(true);
        QuizSlide.openAsync(qupath, q.getSlideUrl(),
                () -> {
                    opening = false;
                    progress.setVisible(false);
                    setControlsDisabled(false);
                    afterSlideReady(q);
                },
                ex -> {
                    opening = false;
                    progress.setVisible(false);
                    setControlsDisabled(false);
                    Alert alert = new Alert(Alert.AlertType.ERROR,
                            "Slayt açılamadı:\n\n" + ex.getMessage());
                    if (stage != null)
                        alert.initOwner(stage);
                    alert.showAndWait();
                });
    }

    /**
     * Called once {@code q}'s slide is confirmed showing in the viewer (either immediately, if it
     * was already open, or from {@link QuizSlide#openAsync}'s {@code onDone} callback) -- i.e.
     * exactly the point at which the hierarchy underneath the viewer reflects {@code q}'s slide.
     * Applies the question's {@link QuizQuestion.Viewport}, if any, and -- for ANNOTATION/NAVIGATION
     * questions, the two types the learner can draw on -- pins {@link #annotationBaselineViewer}/
     * {@link #annotationBaselineImageData} to the viewer/image actually used to show {@code q}, and
     * snapshots its current annotation objects as {@link #annotationBaseline} (marking it valid via
     * {@link #annotationBaselineValid}), so {@link #clearTransientAnnotations()} can later tell
     * which ones the learner added while answering -- against the *pinned* viewer/slide, not
     * whatever {@code qupath.getViewer()} happens to return at that later point.
     */
    private void afterSlideReady(QuizQuestion q) {
        QuPathViewer viewer = qupath.getViewer();
        if (viewer == null)
            return;
        QuizQuestion.Viewport vp = q.getViewport();
        if (vp != null)
            viewer.setDownsampleFactor(vp.downsample, vp.centerX, vp.centerY);
        if (q.getType() == QuizType.ANNOTATION || q.getType() == QuizType.NAVIGATION) {
            annotationBaselineViewer = viewer;
            annotationBaselineImageData = viewer.getImageData();
            annotationBaseline = currentAnnotations(viewer);
            annotationBaselineValid = true;
        }
    }

    /** "Göster": reveal the answer/explanation area for the current question. Idempotent. */
    private void revealAnswer() {
        if (quiz == null || currentIndex < 1)
            return;
        QuizQuestion q = quiz.getQuestions().get(currentIndex - 1);
        switch (q.getType()) {
            case MCQ -> {
                List<String> options = q.getOptions() == null ? List.of() : q.getOptions();
                Integer correct = q.getCorrectIndex();
                String correctText = (correct != null && correct >= 0 && correct < options.size())
                        ? options.get(correct) : "?";
                StringBuilder sb = new StringBuilder("Doğru cevap: ").append(correctText);
                Integer picked = selectedMcqIndex();
                if (picked != null && !picked.equals(correct)) {
                    String pickedText = (picked >= 0 && picked < options.size()) ? options.get(picked) : "?";
                    sb.append("\nSizin cevabınız: ").append(pickedText);
                }
                revealAnswerLabel.setText(sb.toString());
            }
            case FREETEXT -> {
                String model = q.getModelAnswer() == null ? "" : q.getModelAnswer();
                revealAnswerLabel.setText("Model cevap: " + model);
            }
            case ANNOTATION -> {
                ROI roi = parseGeometrySafely(q.getReferenceGeometryGeoJson());
                if (roi != null) {
                    showRevealOverlay(roi);
                    revealAnswerLabel.setText("Referans bölge slayt üzerinde gösteriliyor.");
                } else {
                    revealAnswerLabel.setText("Referans geometri mevcut değil.");
                }
            }
            case NAVIGATION -> {
                ROI roi = parseGeometrySafely(q.getTargetGeometryGeoJson());
                if (roi != null) {
                    showRevealOverlay(roi);
                    QuPathViewer viewer = qupath.getViewer();
                    if (viewer != null) {
                        double cx = roi.getBoundsX() + roi.getBoundsWidth() / 2.0;
                        double cy = roi.getBoundsY() + roi.getBoundsHeight() / 2.0;
                        viewer.setDownsampleFactor(viewer.getDownsampleFactor(), cx, cy);
                    }
                    revealAnswerLabel.setText("Hedef bölgeye gidildi.");
                } else {
                    revealAnswerLabel.setText("Hedef geometri mevcut değil.");
                }
            }
        }
        revealExplanationLabel.setText(q.getExplanation() == null ? "" : q.getExplanation());
        setRevealVisible(true);
    }

    /**
     * Parse {@code geoJson} into a ROI, defensively: {@link QuizGeometry#fromGeoJson} returns
     * {@code null} on blank input and throws (at least) {@code JsonSyntaxException} and
     * {@code IllegalArgumentException} on malformed input -- both are caught here (as a broad
     * {@code Exception} catch, since a third-party Gson adapter's exact failure modes are not part
     * of its documented contract) so a bad geometry in a hand-edited quiz pack shows an alert
     * instead of crashing the runner. The {@link ImagePlane} argument is accepted only for
     * signature symmetry with {@link QuizGeometry#fromGeoJson}; the GeoJSON always encodes its own
     * plane (see that method's Javadoc), so {@link ImagePlane#getDefaultPlane()} is passed here
     * without loss.
     */
    private ROI parseGeometrySafely(String geoJson) {
        if (geoJson == null || geoJson.isBlank())
            return null;
        try {
            return QuizGeometry.fromGeoJson(geoJson, ImagePlane.getDefaultPlane());
        } catch (Exception ex) {
            logger.warn("Failed to parse quiz reveal geometry: {}", ex.getMessage());
            Alert alert = new Alert(Alert.AlertType.ERROR,
                    "Referans/hedef geometri okunamadı:\n\n" + ex.getMessage());
            if (stage != null)
                alert.initOwner(stage);
            alert.showAndWait();
            return null;
        }
    }

    /**
     * Add a {@link QuizRevealOverlay} for {@code roi} to the active viewer, replacing any reveal
     * overlay already showing (so repeated Göster clicks -- or a NAVIGATION reveal that recentres
     * the same viewer -- never stack up duplicate overlays).
     */
    private void showRevealOverlay(ROI roi) {
        QuPathViewer viewer = qupath.getViewer();
        if (viewer == null)
            return;
        removeRevealOverlay();
        QuizRevealOverlay overlay = new QuizRevealOverlay(viewer.getOverlayOptions(), roi);
        viewer.getCustomOverlayLayers().add(overlay);
        revealOverlay = overlay;
        revealOverlayViewer = viewer;
        viewer.repaint();
    }

    /** Remove the currently-showing reveal overlay, if any, from the viewer it was added to. */
    private void removeRevealOverlay() {
        if (revealOverlay == null)
            return;
        try {
            if (revealOverlayViewer != null) {
                revealOverlayViewer.getCustomOverlayLayers().remove(revealOverlay);
                revealOverlayViewer.repaint();
            }
        } catch (Exception ex) {
            logger.debug("Could not remove quiz reveal overlay: {}", ex.getMessage());
        } finally {
            revealOverlay = null;
            revealOverlayViewer = null;
        }
    }

    /**
     * The hierarchy's current annotation objects for {@code viewer}'s image, or an empty set if
     * there is no image/hierarchy. Used by {@link #afterSlideReady(QuizQuestion)} to snapshot
     * {@link #annotationBaseline}; {@link #clearTransientAnnotations()} reads the pinned
     * {@link #annotationBaselineViewer}'s hierarchy directly instead, since by that point it also
     * needs the {@link PathObjectHierarchy} reference to call {@code removeObjects} on.
     */
    private Set<PathObject> currentAnnotations(QuPathViewer viewer) {
        try {
            ImageData<BufferedImage> imageData = viewer == null ? null : viewer.getImageData();
            if (imageData == null)
                return new HashSet<>();
            return new HashSet<>(imageData.getHierarchy().getAnnotationObjects());
        } catch (Exception ex) {
            logger.debug("Could not read quiz annotation objects: {}", ex.getMessage());
            return new HashSet<>();
        }
    }

    /**
     * Remove annotation objects added to the hierarchy since {@link #annotationBaseline} was last
     * captured (in {@link #afterSlideReady(QuizQuestion)}) -- i.e. drawn by the learner while the
     * just-left ANNOTATION/NAVIGATION question was on screen. Anything present before the question
     * was shown (including annotations from an earlier, un-cleared MCQ/FREETEXT question -- those
     * types never trigger this cleanup, since they carry no draw instruction) is left untouched, so
     * this can never discard a learner's or the atlas author's pre-existing work.
     * <p>
     * Acts <em>only</em> on the pinned {@link #annotationBaselineViewer}/
     * {@link #annotationBaselineImageData} -- never {@code qupath.getViewer()} live -- and only when
     * {@link #annotationBaselineValid} is still {@code true} and the pinned viewer's current image
     * data still matches what was captured. This is what makes both of the unrelated-deletion paths
     * safe: closing the window (or otherwise reaching here) before {@link #afterSlideReady} has run
     * for the current question finds {@code annotationBaselineValid == false} and does nothing;
     * the user switching the active viewer in a multi-viewer layout still resolves against the
     * viewer the baseline was actually captured against, and if that viewer's slide itself changed
     * in the meantime the image-data check below bails out instead of diffing against the new slide.
     */
    private void clearTransientAnnotations() {
        try {
            if (!annotationBaselineValid || annotationBaselineViewer == null)
                return;
            ImageData<BufferedImage> imageData = annotationBaselineViewer.getImageData();
            if (imageData == null || imageData != annotationBaselineImageData) {
                // The pinned viewer's slide changed since the baseline was captured (or was closed) --
                // diffing against it would compare the wrong slide's annotations, so do not touch it.
                annotationBaselineValid = false;
                return;
            }
            PathObjectHierarchy hierarchy = imageData.getHierarchy();
            Set<PathObject> toRemove = new HashSet<>(hierarchy.getAnnotationObjects());
            toRemove.removeAll(annotationBaseline);
            if (!toRemove.isEmpty()) {
                // removeObjects(coll, true) already fires the hierarchy-changed event internally --
                // no separate fireHierarchyChangedEvent call is needed.
                hierarchy.removeObjects(toRemove, true);
            }
            annotationBaselineValid = false;
        } catch (Exception ex) {
            logger.debug("Could not clear transient quiz annotations: {}", ex.getMessage());
        }
    }

    /**
     * Clean up whatever question was on screen before navigating away from it: remove the reveal
     * overlay (regardless of question type -- it is only ever non-null for a just-left
     * ANNOTATION/NAVIGATION question, so this is always safe) and, for ANNOTATION/NAVIGATION
     * questions specifically, clear the learner's transient answer annotations (see
     * {@link #clearTransientAnnotations()}).
     * <p>
     * Callers pass the quiz/index describing the question being left <em>explicitly</em>, evaluated
     * before any field reassignment, rather than reading {@link #quiz}/{@link #currentIndex} here --
     * {@link #promptLoad()} reassigns {@link #quiz} before advancing to the new pack's first
     * question, so resolving against live fields at that point could look up a stale index in the
     * new (possibly shorter) pack.
     */
    private void leaveQuestion(AtlasQuiz q, int index) {
        if (q == null || index < 1 || index > q.getQuestions().size())
            return;
        removeRevealOverlay();
        QuizQuestion previous = q.getQuestions().get(index - 1);
        if (previous.getType() == QuizType.ANNOTATION || previous.getType() == QuizType.NAVIGATION)
            clearTransientAnnotations();
    }

    /** The 0-based index of the learner's selected MCQ radio button, or null if none picked. */
    private Integer selectedMcqIndex() {
        if (mcqGroup == null)
            return null;
        Toggle t = mcqGroup.getSelectedToggle();
        return t == null ? null : (Integer) t.getUserData();
    }

    /** "Önceki": no-op at question 1 (nothing changed to re-show); otherwise show i-1. */
    private void goPrev() {
        if (quiz == null || opening)
            return;
        int target = Math.max(1, currentIndex - 1);
        if (target != currentIndex) {
            // Evaluated against the still-current quiz/currentIndex, before showQuestion moves on.
            leaveQuestion(quiz, currentIndex);
            showQuestion(target);
        }
    }

    /** "Sonraki": no-op at the last question; otherwise show i+1. */
    private void goNext() {
        if (quiz == null || opening)
            return;
        int n = quiz.getQuestions().size();
        int target = Math.min(n, currentIndex + 1);
        if (target != currentIndex) {
            // Evaluated against the still-current quiz/currentIndex, before showQuestion moves on.
            leaveQuestion(quiz, currentIndex);
            showQuestion(target);
        }
    }

    private void setRevealVisible(boolean visible) {
        revealArea.setVisible(visible);
        revealArea.setManaged(visible);
    }

    /** Disable/enable "Sınav yükle…" + Önceki/Göster/Sonraki together, while a slide is loading. */
    private void setControlsDisabled(boolean disabled) {
        loadBtn.setDisable(disabled);
        prevBtn.setDisable(disabled);
        revealBtn.setDisable(disabled);
        nextBtn.setDisable(disabled);
    }
}
