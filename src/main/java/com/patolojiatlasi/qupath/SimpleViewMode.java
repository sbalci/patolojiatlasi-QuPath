package com.patolojiatlasi.qupath;

import java.util.LinkedHashMap;
import java.util.Map;
import javafx.scene.control.Menu;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.QuPathGUI;

/**
 * "Basit görünüm": hides QuPath's analysis-focused top-level menus (Analyze, Classify, Automate,
 * TMA — whichever exist) so learners see a reading-focused UI, and restores them on toggle-off.
 * Public-API only ({@link QuPathGUI#getMenu(String, boolean)} + Menu.setVisible) — deliberately
 * NOT the reflection-into-internals approach QuPath Edu uses for its Study mode (Yli-Hallila et
 * al., J Anat 2025, doi:10.1111/joa.14172), so it cannot break on private-API changes.
 * Session-only (no persistence); state kept per-JVM in this class.
 */
public final class SimpleViewMode {
    private static final Logger logger = LoggerFactory.getLogger(SimpleViewMode.class);
    private static final String[] HIDE_MENUS = {"Analyze", "Classify", "Automate", "TMA"};
    private static final Map<Menu, Boolean> priorVisibility = new LinkedHashMap<>();

    private SimpleViewMode() {}

    /** Enable/disable simple view. Idempotent; FX thread. */
    public static void apply(QuPathGUI qupath, boolean enabled) {
        if (qupath == null) return;
        if (enabled) {
            if (!priorVisibility.isEmpty()) return;             // already applied
            for (String name : HIDE_MENUS) {
                try {
                    Menu m = qupath.getMenu(name, false);       // null when absent in this QuPath
                    if (m != null) {
                        priorVisibility.put(m, m.isVisible());
                        m.setVisible(false);
                    }
                } catch (Exception ex) {
                    logger.debug("Basit görünüm: '{}' menüsü gizlenemedi: {}", name, ex.getMessage());
                }
            }
        } else {
            priorVisibility.forEach((m, was) -> {
                try { m.setVisible(was); } catch (Exception ignore) {}
            });
            priorVisibility.clear();
        }
    }
}
