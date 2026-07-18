package com.patolojiatlasi.qupath;

import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.patolojiatlasi.qupath.focus.FocusHeatmap;

import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;

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

            // Quiz group — self-study quiz: take / author.
            MenuItem quizTakeItem = new MenuItem("Çöz…");
            quizTakeItem.setOnAction(e -> com.patolojiatlasi.qupath.quiz.QuizRunnerWindow.show(qupath));
            MenuItem quizAuthorItem = new MenuItem("Hazırla…");
            quizAuthorItem.setOnAction(e -> com.patolojiatlasi.qupath.quiz.QuizAuthorWindow.show(qupath));
            Menu quizMenu = new Menu("Sınav / Quiz");
            quizMenu.getItems().addAll(quizTakeItem, quizAuthorItem);

            atlas.getItems().addAll(
                    browseItem,
                    new SeparatorMenuItem(),
                    viewMenu, compareMenu, quizMenu);

            qupath.getMenu("Extensions", true).getItems().add(atlas);
            logger.info("Patoloji Atlası extension installed");
        } catch (Exception e) {
            logger.error("Error installing Patoloji Atlası extension: {}", e.getMessage(), e);
        }
    }
}
