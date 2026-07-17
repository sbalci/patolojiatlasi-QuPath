package com.patolojiatlasi.qupath;

import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;

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
            Menu menu = qupath.getMenu("Extensions", true);
            MenuItem item = new MenuItem("Browse Patoloji Atlası...");
            item.setOnAction(e -> AtlasBrowser.show(qupath));
            menu.getItems().add(item);
            // Arbitrary view rotation (not just 90°/flips) for the active viewer.
            RotationControl rotation = new RotationControl(qupath);
            MenuItem rotationItem = new MenuItem("Görüntüyü döndür…");
            rotationItem.setOnAction(e -> rotation.show());
            menu.getItems().add(rotationItem);
            // Focus (dwell) heatmap — tracks where the reader looks; optional persistence.
            menu.getItems().add(new FocusHeatmap(qupath).buildMenu());
            // Self-study quiz: take or author a quiz on atlas slides.
            MenuItem quizTakeItem = new MenuItem("Sınav/quiz çöz…");
            quizTakeItem.setOnAction(e -> com.patolojiatlasi.qupath.quiz.QuizRunnerWindow.show(qupath));
            MenuItem quizAuthorItem = new MenuItem("Sınav/quiz hazırla…");
            quizAuthorItem.setOnAction(e -> com.patolojiatlasi.qupath.quiz.QuizAuthorWindow.show(qupath));
            menu.getItems().addAll(quizTakeItem, quizAuthorItem);
            logger.info("Patoloji Atlası extension installed");
        } catch (Exception e) {
            logger.error("Error installing Patoloji Atlası extension: {}", e.getMessage(), e);
        }
    }
}
