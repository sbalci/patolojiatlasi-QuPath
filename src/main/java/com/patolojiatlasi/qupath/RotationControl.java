package com.patolojiatlasi.qupath;

import java.util.Locale;

import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.viewer.QuPathViewer;

/**
 * A small floating slider that rotates the active viewer by an arbitrary angle (not just 90°).
 * QuPath rotation is view-only — it doesn't alter pixels. The slider stays in sync with the active
 * viewer: dragging it rotates that viewer, and rotating the viewer elsewhere moves the slider.
 */
public final class RotationControl {

    private final QuPathGUI qupath;

    private Stage stage;
    private Slider slider;
    private Label valueLabel;

    private boolean syncing;                 // guards the slider ↔ viewer feedback loop
    private QuPathViewer boundViewer;
    private final ChangeListener<Number> viewerRotationListener =
            (obs, was, now) -> setSliderDegrees(Math.toDegrees(now.doubleValue()));

    public RotationControl(QuPathGUI qupath) {
        this.qupath = qupath;
    }

    /** Show (or focus) the single rotation window, synced to the current viewer. */
    public void show() {
        if (stage == null)
            stage = build();
        rebind();
        stage.show();
        stage.toFront();
    }

    private Stage build() {
        slider = new Slider(-180, 180, 0);
        slider.setMajorTickUnit(45);
        slider.setMinorTickCount(8);
        slider.setShowTickMarks(true);
        slider.setShowTickLabels(true);
        slider.setBlockIncrement(1);
        slider.setPrefWidth(300);
        slider.valueProperty().addListener((obs, was, now) -> {
            applyToViewer(now.doubleValue());
            updateLabel(now.doubleValue());
        });

        valueLabel = new Label("0°");
        valueLabel.setMinWidth(44);
        valueLabel.setAlignment(Pos.CENTER_RIGHT);

        Button minus = nudge("−1°", -1);
        Button plus = nudge("+1°", +1);
        Button minus90 = nudge("−90°", -90);
        Button plus90 = nudge("+90°", +90);
        Button reset = new Button("Sıfırla");
        reset.setOnAction(e -> slider.setValue(0));

        HBox buttons = new HBox(6, minus90, minus, reset, plus, plus90, valueLabel);
        buttons.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(8, slider, buttons);
        root.setPadding(new Insets(12));

        // Keep the slider pointed at whichever viewer is active.
        qupath.viewerProperty().addListener((obs, was, now) -> rebind());

        Stage s = new Stage();
        s.setTitle("Görüntüyü döndür");
        s.setResizable(false);
        s.setScene(new Scene(root));
        return s;
    }

    private Button nudge(String text, double delta) {
        Button b = new Button(text);
        b.setOnAction(e -> slider.setValue(clamp(slider.getValue() + delta)));
        return b;
    }

    /** Rotate the current viewer to the given degrees (no-op if there's no viewer). */
    private void applyToViewer(double degrees) {
        if (syncing)
            return;
        QuPathViewer v = qupath.getViewer();
        if (v == null)
            return;
        syncing = true;
        try {
            v.setRotation(Math.toRadians(degrees));
        } finally {
            syncing = false;
        }
    }

    /** Point at the current active viewer: move our rotation listener to it and read its angle. */
    private void rebind() {
        QuPathViewer v = qupath.getViewer();
        if (v == boundViewer)
            return;
        if (boundViewer != null)
            boundViewer.rotationProperty().removeListener(viewerRotationListener);
        boundViewer = v;
        if (boundViewer != null) {
            boundViewer.rotationProperty().addListener(viewerRotationListener);
            setSliderDegrees(Math.toDegrees(boundViewer.getRotation()));
        }
    }

    private void setSliderDegrees(double degrees) {
        if (syncing)
            return;
        syncing = true;
        try {
            slider.setValue(clamp(degrees));
            updateLabel(degrees);
        } finally {
            syncing = false;
        }
    }

    private void updateLabel(double degrees) {
        valueLabel.setText(String.format(Locale.US, "%.0f°", degrees));
    }

    private static double clamp(double d) {
        return d < -180 ? -180 : (d > 180 ? 180 : d);
    }
}
