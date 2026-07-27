package com.patolojiatlasi.qupath.pathologycot;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.patolojiatlasi.qupath.quiz.AtlasQuiz;
import com.patolojiatlasi.qupath.quiz.AtlasQuizIO;
import com.patolojiatlasi.qupath.quiz.QuizAuthorWindow;
import com.patolojiatlasi.qupath.quiz.QuizSlide;
import com.patolojiatlasi.qupath.research.BlindedResearch;

import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;

/**
 * JavaFX menu actions that wire the Pathology-CoT data engine ({@link PathologyCotIO}/
 * {@link PathologyCotDiscretizer}/{@link PathologyCotDraft}/{@link PathologyCotExport}) into
 * three {@code Araştırma → Pathology-CoT} menu items:
 * <ol>
 *   <li>{@link #draftFromRecording} — turn a recorded blinded/focus fragment into a reviewable
 *       guided-tour draft (quiz pack) + its {@code pathology-cot-behaviors/1} sidecar;</li>
 *   <li>{@link #reviewDraft} — open the existing tour author so the reader can fill in each
 *       stop's rationale;</li>
 *   <li>{@link #exportDataset} — export a reviewed tour + the open slide into a self-contained
 *       Pathology-CoT case folder (crops + {@code conversation.json} + {@code README.txt}).</li>
 * </ol>
 * Every handler wraps its entire body in a broad {@code catch (Exception ...)} — never just
 * {@code IOException} — because {@link PathologyCotIO#readFragment} can throw an unchecked
 * exception (e.g. {@link IllegalStateException}) on a malformed {@code path} array, and a menu
 * action must never crash QuPath; failures are always reported via an owned {@link Alert}.
 * Mirrors {@code focus.FocusHeatmap}'s async-save discipline (background {@link Thread} +
 * {@link Platform#runLater} for anything touching a slide-backed {@link ImageServer}) and
 * {@code quiz.QuizAuthorWindow}/{@code quiz.QuizRunnerWindow}'s {@link FileChooser}/{@link Alert}
 * + {@code initOwner} conventions.
 */
public final class PathologyCotActions {

    private static final Logger logger = LoggerFactory.getLogger(PathologyCotActions.class);

    private PathologyCotActions() {
    }

    /**
     * "Gezinme kaydından CoT taslağı oluştur…": pick a recorded fragment, discretize its scanpath
     * into inspect/peek {@link Behavior}s, then save both the {@code pathology-cot-behaviors/1}
     * analysis-contract sidecar and a reviewable guided-tour draft (quiz pack) built from those
     * behaviors — finally opening the tour author so the reader can fill in each stop's rationale.
     */
    public static void draftFromRecording(QuPathGUI qupath) {
        try {
            ImageData<BufferedImage> vd = currentImageDataOrNull(qupath);
            if (vd == null) {
                showInfo(qupath, "Önce bir slayt açın.");
                return;
            }

            File fragmentDir = atlasFocusDirOrNull(qupath);
            FileChooser fc = new FileChooser();
            fc.setTitle("Gezinme kaydı (fragment) seç");
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Fragment (*.json)", "*.json"));
            if (fragmentDir != null)
                fc.setInitialDirectory(fragmentDir);
            File fragmentFile = fc.showOpenDialog(qupath.getStage());
            if (fragmentFile == null)
                return;

            CotFragment fragment = PathologyCotIO.readFragment(fragmentFile);

            String openUri = QuizSlide.currentSlideUrl(qupath.getViewer());
            if (!PathologyCotIO.slideMatches(fragment, openUri)) {
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                        "Bu kayıt açık slayta ait olmayabilir (slayt anahtarı eşleşmiyor). "
                                + "Yine de devam edilsin mi?");
                confirm.setTitle("Slayt uyuşmazlığı");
                confirm.setHeaderText("Slayt uyuşmazlığı");
                if (qupath.getStage() != null)
                    confirm.initOwner(qupath.getStage());
                Optional<ButtonType> choice = confirm.showAndWait();
                if (choice.isEmpty() || choice.get() != ButtonType.OK)
                    return;
            }

            List<Behavior> behaviors = PathologyCotDiscretizer.discretize(
                    fragment.path(), fragment.baseMagnification(), fragment.imageWidth(), fragment.imageHeight());
            if (behaviors.isEmpty()) {
                showInfo(qupath, "Bu kayıttan çıkarılabilir inceleme/yakın-bakış bulunamadı.");
                return;
            }

            FileChooser saveFc = new FileChooser();
            saveFc.setTitle("CoT taslağını kaydet…");
            saveFc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Quiz pack (*.json)", "*.json"));
            saveFc.setInitialFileName(suggestedDraftName(fragmentFile));
            if (fragmentDir != null)
                saveFc.setInitialDirectory(fragmentDir);
            File draftFile = saveFc.showSaveDialog(qupath.getStage());
            if (draftFile == null)
                return;
            if (!draftFile.getName().toLowerCase(Locale.ROOT).endsWith(".json"))
                draftFile = new File(draftFile.getAbsolutePath() + ".json");

            String slideUrl = QuizSlide.currentSlideUrl(qupath.getViewer());
            String slideTitle = slideTitle(vd, slideUrl);
            AtlasQuiz draft = PathologyCotDraft.buildDraft(
                    behaviors, slideUrl, slideTitle, fragment.baseMagnification(), fragment.decision());

            // Write the sidecar BEFORE the named draft artifact: if writeBehaviors throws partway
            // through, nothing "complete-looking" has landed yet. Writing the draft last also means
            // the success Alert below is only reachable once the draft file itself has landed.
            File behaviorsFile = siblingBehaviorsFile(draftFile);
            PathologyCotIO.writeBehaviors(behaviorsFile, fragment, behaviors);

            AtlasQuizIO.write(draft, draftFile);

            QuizAuthorWindow.show(qupath);
            // QuizAuthorWindow has no public "load a pack from File" entry point (only its own
            // "Aç…" FileChooser button) -- so rather than reach into its internals, tell the
            // reader the exact path to open there themselves.
            showInfo(qupath, "Taslak kaydedildi: " + draftFile.getAbsolutePath()
                    + "\nDavranış sözlüğü (behaviors): " + behaviorsFile.getAbsolutePath()
                    + "\n\nAçılan \"Sınav/quiz hazırla\" penceresinde \"Aç…\" ile bu taslağı yükleyin ve "
                    + "her durak için gerekçeyi (neden baktım / ne gördüm) girip kaydedin.");
        } catch (Exception ex) {
            logger.warn("Pathology-CoT draft creation failed: {}", message(ex), ex);
            showError(qupath, "CoT taslağı oluşturulamadı:\n\n" + message(ex));
        }
    }

    /** "CoT taslağını gözden geçir…": thin convenience — the tour author already loads/saves packs. */
    public static void reviewDraft(QuPathGUI qupath) {
        try {
            QuizAuthorWindow.show(qupath);
        } catch (Exception ex) {
            logger.warn("Could not open the Pathology-CoT draft reviewer: {}", message(ex), ex);
            showError(qupath, "Gözden geçirme penceresi açılamadı:\n\n" + message(ex));
        }
    }

    /**
     * "Pathology-CoT veri kümesi olarak dışa aktar…": pick a reviewed tour + a target folder, then
     * write the case folder on a background thread (crops via {@link ImageServer#readRegion} are
     * not FX-thread work) — reporting the exported ROI count, or any failure, back via an
     * {@link Alert} on the FX thread.
     */
    public static void exportDataset(QuPathGUI qupath) {
        try {
            ImageData<BufferedImage> vd = currentImageDataOrNull(qupath);
            ImageServer<BufferedImage> server = vd == null ? null : vd.getServer();
            if (server == null) {
                showInfo(qupath, "Önce bir slayt açın.");
                return;
            }

            FileChooser fc = new FileChooser();
            fc.setTitle("Gözden geçirilmiş CoT taslağını seç");
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Quiz pack (*.json)", "*.json"));
            File reviewedFile = fc.showOpenDialog(qupath.getStage());
            if (reviewedFile == null)
                return;

            AtlasQuiz quiz = AtlasQuizIO.read(reviewedFile);

            // Same failure class draftFromRecording's fragment/slide check guards against: a pack
            // authored against a different slide would otherwise silently pair its stored ROIs with
            // crops from whatever slide happens to be open now. Compared via PathologyCotIO.slideKey
            // (query-stripped -- e.g. the atlas's "?mpp=" query) rather than raw equality, matching
            // draftFromRecording's own slide-match check just above; a quiz pack's slideUrl is the
            // plain DZI/file URL QuizSlide binds questions with, so slideKey-vs-slideKey is the right
            // granularity here (not the further anonymized slideKey hash used for fragments).
            String packSlideUrl = quiz.getQuestions().isEmpty() ? null : quiz.getQuestions().get(0).getSlideUrl();
            String openSlideUrl = QuizSlide.currentSlideUrl(qupath.getViewer());
            if (packSlideUrl != null && !packSlideUrl.isBlank()
                    && !PathologyCotIO.slideKey(packSlideUrl).equals(PathologyCotIO.slideKey(openSlideUrl))) {
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                        "Bu paket farklı bir slayta ait görünüyor (kayıtlı slayt açık slaytla eşleşmiyor). "
                                + "Kırpmalar açık slayttan alınacak. Yine de devam edilsin mi?");
                confirm.setTitle("Slayt uyuşmazlığı");
                confirm.setHeaderText("Slayt uyuşmazlığı");
                if (qupath.getStage() != null)
                    confirm.initOwner(qupath.getStage());
                Optional<ButtonType> choice = confirm.showAndWait();
                if (choice.isEmpty() || choice.get() != ButtonType.OK)
                    return;
            }

            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("Dışa aktarım klasörünü seç");
            File targetDir = dc.showDialog(qupath.getStage());
            if (targetDir == null)
                return;

            Thread worker = new Thread(() -> {
                try {
                    int count = PathologyCotExport.export(quiz, server, targetDir);
                    Platform.runLater(() -> showInfo(qupath,
                            "Dışa aktarıldı: " + count + " ROI.\nKlasör: " + targetDir.getAbsolutePath()));
                } catch (Exception ex) {
                    logger.warn("Pathology-CoT export failed: {}", message(ex), ex);
                    Platform.runLater(() -> showError(qupath, "Dışa aktarma başarısız:\n\n" + message(ex)));
                }
            }, "pathology-cot-export");
            worker.setDaemon(true);
            worker.start();
        } catch (Exception ex) {
            logger.warn("Pathology-CoT export could not be started: {}", message(ex), ex);
            showError(qupath, "Dışa aktarma başlatılamadı:\n\n" + message(ex));
        }
    }

    // --- helpers -------------------------------------------------------------------------------

    private static ImageData<BufferedImage> currentImageDataOrNull(QuPathGUI qupath) {
        QuPathViewer viewer = qupath.getViewer();
        return viewer == null ? null : viewer.getImageData();
    }

    /** {@code <projectDir>/atlas-focus} when a project is open on disk and that folder already
     *  exists (where recorded fragments/zips actually land — see {@code focus.BlindedStore}), else
     *  {@code null} (no {@link FileChooser#setInitialDirectory} call, letting it fall back to its
     *  own default). */
    private static File atlasFocusDirOrNull(QuPathGUI qupath) {
        if (qupath.getProject() == null)
            return null;
        File projectDir = BlindedResearch.projectDir(qupath.getProject());
        if (projectDir == null)
            return null;
        File dir = new File(projectDir, "atlas-focus");
        return dir.isDirectory() ? dir : null;
    }

    /** The open server's display name, falling back to the slide URL's basename. */
    private static String slideTitle(ImageData<BufferedImage> vd, String slideUrl) {
        try {
            if (vd != null && vd.getServer() != null) {
                String name = vd.getServer().getMetadata().getName();
                if (name != null && !name.isBlank())
                    return name;
            }
        } catch (Exception ignored) {
            // fall through to the URL-basename fallback below
        }
        return basename(slideUrl);
    }

    /** Last path segment of {@code url}, minus any query string. "" for a null/blank url. */
    private static String basename(String url) {
        if (url == null || url.isBlank())
            return "";
        String s = url;
        int q = s.indexOf('?');
        if (q >= 0)
            s = s.substring(0, q);
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        return slash >= 0 ? s.substring(slash + 1) : s;
    }

    private static String suggestedDraftName(File fragmentFile) {
        return stripJsonExtension(fragmentFile.getName()) + "-taslak.json";
    }

    /** {@code <draftBaseName>.behaviors.json}, sibling to {@code draftFile}. */
    private static File siblingBehaviorsFile(File draftFile) {
        String base = stripJsonExtension(draftFile.getName());
        return new File(draftFile.getParentFile(), base + ".behaviors.json");
    }

    private static String stripJsonExtension(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".json") ? name.substring(0, name.length() - 5) : name;
    }

    private static String message(Exception ex) {
        String m = ex.getMessage();
        return m != null ? m : ex.toString();
    }

    private static void showInfo(QuPathGUI qupath, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, message);
        if (qupath.getStage() != null)
            alert.initOwner(qupath.getStage());
        alert.showAndWait();
    }

    private static void showError(QuPathGUI qupath, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message);
        if (qupath.getStage() != null)
            alert.initOwner(qupath.getStage());
        alert.showAndWait();
    }
}
