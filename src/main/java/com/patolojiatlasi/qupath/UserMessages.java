package com.patolojiatlasi.qupath;

import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.stage.Window;

/**
 * User-facing error dialogs in one consistent shape for readers who are not computer specialists:
 * <b>what happened</b> → <b>what to do</b>, with the raw Java exception text tucked into a collapsed
 * "Ayrıntıları göster" pane (still there for a screenshot to the maintainer, never the headline).
 *
 * <p>Must be called on the JavaFX application thread.
 */
final class UserMessages {

    /** Next steps for a slide that could not be streamed (the common novice failure: no network / firewall). */
    static final String NETWORK_ADVICE =
            "Yapabilecekleriniz:\n"
            + "  1. İnternet bağlantınızı kontrol edin.\n"
            + "  2. Kurum ağındaysanız güvenlik duvarı patolojiatlasi.com adresini engelliyor olabilir; "
            + "bilgi işlem birimine sorun veya başka bir ağdan deneyin.\n"
            + "  3. Birkaç dakika sonra yeniden deneyin.\n"
            + "  4. Sorun sürerse bu pencerenin ekran görüntüsünü (\"Ayrıntıları göster\" açıkken) gönderin.";

    private UserMessages() {}

    /** Short technical description of {@code t} (class + message), for the collapsed pane or a log line. */
    static String technical(Throwable t) {
        if (t == null) return "";
        String msg = t.getMessage();
        return t.getClass().getSimpleName() + (msg == null || msg.isBlank() ? "" : ": " + msg);
    }

    /**
     * Shows a modal error/warning dialog.
     *
     * @param owner       owner window (may be null)
     * @param type        {@link Alert.AlertType#ERROR} or {@link Alert.AlertType#WARNING}
     * @param whatHappened one plain sentence, e.g. "Slayt açılamadı: Gastrit (H&E)"
     * @param whatToDo    numbered next steps in plain Turkish
     * @param cause       the exception (shown collapsed); may be null
     */
    static void show(Window owner, Alert.AlertType type, String whatHappened, String whatToDo, Throwable cause) {
        Alert alert = new Alert(type);
        alert.setTitle("Patoloji Atlası");
        alert.setHeaderText(whatHappened);
        alert.setContentText(whatToDo);
        alert.getDialogPane().setPrefWidth(540);
        if (cause != null) {
            TextArea area = new TextArea(technical(cause));
            area.setEditable(false);
            area.setWrapText(true);
            area.setPrefRowCount(4);
            GridPane.setVgrow(area, Priority.ALWAYS);
            GridPane.setHgrow(area, Priority.ALWAYS);
            GridPane box = new GridPane();
            box.setMaxWidth(Double.MAX_VALUE);
            box.add(new Label("Teknik ayrıntı (destek için):"), 0, 0);
            box.add(area, 0, 1);
            alert.getDialogPane().setExpandableContent(box);
        }
        if (owner != null)
            alert.initOwner(owner);
        alert.showAndWait();
    }
}
