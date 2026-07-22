package com.patolojiatlasi.qupath;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Optional;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.patolojiatlasi.qupath.focus.FocusHeatmap;
import com.patolojiatlasi.qupath.research.BlindedResearch;

import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.images.ImageData;
import qupath.lib.projects.Project;

/**
 * QuPath extension that adds a browsable catalogue of whole-slide images from
 * patolojiatlasi.com, opened directly in QuPath by streaming their Deep Zoom tiles.
 */
public class AtlasExtension implements QuPathExtension {

    private static final Logger logger = LoggerFactory.getLogger(AtlasExtension.class);

    /** Retained so Task 3's project-open/close hook can drive blinded recording on the same instance. */
    private FocusHeatmap focusHeatmap;

    /** Retained so {@link #onProjectChanged} can own the consent {@link Alert} (stage owner lookup). */
    private QuPathGUI qupath;

    @Override
    public String getName() {
        return "Patoloji Atlası";
    }

    @Override
    public String getDescription() {
        return "Browse and open whole-slide images from the Patoloji Atlası "
                + "(patolojiatlasi.com / histopathologyatlas.com) directly inside QuPath.";
    }

    @Override
    public Version getQuPathVersion() {
        return Version.parse("0.6.0");
    }

    @Override
    public void installExtension(QuPathGUI qupath) {
        this.qupath = qupath;
        try {
            // This extension's atlas-specific actions live under one top-level "Patoloji Atlası"
            // menu, grouped into sub-menus, instead of being scattered flat among the other
            // extensions' items in Extensions. The atlas-independent research tools (focus
            // heatmap / blinded recording, rotation, flag-any-project) live in a separate
            // top-level "Araştırma" menu (sibling of this one) so they read as general tools that
            // work on any slide, not just atlas ones.
            Menu atlas = new Menu("Patoloji Atlası");

            // Primary entry: browse & open atlas slides.
            MenuItem browseItem = new MenuItem("Slaytlara gözat…");
            browseItem.setOnAction(e -> AtlasBrowser.show(qupath));

            // Catalogue coverage & QC dashboard — a read-only category x stain matrix, opt-in
            // link check, and drill-down into the project builder.
            MenuItem coverageItem = new MenuItem("Katalog kapsamı ve QC…");
            coverageItem.setOnAction(e -> CoverageDashboard.show(qupath));

            // Research group — reorientation, the focus (dwell) heatmap sub-menu (which includes
            // the blinded-record toggle), and flag-any-project, surfaced as a separate top-level
            // "Araştırma" menu built further down. Atlas-independent: applies to any open slide.
            RotationControl rotation = new RotationControl(qupath);
            MenuItem rotationItem = new MenuItem("Görüntüyü döndür…");
            rotationItem.setOnAction(e -> rotation.show());
            this.focusHeatmap = new FocusHeatmap(qupath);
            MenuItem flagProjectItem = new MenuItem("Mevcut projeyi araştırma projesi yap (kör kayıt)…");
            flagProjectItem.setOnAction(e -> flagCurrentProjectAsResearch(qupath));

            // Blinded-research auto-start: a project can carry a "blinded tracking" flag
            // (set from the project builder checkbox); opening such a project starts blinded
            // recording on this same retained focusHeatmap instance, after a one-time consent
            // notice. Property fires on the FX thread, so the hook itself doesn't need to.
            qupath.projectProperty().addListener((obs, oldProj, newProj) -> onProjectChanged(oldProj, newProj));
            // JavaFX ChangeListeners don't fire for the value already in place at registration
            // time, so a project opened before this extension installed (or before this listener
            // was attached) would never get its blinded-tracking flag checked. Evaluate it once,
            // deferred via runLater so a consent dialog can't pop up synchronously during
            // extension install; oldProj=null is correct here -- there's no outgoing project.
            javafx.application.Platform.runLater(() -> onProjectChanged(null, qupath.getProject()));

            // Compare group — a case's stains in a synchronized multi-viewer grid.
            MenuItem compareItem = new MenuItem("Bu vakanın boyalarını karşılaştır…");
            compareItem.setOnAction(e -> com.patolojiatlasi.qupath.CaseCompare.compareCurrentCase(qupath));
            MenuItem singleItem = new MenuItem("Tek görünüme dön");
            singleItem.setOnAction(e -> com.patolojiatlasi.qupath.CaseCompare.backToSingle(qupath));
            Menu compareMenu = new Menu("Karşılaştır");
            compareMenu.getItems().addAll(compareItem, singleItem);

            // Reference group — open an atlas case as a bench-side reference beside your own slide.
            MenuItem refPickItem = new MenuItem("Referans slayt aç…");
            refPickItem.setOnAction(e -> com.patolojiatlasi.qupath.ReferencePickerDialog.show(qupath));
            Menu referenceMenu = new Menu("Referans");
            referenceMenu.getItems().add(refPickItem);

            // Related-content navigator — companion window: the open case's other stains + other
            // cases in the same category, auto-following the active viewer.
            MenuItem relatedItem = new MenuItem("İlgili içerik…");
            relatedItem.setOnAction(e -> RelatedContentNavigator.show(qupath));

            // Citation group — cite the slide currently open in the active viewer, or a selected region.
            MenuItem citeOpenItem = new MenuItem("Açık slaytı alıntıla…");
            citeOpenItem.setOnAction(e -> {
                AtlasCase open = resolveOpenCase(qupath);
                if (open == null) {
                    Alert alert = new Alert(Alert.AlertType.INFORMATION,
                            "Açık slayt atlas kataloğunda bulunamadı.");
                    if (qupath.getStage() != null)
                        alert.initOwner(qupath.getStage());
                    alert.showAndWait();
                    return;
                }
                CitationDialog.show(qupath, open);
            });
            MenuItem citeRegionItem = new MenuItem("Bu bölgeyi alıntıla…");
            citeRegionItem.setOnAction(e -> FigureCitationDialog.show(qupath));
            Menu citationMenu = new Menu("Atıf");
            citationMenu.getItems().addAll(citeOpenItem, citeRegionItem);

            // Quiz group — self-study quiz: take / author.
            MenuItem quizTakeItem = new MenuItem("Çöz…");
            quizTakeItem.setOnAction(e -> com.patolojiatlasi.qupath.quiz.QuizRunnerWindow.show(qupath));
            MenuItem quizAuthorItem = new MenuItem("Hazırla…");
            quizAuthorItem.setOnAction(e -> com.patolojiatlasi.qupath.quiz.QuizAuthorWindow.show(qupath));
            Menu quizMenu = new Menu("Sınav / Quiz");
            quizMenu.getItems().addAll(quizTakeItem, quizAuthorItem);

            atlas.getItems().addAll(
                    browseItem,
                    coverageItem,
                    new SeparatorMenuItem(),
                    compareMenu, referenceMenu, relatedItem, citationMenu, quizMenu);

            Menu research = new Menu("Araştırma");
            research.getItems().addAll(
                    focusHeatmap.buildMenu(),
                    rotationItem,
                    new SeparatorMenuItem(),
                    flagProjectItem);

            qupath.getMenu("Extensions", true).getItems().addAll(atlas, research);
            logger.info("Patoloji Atlası extension installed");
        } catch (Exception e) {
            logger.error("Error installing Patoloji Atlası extension: {}", e.getMessage(), e);
        }
    }

    /**
     * Resolves the atlas case shown in {@code qupath}'s active viewer, matched by DZI URL
     * (ignoring any {@code ?mpp=} query) against the bundled catalogue — the same matching
     * approach {@link CaseCompare#siblingStains} uses for its own active-viewer lookup. Returns
     * {@code null} if there's no active viewer/image, or the shown slide isn't a cataloged atlas
     * slide.
     * <p>
     * Package-visible so {@link FigureCitationDialog} can reuse the same active-viewer→catalogue
     * match for the "cite this region" action.
     */
    static AtlasCase resolveOpenCase(QuPathGUI qupath) {
        if (qupath == null)
            return null;
        try {
            QuPathViewer viewer = qupath.getViewer();
            ImageData<BufferedImage> data = viewer == null ? null : viewer.getImageData();
            if (data == null || data.getServer() == null)
                return null;
            var uris = data.getServer().getURIs();
            if (uris == null || uris.isEmpty())
                return null;
            String openBase = CaseCompare.stripQuery(uris.iterator().next().toString());
            for (AtlasCase c : AtlasCatalog.loadBundled()) {
                if (CaseCompare.stripQuery(c.getDziUrl()).equals(openBase))
                    return c;
            }
            return null;
        } catch (Exception e) {
            logger.debug("Could not resolve open atlas case: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Flags the currently open project (any project -- atlas-built, local-slide, or server-slide)
     * as a blinded-research project: writes the {@code atlas-research.json} sidecar, marks consent
     * (the researcher is opting in right now, in this very action, so there's no need for the
     * {@link #onProjectChanged} hook to prompt again later), and starts blinded recording
     * immediately on the retained {@link #focusHeatmap} instance -- the same instance the project-
     * open hook and the "Araştırma" menu's focus sub-menu drive. This is what lets a researcher use
     * blinded recording on their own project, not just one built from the atlas browser.
     */
    private void flagCurrentProjectAsResearch(QuPathGUI qupath) {
        Project<?> p = qupath.getProject();
        if (p == null) {
            Alert alert = new Alert(Alert.AlertType.INFORMATION, "Önce bir proje açın veya oluşturun.");
            if (qupath.getStage() != null)
                alert.initOwner(qupath.getStage());
            alert.showAndWait();
            return;
        }
        File dir = BlindedResearch.projectDir(p);
        if (dir == null) {
            Alert alert = new Alert(Alert.AlertType.INFORMATION,
                    "Proje klasörünü bulmak için önce projeyi kaydedin.");
            if (qupath.getStage() != null)
                alert.initOwner(qupath.getStage());
            alert.showAndWait();
            return;
        }
        if (BlindedResearch.isBlindedProject(p)) {
            Alert alert = new Alert(Alert.AlertType.INFORMATION, "Bu zaten bir araştırma projesi.");
            if (qupath.getStage() != null)
                alert.initOwner(qupath.getStage());
            alert.showAndWait();
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Bu proje bir araştırma projesi yapılacak: bu oturumda ve her açılışta anonim, "
                        + "gizli (kör) odak kaydı tutulur — ekranda ısı haritası gösterilmez. "
                        + "Devam edilsin mi?");
        confirm.setTitle("Araştırma projesi");
        confirm.setHeaderText("Araştırma projesi");
        if (qupath.getStage() != null)
            confirm.initOwner(qupath.getStage());
        Optional<ButtonType> choice = confirm.showAndWait();
        if (choice.isPresent() && choice.get() == ButtonType.OK) {
            BlindedResearch.writeFlag(dir, true);
            BlindedResearch.markConsented(p);
            focusHeatmap.startBlinded();
            Alert done = new Alert(Alert.AlertType.INFORMATION,
                    "Proje işaretlendi; kör kayıt başladı ve her açılışta sürecek.");
            if (qupath.getStage() != null)
                done.initOwner(qupath.getStage());
            done.showAndWait();
        }
        // else: cancelled -- flag not written, recording not started.
    }

    /**
     * Blinded-research auto-start hook, run whenever {@code qupath.projectProperty()} fires (i.e.
     * the active project changed — opened, closed, or switched). Stops any in-progress blinded
     * recording from the closing project, then — if the newly-opened project carries the
     * blinded-tracking flag (see {@link BlindedResearch}) — starts it again, showing a one-time
     * consent notice first if this project hasn't been consented to yet. Declining the notice
     * leaves {@code consented} false, so the same notice reappears next time this project opens
     * and recording stays off; accepting starts recording immediately and every later re-open of
     * this project skips straight to {@link FocusHeatmap#startBlinded()}.
     */
    private void onProjectChanged(Project<BufferedImage> oldProj, Project<BufferedImage> newProj) {
        if (oldProj != null && focusHeatmap.isBlinded())
            focusHeatmap.stopBlinded();

        if (newProj == null || !BlindedResearch.isBlindedProject(newProj))
            return;
        // Re-entrancy guard: never re-start (or re-prompt for) a session that's already recording.
        if (focusHeatmap.isBlinded())
            return;

        if (BlindedResearch.hasConsented(newProj)) {
            focusHeatmap.startBlinded();
            return;
        }

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "Bu proje araştırma amaçlı kör odak kaydı için işaretlenmiş. Kabul ederseniz, "
                        + "bu oturumdan itibaren bakılan bölgeler ve süre anonimleştirilmiş şekilde "
                        + "sessizce kaydedilir; kimlik bilgisi kaydedilmez ve uygulama içinde hiçbir "
                        + "şey gösterilmez.");
        alert.setTitle("Araştırma kaydı");
        alert.setHeaderText("Araştırma kaydı");
        if (qupath.getStage() != null)
            alert.initOwner(qupath.getStage());
        Optional<ButtonType> choice = alert.showAndWait();
        if (choice.isPresent() && choice.get() == ButtonType.OK) {
            BlindedResearch.markConsented(newProj);
            focusHeatmap.startBlinded();
        }
        // else: declined -- consented stays false, so the notice is shown again on next open and
        // recording stays off for this session.
    }
}
