package com.patolojiatlasi.qupath;

import java.awt.image.BufferedImage;

import javafx.scene.control.Alert;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.patolojiatlasi.qupath.focus.FocusHeatmap;

import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.images.ImageData;

/**
 * QuPath extension that adds a browsable catalogue of whole-slide images from
 * patolojiatlasi.com, opened directly in QuPath by streaming their Deep Zoom tiles.
 */
public class AtlasExtension implements QuPathExtension {

    private static final Logger logger = LoggerFactory.getLogger(AtlasExtension.class);

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
        try {
            // All of this extension's actions live under one top-level "Patoloji Atlası" menu,
            // grouped into sub-menus (with the focus heatmap as a sub-sub-menu), instead of being
            // scattered flat among the other extensions' items in Extensions.
            Menu atlas = new Menu("Patoloji Atlası");

            // Primary entry: browse & open atlas slides.
            MenuItem browseItem = new MenuItem("Slaytlara gözat…");
            browseItem.setOnAction(e -> AtlasBrowser.show(qupath));

            // Catalogue coverage & QC dashboard — a read-only category x stain matrix, opt-in
            // link check, and drill-down into the project builder.
            MenuItem coverageItem = new MenuItem("Katalog kapsamı ve QC…");
            coverageItem.setOnAction(e -> CoverageDashboard.show(qupath));

            // View group — reorientation + the focus (dwell) heatmap sub-menu.
            RotationControl rotation = new RotationControl(qupath);
            MenuItem rotationItem = new MenuItem("Görüntüyü döndür…");
            rotationItem.setOnAction(e -> rotation.show());
            Menu viewMenu = new Menu("Görünüm");
            viewMenu.getItems().addAll(rotationItem, new FocusHeatmap(qupath).buildMenu());

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
                    viewMenu, compareMenu, referenceMenu, citationMenu, quizMenu);

            qupath.getMenu("Extensions", true).getItems().add(atlas);
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
}
