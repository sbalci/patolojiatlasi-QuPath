package com.patolojiatlasi.qupath.autoview;

import java.util.Locale;

import com.patolojiatlasi.qupath.focus.FocusHeatmap;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Slider;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.StringConverter;

import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.prefs.PathPrefs;

/**
 * The "Otomatik tarama" control window: a small floating panel that sweeps the active viewer in a
 * serpentine at an adjustable speed and direction.
 * <p>
 * Structurally a sibling of {@code RotationControl} — build the {@link Stage} once, show/focus it on
 * demand — with {@link AutoviewSweep} doing the actual work.
 */
public final class AutoviewControl {

    /**
     * Fraction of a field that must be tissue for it to be worth visiting. Deliberately low: a
     * false positive costs a little sweep time, a false negative skips real tissue. The user-facing
     * knob is {@link #PREF_SENSITIVITY}, which moves the mask thresholds rather than this.
     */
    private static final double TISSUE_THRESHOLD = 0.01;

    private static final double MIN_FOV_PER_SEC = 0.05;   // 20 s per field
    private static final double MAX_FOV_PER_SEC = 1.0;    // 1 s per field

    // These MUST be static final. PathPrefs.createPersistentPreference returns a NEW property each
    // call, so building them inside build() would yield duplicate properties bound to one key --
    // the second window would silently stop tracking the first.
    static final DoubleProperty PREF_SPEED =
            PathPrefs.createPersistentPreference("atlas.autoview.fovPerSec", 0.25);
    static final ObjectProperty<AutoviewPath.Axis> PREF_AXIS =
            PathPrefs.createPersistentPreference("atlas.autoview.axis",
                    AutoviewPath.Axis.ROWS, AutoviewPath.Axis.class);
    static final ObjectProperty<AutoviewPath.Corner> PREF_CORNER =
            PathPrefs.createPersistentPreference("atlas.autoview.corner",
                    AutoviewPath.Corner.TOP_LEFT, AutoviewPath.Corner.class);
    static final BooleanProperty PREF_SKIP_EMPTY =
            PathPrefs.createPersistentPreference("atlas.autoview.skipEmpty", true);
    static final DoubleProperty PREF_SENSITIVITY =
            PathPrefs.createPersistentPreference("atlas.autoview.tissueSensitivity", 0.5);

    private final QuPathGUI qupath;
    private final AutoviewSweep sweep;

    private Stage stage;
    private Button startButton;
    private Button pauseButton;
    private Button stopButton;
    private ComboBox<AutoviewPath.Axis> axisBox;
    private ComboBox<AutoviewPath.Corner> cornerBox;
    private CheckBox skipEmptyBox;
    private Slider sensitivitySlider;
    private Label sensitivityLabel;
    private Label fieldLabel;

    public AutoviewControl(QuPathGUI qupath, FocusHeatmap focusHeatmap) {
        this.qupath = qupath;
        this.sweep = new AutoviewSweep(qupath, focusHeatmap);
        this.sweep.setOnStateChanged(this::refreshControls);
    }

    /** Show (or focus) the single autoview window. */
    public void show() {
        if (stage == null)
            stage = build();
        refreshControls();
        stage.show();
        stage.toFront();
    }

    private Stage build() {
        Slider speed = new Slider(MIN_FOV_PER_SEC, MAX_FOV_PER_SEC, clampSpeed(PREF_SPEED.get()));
        speed.setPrefWidth(240);
        Label speedLabel = new Label();
        speedLabel.setMinWidth(150);
        speed.valueProperty().addListener((obs, was, now) -> {
            double v = now.doubleValue();
            PREF_SPEED.set(v);
            sweep.setFovPerSec(v);
            speedLabel.setText(speedText(v));
        });
        speedLabel.setText(speedText(speed.getValue()));

        axisBox = new ComboBox<>();
        axisBox.getItems().addAll(AutoviewPath.Axis.values());
        axisBox.setConverter(converter(a -> a == AutoviewPath.Axis.ROWS
                ? "Satırlar (yatay)" : "Sütunlar (dikey)"));
        axisBox.getSelectionModel().select(PREF_AXIS.get());
        axisBox.valueProperty().addListener((obs, was, now) -> {
            if (now != null)
                PREF_AXIS.set(now);
        });

        cornerBox = new ComboBox<>();
        cornerBox.getItems().addAll(AutoviewPath.Corner.values());
        cornerBox.setConverter(converter(AutoviewControl::cornerText));
        cornerBox.getSelectionModel().select(PREF_CORNER.get());
        cornerBox.valueProperty().addListener((obs, was, now) -> {
            if (now != null)
                PREF_CORNER.set(now);
        });

        skipEmptyBox = new CheckBox("Boş alanları atla");
        skipEmptyBox.setSelected(PREF_SKIP_EMPTY.get());
        skipEmptyBox.selectedProperty().addListener((obs, was, now) -> {
            PREF_SKIP_EMPTY.set(now);
            refreshControls();
        });

        sensitivitySlider = new Slider(0, 1, clamp01(PREF_SENSITIVITY.get()));
        sensitivitySlider.setPrefWidth(160);
        sensitivityLabel = new Label();
        sensitivityLabel.setMinWidth(40);
        sensitivitySlider.valueProperty().addListener((obs, was, now) -> {
            PREF_SENSITIVITY.set(now.doubleValue());
            sensitivityLabel.setText(String.format(Locale.US, "%.2f", now.doubleValue()));
        });
        sensitivityLabel.setText(String.format(Locale.US, "%.2f", sensitivitySlider.getValue()));

        GridPane form = new GridPane();
        form.setHgap(8);
        form.setVgap(8);
        form.addRow(0, new Label("Hız:"), speed, speedLabel);
        form.addRow(1, new Label("Yön:"), axisBox);
        form.addRow(2, new Label("Başlangıç:"), cornerBox);
        form.addRow(3, skipEmptyBox, new HBox(8, sensitivitySlider, sensitivityLabel));

        startButton = new Button("Başlat");
        startButton.setOnAction(e -> onStart());
        pauseButton = new Button("Duraklat");
        pauseButton.setOnAction(e -> onPauseResume());
        stopButton = new Button("Durdur");
        stopButton.setOnAction(e -> sweep.stop());

        HBox buttons = new HBox(8, startButton, pauseButton, stopButton);
        buttons.setAlignment(Pos.CENTER_LEFT);

        ProgressBar bar = new ProgressBar(0);
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.progressProperty().bind(sweep.progressProperty());
        HBox.setHgrow(bar, Priority.ALWAYS);

        fieldLabel = new Label("");
        fieldLabel.setMinWidth(110);
        sweep.fieldIndexProperty().addListener((obs, was, now) -> refreshFieldLabel());
        sweep.fieldCountProperty().addListener((obs, was, now) -> refreshFieldLabel());

        Label statusLabel = new Label();
        statusLabel.textProperty().bind(sweep.statusProperty());

        VBox root = new VBox(10, form, buttons, new HBox(8, bar, fieldLabel), statusLabel);
        root.setPadding(new Insets(12));

        Stage s = new Stage();
        s.setTitle("Otomatik tarama");
        s.setResizable(false);
        s.setScene(new Scene(root));
        // Closing the window must never leave a sweep running against a viewer nobody is watching.
        s.setOnHidden(e -> sweep.stop());
        return s;
    }

    private void onStart() {
        AutoviewSweep.Settings settings = new AutoviewSweep.Settings(
                clampSpeed(PREF_SPEED.get()),
                axisBox.getValue() == null ? AutoviewPath.Axis.ROWS : axisBox.getValue(),
                cornerBox.getValue() == null ? AutoviewPath.Corner.TOP_LEFT : cornerBox.getValue(),
                skipEmptyBox.isSelected(),
                TISSUE_THRESHOLD,
                clamp01(PREF_SENSITIVITY.get()));

        String refusal = sweep.start(settings);
        if (refusal != null)
            info(refusal);
        refreshControls();
    }

    private void onPauseResume() {
        if (sweep.isPaused())
            sweep.resume();
        else
            sweep.pause();
        refreshControls();
    }

    private void refreshControls() {
        if (startButton == null)
            return;
        boolean running = sweep.isRunning();
        startButton.setDisable(running);
        pauseButton.setDisable(!running);
        pauseButton.setText(sweep.isPaused() ? "Devam et" : "Duraklat");
        stopButton.setDisable(!running);

        // Changing these mid-sweep would need a full replan, so they are settled before Başlat.
        axisBox.setDisable(running);
        cornerBox.setDisable(running);
        skipEmptyBox.setDisable(running);
        sensitivitySlider.setDisable(running || !skipEmptyBox.isSelected());
        sensitivityLabel.setDisable(running || !skipEmptyBox.isSelected());
        refreshFieldLabel();
    }

    private void refreshFieldLabel() {
        if (fieldLabel == null)
            return;
        int total = sweep.fieldCountProperty().get();
        if (total <= 0) {
            fieldLabel.setText("");
            return;
        }
        fieldLabel.setText(String.format(Locale.US, "alan %d / %d",
                sweep.fieldIndexProperty().get(), total));
    }

    private void info(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, message);
        alert.setHeaderText(null);
        alert.setTitle("Otomatik tarama");
        if (qupath.getStage() != null)
            alert.initOwner(qupath.getStage());
        alert.showAndWait();
    }

    /**
     * Speed reads in fields of view per second, shown inverted as seconds per field — which is how a
     * reader thinks about screening. Deliberately not µm/s: atlas slides open uncalibrated, so a
     * physical unit here would be a fabrication.
     */
    private static String speedText(double fovPerSec) {
        return String.format(Locale.US, "%.2f alan/sn  (%.0f sn/alan)", fovPerSec, 1.0 / fovPerSec);
    }

    private static String cornerText(AutoviewPath.Corner corner) {
        switch (corner) {
            case TOP_LEFT:
                return "Sol üst";
            case TOP_RIGHT:
                return "Sağ üst";
            case BOTTOM_LEFT:
                return "Sol alt";
            case BOTTOM_RIGHT:
                return "Sağ alt";
            default:
                return corner.name();
        }
    }

    private static <T> StringConverter<T> converter(java.util.function.Function<T, String> toText) {
        return new StringConverter<T>() {
            @Override
            public String toString(T value) {
                return value == null ? "" : toText.apply(value);
            }

            @Override
            public T fromString(String text) {
                return null;   // display-only combo
            }
        };
    }

    private static double clampSpeed(double v) {
        if (Double.isNaN(v))
            return 0.25;
        return v < MIN_FOV_PER_SEC ? MIN_FOV_PER_SEC : (v > MAX_FOV_PER_SEC ? MAX_FOV_PER_SEC : v);
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v))
            return 0.5;
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }
}
