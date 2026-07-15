# Curated-project builder — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a user select several atlas images into a persistent basket and turn them into a QuPath project — either a new project on disk or added to the current one — so the extension can prepare slide sets for courses, seminars, and exams.

**Architecture:** A UI-free `AtlasProjectService` owns the per-image add and the batch build (dedup, partial-failure collection); the existing `AtlasBrowser` gains a persistent selection basket + "Add to selection" affordances + a "Create project…" button; a modal `ProjectBuilderDialog` reviews the basket and runs the build on a background thread. `AtlasCase` gains value equality by DZI URL so the basket dedups by slide.

**Tech Stack:** Java 21, JavaFX (controls provided by QuPath at runtime), QuPath 0.6 API (`qupath-core`, `qupath-gui-fx`, both `compileOnly`), JUnit 5, Gradle.

## Global Constraints

- **QuPath API floor: 0.6.0.** Only use APIs verified present in 0.6.0 (see the verified-API list below). Java 21 source/target.
- **No new bundled dependencies.** QuPath + JavaFX stay `compileOnly` (provided at runtime); nothing new is packaged into the jar. Use only JDK + JavaFX + QuPath types.
- **Match existing patterns.** Background work runs on a `Thread` with `setDaemon(true)`, UI updates via `Platform.runLater`; dialogs use raw `javafx.scene.control.Alert` (this repo does not use qupath-fx `Dialogs` and has no lint forbidding `Alert`).
- **UTF-8 source.** Turkish string literals are allowed; `compileJava` already pins UTF-8.
- **No behavior change** to the no-project ("read-only viewer") branch of `openSelected()`.
- **Verified QuPath 0.6 API** (do not use variants outside this list):
  - `qupath.lib.projects.Projects.createProject(File, Class<T>) : Project<T>`
  - `Project.addImage(ServerBuilder<T>) : ProjectImageEntry<T>` throws `IOException`; `Project.removeImage(ProjectImageEntry<?>, boolean)`; `Project.syncChanges()`; `Project.getImageList()`
  - `ProjectImageEntry.getServerBuilder().getURIs() : Collection<URI>`; `ProjectImageEntry.setImageName(String)`; `ProjectImageEntry.saveImageData(ImageData)`
  - `QuPathGUI.getProject()`, `setProject(Project<BufferedImage>)`, `refreshProject()`, `openImageEntry(ProjectImageEntry<BufferedImage>)`, `getViewer()`
- **Build/verify commands use `--offline`** (all deps are already in the local Gradle cache; avoids reaching SciJava).

---

### Task 1: `AtlasCase` value equality by DZI URL

Gives the selection basket (`LinkedHashSet<AtlasCase>`) correct dedup: the same slide added twice collapses to one entry. DZI URL is unique per stain of a case.

**Files:**
- Modify: `src/main/java/com/patolojiatlasi/qupath/AtlasCase.java`
- Test: `src/test/java/com/patolojiatlasi/qupath/AtlasCaseTest.java` (create)

**Interfaces:**
- Consumes: nothing.
- Produces: `AtlasCase.equals`/`hashCode` keyed on `dziUrl`; a private test factory `sample(String dziUrl)`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/patolojiatlasi/qupath/AtlasCaseTest.java`:

```java
package com.patolojiatlasi.qupath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.LinkedHashSet;

import org.junit.jupiter.api.Test;

class AtlasCaseTest {

    private static AtlasCase sample(String dziUrl) {
        return new AtlasCase("repo", "stain", "HE", "Title EN", "Başlık TR",
                "Colon", "GI", "published", dziUrl, "");
    }

    @Test
    void equalWhenSameDziUrl() {
        AtlasCase a = sample("https://images.patolojiatlasi.com/case1/HE.dzi");
        AtlasCase b = sample("https://images.patolojiatlasi.com/case1/HE.dzi");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void notEqualWhenDifferentDziUrl() {
        AtlasCase a = sample("https://images.patolojiatlasi.com/case1/HE.dzi");
        AtlasCase b = sample("https://images.patolojiatlasi.com/case2/HE.dzi");
        assertNotEquals(a, b);
    }

    @Test
    void basketDedupsBySlide() {
        LinkedHashSet<AtlasCase> basket = new LinkedHashSet<>();
        basket.add(sample("https://images.patolojiatlasi.com/case1/HE.dzi"));
        basket.add(sample("https://images.patolojiatlasi.com/case1/HE.dzi"));
        assertEquals(1, basket.size());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew --offline test --tests "com.patolojiatlasi.qupath.AtlasCaseTest"`
Expected: FAIL — `equalWhenSameDziUrl` and `basketDedupsBySlide` fail because `AtlasCase` still uses `Object` identity equality (two distinct instances are unequal).

- [ ] **Step 3: Write minimal implementation**

In `AtlasCase.java`, add these two methods just before the existing `toString()` (after the private helpers is fine; anywhere in the class body):

```java
    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof AtlasCase other))
            return false;
        return dziUrl.equals(other.dziUrl);
    }

    @Override
    public int hashCode() {
        return dziUrl.hashCode();
    }
```

(`dziUrl` is never null — the constructor runs it through `nz(...)`.)

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew --offline test --tests "com.patolojiatlasi.qupath.AtlasCaseTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/patolojiatlasi/qupath/AtlasCase.java \
        src/test/java/com/patolojiatlasi/qupath/AtlasCaseTest.java
git commit -m "feat: AtlasCase value equality by DZI URL for basket dedup"
```

---

### Task 2: `AtlasProjectService` (add logic, dedup, build results)

The UI-free service both flows use. Pure logic (`selectNew`, `BuildResult.summary`) is unit-tested; the image-add/build methods need a live DZI server + QuPath runtime and are verified manually later.

**Files:**
- Create: `src/main/java/com/patolojiatlasi/qupath/AtlasProjectService.java`
- Test: `src/test/java/com/patolojiatlasi/qupath/AtlasProjectServiceTest.java`

**Interfaces:**
- Consumes: `AtlasCase.getDziURI()`, `AtlasCase.getTitle()`; `DziImageServer(URI)`; the verified QuPath API.
- Produces (later tasks rely on these exact signatures):
  - `static ProjectImageEntry<BufferedImage> addCaseToProject(Project<BufferedImage> project, AtlasCase c) throws IOException`
  - `static Set<URI> collectUris(Project<BufferedImage> project)`
  - `static List<AtlasCase> selectNew(List<AtlasCase> cases, Set<URI> existing)`
  - `static BuildOutcome createProject(File dir, List<AtlasCase> cases) throws IOException`
  - `static BuildResult addCasesToProject(Project<BufferedImage> project, List<AtlasCase> cases) throws IOException`
  - `record BuildOutcome(Project<BufferedImage> project, BuildResult result)`
  - `record BuildResult(int added, int skipped, List<Failure> failures)` with nested `record Failure(AtlasCase c, String reason)`, `boolean hasFailures()`, `String summary()`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/patolojiatlasi/qupath/AtlasProjectServiceTest.java`:

```java
package com.patolojiatlasi.qupath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class AtlasProjectServiceTest {

    private static AtlasCase sample(String dziUrl) {
        return new AtlasCase("repo", "stain", "HE", dziUrl, "",
                "Colon", "GI", "published", dziUrl, "");
    }

    @Test
    void selectNewSkipsPresentUrisAndKeepsOrder() {
        AtlasCase present = sample("https://x/HE.dzi");
        AtlasCase fresh1 = sample("https://x/CD3.dzi");
        AtlasCase fresh2 = sample("https://x/CD8.dzi");
        Set<URI> existing = Set.of(URI.create("https://x/HE.dzi"));

        List<AtlasCase> result = AtlasProjectService.selectNew(
                List.of(present, fresh1, fresh2), existing);

        assertEquals(List.of(fresh1, fresh2), result);
    }

    @Test
    void selectNewReturnsAllWhenNothingPresent() {
        AtlasCase a = sample("https://x/HE.dzi");
        AtlasCase b = sample("https://x/CD3.dzi");
        List<AtlasCase> result = AtlasProjectService.selectNew(List.of(a, b), Set.of());
        assertEquals(List.of(a, b), result);
    }

    @Test
    void buildResultSummaryReportsCounts() {
        AtlasProjectService.BuildResult r = new AtlasProjectService.BuildResult(
                3, 2, List.of(new AtlasProjectService.BuildResult.Failure(
                        sample("https://x/E.dzi"), "boom")));
        assertEquals("added 3, skipped 2, failed 1", r.summary());
        assertTrue(r.hasFailures());
    }

    @Test
    void buildResultNoFailures() {
        AtlasProjectService.BuildResult r =
                new AtlasProjectService.BuildResult(5, 0, List.of());
        assertEquals("added 5, skipped 0, failed 0", r.summary());
        assertFalse(r.hasFailures());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew --offline test --tests "com.patolojiatlasi.qupath.AtlasProjectServiceTest"`
Expected: FAIL — compilation error, `AtlasProjectService` does not exist yet.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/com/patolojiatlasi/qupath/AtlasProjectService.java`:

```java
package com.patolojiatlasi.qupath;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.patolojiatlasi.qupath.dzi.DziImageServer;

import qupath.lib.images.ImageData;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.projects.Projects;

/**
 * UI-free helper that turns {@link AtlasCase}s into QuPath project entries. Shared by the
 * single-open path in {@link AtlasBrowser} and the batch project builder. Adds are attempted
 * independently so one bad image never aborts a build.
 */
public final class AtlasProjectService {

    private static final Logger logger = LoggerFactory.getLogger(AtlasProjectService.class);

    private AtlasProjectService() {}

    /**
     * Add a single case to a project: build its DZI server, add it, name the entry, and save
     * its ImageData. Rolls the entry back if the save fails. Does NOT call syncChanges — the
     * caller syncs once for its batch.
     */
    public static ProjectImageEntry<BufferedImage> addCaseToProject(
            Project<BufferedImage> project, AtlasCase c) throws IOException {
        DziImageServer server = new DziImageServer(c.getDziURI());
        ProjectImageEntry<BufferedImage> entry = project.addImage(server.getBuilder());
        try {
            entry.setImageName(c.getTitle());
            try (ImageData<BufferedImage> imageData = new ImageData<>(server)) {
                entry.saveImageData(imageData);
            }
            return entry;
        } catch (Exception inner) {
            try {
                project.removeImage(entry, true);
            } catch (Exception rollbackEx) {
                logger.warn("Could not roll back partial project entry: {}", rollbackEx.getMessage());
            }
            throw new IOException("Failed to add \"" + c.getTitle() + "\": " + inner.getMessage(), inner);
        }
    }

    /** URIs already referenced by the project's image entries (used for dedup). */
    public static Set<URI> collectUris(Project<BufferedImage> project) {
        Set<URI> uris = new HashSet<>();
        for (ProjectImageEntry<BufferedImage> entry : project.getImageList()) {
            try {
                uris.addAll(entry.getServerBuilder().getURIs());
            } catch (Exception e) {
                logger.warn("Could not read URIs for entry {}: {}", entry.getImageName(), e.getMessage());
            }
        }
        return uris;
    }

    /** Cases whose DZI URI is not already in {@code existing}; input order preserved. Pure. */
    public static List<AtlasCase> selectNew(List<AtlasCase> cases, Set<URI> existing) {
        List<AtlasCase> fresh = new ArrayList<>();
        for (AtlasCase c : cases) {
            if (!existing.contains(c.getDziURI()))
                fresh.add(c);
        }
        return fresh;
    }

    /** Create the folder + a fresh project, add every case, then sync once. */
    public static BuildOutcome createProject(File dir, List<AtlasCase> cases) throws IOException {
        if (!dir.exists() && !dir.mkdirs())
            throw new IOException("Could not create project folder: " + dir);
        Project<BufferedImage> project = Projects.createProject(dir, BufferedImage.class);
        BuildResult result = addEach(project, cases, 0);
        project.syncChanges();
        return new BuildOutcome(project, result);
    }

    /** Add cases to an existing project, skipping any already present, then sync once. */
    public static BuildResult addCasesToProject(
            Project<BufferedImage> project, List<AtlasCase> cases) throws IOException {
        List<AtlasCase> fresh = selectNew(cases, collectUris(project));
        int skipped = cases.size() - fresh.size();
        BuildResult result = addEach(project, fresh, skipped);
        project.syncChanges();
        return result;
    }

    /** Shared add loop; collects per-case failures without aborting the batch. */
    private static BuildResult addEach(Project<BufferedImage> project, List<AtlasCase> cases, int skipped) {
        int added = 0;
        List<BuildResult.Failure> failures = new ArrayList<>();
        for (AtlasCase c : cases) {
            try {
                addCaseToProject(project, c);
                added++;
            } catch (Exception ex) {
                failures.add(new BuildResult.Failure(c, ex.getMessage()));
            }
        }
        return new BuildResult(added, skipped, failures);
    }

    /** A created project plus the outcome of adding its images. */
    public record BuildOutcome(Project<BufferedImage> project, BuildResult result) {}

    /** Counts + per-image failures from a build. */
    public record BuildResult(int added, int skipped, List<Failure> failures) {

        public record Failure(AtlasCase c, String reason) {}

        public boolean hasFailures() {
            return failures != null && !failures.isEmpty();
        }

        public String summary() {
            int failed = failures == null ? 0 : failures.size();
            return "added " + added + ", skipped " + skipped + ", failed " + failed;
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew --offline test --tests "com.patolojiatlasi.qupath.AtlasProjectServiceTest"`
Expected: PASS (4 tests). (`selectNew` and `BuildResult` use only `AtlasCase`, `URI`, and lists — no network or JavaFX.)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/patolojiatlasi/qupath/AtlasProjectService.java \
        src/test/java/com/patolojiatlasi/qupath/AtlasProjectServiceTest.java
git commit -m "feat: AtlasProjectService for adding atlas cases to projects"
```

---

### Task 3: Route single-open through the service (refactor `openSelected`)

Replace the inline add block in `AtlasBrowser.openSelected()` with `AtlasProjectService.addCaseToProject(...)`, so single-open and the builder share one implementation. No user-visible behavior change to either branch.

**Files:**
- Modify: `src/main/java/com/patolojiatlasi/qupath/AtlasBrowser.java` (the `openSelected()` background `Thread` body)

**Interfaces:**
- Consumes: `AtlasProjectService.addCaseToProject(project, c)` from Task 2.
- Produces: nothing new (internal refactor).

- [ ] **Step 1: Replace the Thread body in `openSelected()`**

Find the `Thread t = new Thread(() -> { ... }, "atlas-open-" + c.getReponame());` block inside `openSelected()` and replace the lambda body so it reads exactly:

```java
        Thread t = new Thread(() -> {
            try {
                Project<BufferedImage> project = qupath.getProject();
                if (project != null) {
                    ProjectImageEntry<BufferedImage> entry =
                            AtlasProjectService.addCaseToProject(project, c);
                    project.syncChanges();
                    Platform.runLater(() -> {
                        try {
                            qupath.refreshProject();
                        } catch (Throwable ignore) {
                            // API differences across versions: project is still updated on disk.
                        }
                        try {
                            qupath.openImageEntry(entry);
                        } catch (Throwable ex) {
                            logger.warn("Could not open project entry: {}", ex.getMessage());
                        }
                        done(c, "Added to project & opened: ");
                    });
                } else {
                    DziImageServer server = new DziImageServer(c.getDziURI());
                    ImageData<BufferedImage> imageData = new ImageData<>(server);
                    Platform.runLater(() -> {
                        try {
                            qupath.getViewer().setImageData(imageData);
                            done(c, "Opened (no project): ");
                        } catch (Exception ex) {
                            logger.error("Failed to display atlas case {}: {}", c.getReponame(), ex.getMessage(), ex);
                            fail(c, ex);
                        }
                    });
                }
            } catch (Exception ex) {
                logger.error("Failed to open atlas case {}: {}", c.getReponame(), ex.getMessage(), ex);
                Platform.runLater(() -> fail(c, ex));
            }
        }, "atlas-open-" + c.getReponame());
```

Note: `syncChanges()` now runs in `openSelected()` after `addCaseToProject`; a sync failure no longer triggers rollback of the (already-saved) entry — an acceptable, negligible semantic change. The imports `DziImageServer`, `ImageData`, `Project`, `ProjectImageEntry` are all still used, so no import changes.

- [ ] **Step 2: Compile**

Run: `./gradlew --offline compileJava`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Run the full test suite (nothing should regress)**

Run: `./gradlew --offline test`
Expected: PASS (Tasks 1–2 tests, 7 total).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/patolojiatlasi/qupath/AtlasBrowser.java
git commit -m "refactor: route single-open through AtlasProjectService.addCaseToProject"
```

**Manual verification (record result, do not fake):** in a running QuPath with a project open, double-click an atlas image → it is still added to the project and opened; with no project open, it still opens read-only.

---

### Task 4: Selection basket + "Add to selection" in the browser

Add the persistent basket, the "Add to selection" button, a tree context menu, and the live "N selected" count. The basket survives search/filter/refresh because it is independent of the tree and deduped by `AtlasCase` equality (Task 1).

**Files:**
- Modify: `src/main/java/com/patolojiatlasi/qupath/AtlasBrowser.java`

**Interfaces:**
- Consumes: `AtlasCase.equals/hashCode` (Task 1) via `LinkedHashSet`.
- Produces (Task 5 relies on these): the field `private final java.util.LinkedHashSet<AtlasCase> selection`; the method `private void updateSelectionCount()`; the `selectionCount` label.

- [ ] **Step 1: Add the imports**

In `AtlasBrowser.java`, add these imports (alphabetical, among the existing `javafx.scene.control.*` imports):

```java
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
```

- [ ] **Step 2: Add the basket fields**

Next to the other private fields (e.g. just after `private List<AtlasCase> allCases;`), add:

```java
    // Persistent selection basket, deduped by AtlasCase (DZI URL). Independent of the tree,
    // so it survives search/filter/refresh. Touched only on the JavaFX thread.
    private final java.util.LinkedHashSet<AtlasCase> selection = new java.util.LinkedHashSet<>();
    private final Label selectionCount = new Label("0 selected");
```

- [ ] **Step 3: Add the "Add to selection" button + count to the bottom bar and a tree context menu**

In `buildStage()`, replace the button/bar block (currently `openBtn` … through the `HBox bottom = ...` line) with:

```java
        Button openBtn = new Button("Open in QuPath");
        openBtn.setOnAction(e -> openSelected());
        Button addSelBtn = new Button("Add to selection");
        addSelBtn.setOnAction(e -> addToSelection());
        Button webBtn = new Button("Copy web link");
        webBtn.setOnAction(e -> copyWebLink());
        Button aboutBtn = new Button("About");
        aboutBtn.setTooltip(new javafx.scene.control.Tooltip("About this extension and the atlas websites"));
        aboutBtn.setOnAction(e -> showAbout());

        // Spacer pushes the About button to the far right of the bar.
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox bottom = new HBox(6, openBtn, addSelBtn, webBtn, selectionCount, status, spacer, aboutBtn);
        bottom.setPadding(new Insets(8));
```

Then add a context menu on the tree. Immediately after the existing `tree.getSelectionModel().selectedItemProperty().addListener(...)` line, add:

```java
        MenuItem addToSelectionItem = new MenuItem("Add to selection");
        addToSelectionItem.setOnAction(e -> addToSelection());
        tree.setContextMenu(new ContextMenu(addToSelectionItem));
```

- [ ] **Step 4: Add the `addToSelection` and `updateSelectionCount` methods**

Add these methods (e.g. right after `copyWebLink()`):

```java
    private void addToSelection() {
        AtlasCase c = getSelectedCase();
        if (c == null) {
            status.setText("Select a case first");
            return;
        }
        if (selection.add(c))
            status.setText("Added to selection: " + c.getTitle());
        else
            status.setText("Already in selection: " + c.getTitle());
        updateSelectionCount();
    }

    private void updateSelectionCount() {
        selectionCount.setText(selection.size() + " selected");
    }
```

- [ ] **Step 5: Compile**

Run: `./gradlew --offline compileJava`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/patolojiatlasi/qupath/AtlasBrowser.java
git commit -m "feat: persistent selection basket + Add to selection in atlas browser"
```

**Manual verification (record result):** open the browser, select an image, click **Add to selection** → count shows "1 selected"; search for another term (tree rebuilds), add a second image → count shows "2 selected" (selection persisted across the search); adding the same image twice does not increase the count.

---

### Task 5: `ProjectBuilderDialog` + "Create project…" wiring + README

The modal dialog that reviews the basket and runs the build (new project or add-to-current) on a background thread. Wired from a new "Create project…" button in the browser. Window-modal on the browser, so the browser's single-open cannot run concurrently; the in-flight build disables **Create** to prevent re-entry.

**Files:**
- Create: `src/main/java/com/patolojiatlasi/qupath/ProjectBuilderDialog.java`
- Modify: `src/main/java/com/patolojiatlasi/qupath/AtlasBrowser.java` (add `createProjectBtn`, wire it, toggle it in `updateSelectionCount`)
- Modify: `README.md`

**Interfaces:**
- Consumes: `AtlasProjectService.createProject`, `addCasesToProject`, `BuildOutcome`, `BuildResult` (Task 2); the browser's `selection` basket and `updateSelectionCount` (Task 4); `QuPathGUI` project API.
- Produces: `static void ProjectBuilderDialog.show(QuPathGUI qupath, Stage owner, LinkedHashSet<AtlasCase> basket, Runnable onSelectionChanged)`.

- [ ] **Step 1: Create the dialog class**

Create `src/main/java/com/patolojiatlasi/qupath/ProjectBuilderDialog.java`:

```java
package com.patolojiatlasi.qupath;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.gui.QuPathGUI;
import qupath.lib.projects.Project;

/**
 * Modal dialog to turn the browser's selection basket into a QuPath project — a new project on
 * disk, or added to the currently-open project. The build runs on a background daemon thread;
 * the dialog is window-modal on the browser so single-open cannot run concurrently.
 */
public class ProjectBuilderDialog {

    private static final Logger logger = LoggerFactory.getLogger(ProjectBuilderDialog.class);

    private final QuPathGUI qupath;
    private final Stage owner;
    private final LinkedHashSet<AtlasCase> basket;
    private final Runnable onSelectionChanged;

    private final ObservableList<AtlasCase> items;
    private final ListView<AtlasCase> listView;
    private final Label statusLabel = new Label();
    private final ProgressIndicator progress = new ProgressIndicator();

    private RadioButton newRadio;
    private RadioButton currentRadio;
    private TextField nameField;
    private Label locationLabel;
    private File location;
    private Button createBtn;
    private Stage stage;

    private ProjectBuilderDialog(QuPathGUI qupath, Stage owner,
            LinkedHashSet<AtlasCase> basket, Runnable onSelectionChanged) {
        this.qupath = qupath;
        this.owner = owner;
        this.basket = basket;
        this.onSelectionChanged = onSelectionChanged;
        this.items = FXCollections.observableArrayList(basket);
        this.listView = new ListView<>(items);
    }

    /** Build and show the modal dialog. Must be called on the JavaFX thread. */
    static void show(QuPathGUI qupath, Stage owner,
            LinkedHashSet<AtlasCase> basket, Runnable onSelectionChanged) {
        new ProjectBuilderDialog(qupath, owner, basket, onSelectionChanged).build().showAndWait();
    }

    private Stage build() {
        stage = new Stage();
        stage.initModality(Modality.WINDOW_MODAL);
        if (owner != null)
            stage.initOwner(owner);
        stage.setTitle("Create project from selection");

        listView.setPrefHeight(220);
        listView.setPlaceholder(new Label("No images selected"));

        Button removeBtn = new Button("Remove");
        removeBtn.setOnAction(e -> removeSelected());
        Button clearBtn = new Button("Clear all");
        clearBtn.setOnAction(e -> clearAll());
        HBox listButtons = new HBox(6, removeBtn, clearBtn);

        ToggleGroup group = new ToggleGroup();
        newRadio = new RadioButton("New project");
        newRadio.setToggleGroup(group);
        newRadio.setSelected(true);
        currentRadio = new RadioButton("Add to current project");
        currentRadio.setToggleGroup(group);
        boolean hasProject = qupath.getProject() != null;
        currentRadio.setDisable(!hasProject);
        if (!hasProject)
            currentRadio.setTooltip(new Tooltip("Open a project first to use this option"));

        nameField = new TextField();
        nameField.setPromptText("Project name");
        Button locationBtn = new Button("Choose location…");
        locationBtn.setOnAction(e -> chooseLocation());
        locationLabel = new Label("(no location chosen)");
        HBox locationRow = new HBox(6, locationBtn, locationLabel);
        VBox newBox = new VBox(6, new Label("Project name:"), nameField, locationRow);
        newBox.setPadding(new Insets(4, 0, 0, 20));
        newBox.disableProperty().bind(newRadio.selectedProperty().not());

        createBtn = new Button("Create");
        createBtn.setOnAction(e -> runBuild());
        Button cancelBtn = new Button("Cancel");
        cancelBtn.setOnAction(e -> stage.close());
        progress.setVisible(false);
        progress.setPrefSize(18, 18);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(6, statusLabel, progress, spacer, cancelBtn, createBtn);

        VBox rootBox = new VBox(10,
                new Label("Selected images:"), listView, listButtons,
                new Label("Target:"), newRadio, newBox, currentRadio,
                actions);
        rootBox.setPadding(new Insets(12));

        updateStatus();
        stage.setScene(new Scene(rootBox, 460, 540));
        return stage;
    }

    private void removeSelected() {
        AtlasCase c = listView.getSelectionModel().getSelectedItem();
        if (c == null)
            return;
        items.remove(c);
        basket.remove(c);
        onSelectionChanged.run();
        updateStatus();
    }

    private void clearAll() {
        items.clear();
        basket.clear();
        onSelectionChanged.run();
        updateStatus();
    }

    private void chooseLocation() {
        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle("Choose a parent folder for the new project");
        File dir = dc.showDialog(stage);
        if (dir != null) {
            location = dir;
            locationLabel.setText(dir.getAbsolutePath());
        }
    }

    private void updateStatus() {
        statusLabel.setText(items.size() + " image(s)");
        if (createBtn != null)
            createBtn.setDisable(items.isEmpty());
    }

    private void runBuild() {
        List<AtlasCase> cases = new ArrayList<>(items);
        if (cases.isEmpty()) {
            statusLabel.setText("No images selected");
            return;
        }
        boolean makeNew = newRadio.isSelected();
        final File projectDir;
        if (makeNew) {
            String name = nameField.getText() == null ? "" : nameField.getText().trim();
            if (name.isEmpty()) {
                statusLabel.setText("Enter a project name");
                return;
            }
            if (location == null) {
                statusLabel.setText("Choose a location");
                return;
            }
            File candidate = new File(location, name);
            String[] existing = candidate.list();
            if (candidate.exists() && existing != null && existing.length > 0) {
                statusLabel.setText("Folder exists and is not empty — choose another name");
                return;
            }
            projectDir = candidate;
        } else {
            if (qupath.getProject() == null) {
                statusLabel.setText("No project open");
                return;
            }
            projectDir = null;
        }

        createBtn.setDisable(true);
        progress.setVisible(true);
        statusLabel.setText("Building…");

        Thread t = new Thread(() -> {
            try {
                if (makeNew) {
                    AtlasProjectService.BuildOutcome outcome =
                            AtlasProjectService.createProject(projectDir, cases);
                    Platform.runLater(() -> {
                        try {
                            qupath.setProject(outcome.project());
                        } catch (Throwable ex) {
                            logger.warn("Could not open new project: {}", ex.getMessage());
                        }
                        finish(outcome.result());
                    });
                } else {
                    Project<BufferedImage> project = qupath.getProject();
                    AtlasProjectService.BuildResult result =
                            AtlasProjectService.addCasesToProject(project, cases);
                    Platform.runLater(() -> {
                        try {
                            qupath.refreshProject();
                        } catch (Throwable ignore) {
                            // project already updated on disk
                        }
                        finish(result);
                    });
                }
            } catch (Exception ex) {
                logger.error("Project build failed: {}", ex.getMessage(), ex);
                Platform.runLater(() -> {
                    progress.setVisible(false);
                    createBtn.setDisable(false);
                    statusLabel.setText("Failed: " + ex.getMessage());
                    new Alert(Alert.AlertType.ERROR,
                            "Could not build project\n\n" + ex.getMessage()).showAndWait();
                });
            }
        }, "atlas-project-build");
        t.setDaemon(true);
        t.start();
    }

    /** Runs on the FX thread after a successful build: clear the basket, report, close. */
    private void finish(AtlasProjectService.BuildResult result) {
        progress.setVisible(false);
        basket.clear();
        items.clear();
        onSelectionChanged.run();
        if (result.hasFailures()) {
            StringBuilder sb = new StringBuilder("Some images could not be added:\n\n");
            for (AtlasProjectService.BuildResult.Failure f : result.failures())
                sb.append("• ").append(f.c().getTitle()).append(" — ").append(f.reason()).append('\n');
            new Alert(Alert.AlertType.WARNING, sb.toString()).showAndWait();
        }
        statusLabel.setText("Done — " + result.summary());
        stage.close();
    }
}
```

- [ ] **Step 2: Wire the "Create project…" button in `AtlasBrowser`**

Add a field next to `selectionCount`:

```java
    private final Button createProjectBtn = new Button("Create project…");
```

In `buildStage()`, wire it just before the `HBox bottom = ...` line, and add it to the bar (place it after `selectionCount`). Replace the `HBox bottom = ...` line from Task 4 with:

```java
        createProjectBtn.setDisable(true);
        createProjectBtn.setOnAction(e ->
                ProjectBuilderDialog.show(qupath, stage, selection, this::updateSelectionCount));

        HBox bottom = new HBox(6, openBtn, addSelBtn, webBtn, selectionCount, createProjectBtn, status, spacer, aboutBtn);
```

(`Button` is already imported in `AtlasBrowser`.)

- [ ] **Step 3: Toggle the button in `updateSelectionCount`**

Replace the `updateSelectionCount()` method from Task 4 with:

```java
    private void updateSelectionCount() {
        selectionCount.setText(selection.size() + " selected");
        createProjectBtn.setDisable(selection.isEmpty());
    }
```

- [ ] **Step 4: Compile and run the full suite**

Run: `./gradlew --offline compileJava && ./gradlew --offline test`
Expected: `BUILD SUCCESSFUL`; tests PASS (7 total — no new automated tests; the dialog is JavaFX UI).

- [ ] **Step 5: Update the README**

In `README.md`, under `## What it does`, add a bullet after the "Double-click an image…" bullet:

```markdown
- **Curate a project from several images.** Use **Add to selection** (button or the tree
  right-click menu) to build up a set as you browse and search — the selection persists across
  searches and *Refresh list*. Then **Create project…** opens a dialog where you review the set
  and either create a **new project** on disk or **add the selection to the current project**.
  Because slides stream from URLs, the resulting project is tiny and portable — hand the project
  folder to students (they need this extension installed) to run a course, seminar, or exam set.
```

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/patolojiatlasi/qupath/ProjectBuilderDialog.java \
        src/main/java/com/patolojiatlasi/qupath/AtlasBrowser.java \
        README.md
git commit -m "feat: Create-project-from-selection dialog for atlas browser"
```

**Manual verification (record result):** with several images in the basket, click **Create project…**; (a) **New project** with a name + location creates the project, opens it in QuPath, and all entries stream open; (b) re-open the browser, add more images, choose **Add to current project** → new ones are added and any already present are skipped (status reports `added N, skipped M, failed K`); the basket clears after a successful build; **Remove**/**Clear all** prune the set; **Create** is disabled while a build is in flight.

---

## Self-Review

**1. Spec coverage:**
- Persistent basket surviving search/filter/refresh → Task 4 (basket independent of tree; Task 1 dedup). ✅
- Both targets (new + add-to-current) → Task 5 dialog radios + `createProject`/`addCasesToProject`. ✅
- Separate Create-project dialog with review/remove/clear → Task 5. ✅
- "N selected" count + Add-to-selection (button + context menu), double-click still opens → Task 4. ✅
- Reuse single-open per-image logic → Task 2 `addCaseToProject` + Task 3 refactor. ✅
- Dedup by DZI URL → Task 2 `collectUris`/`selectNew` (+ Task 1 for the basket). ✅
- Background thread + one-build-at-a-time → Task 5. **Correction (post-implementation):** the original plan claimed WINDOW_MODAL alone achieved spec §8's mutual exclusion "more simply" — that was WRONG. Modality only blocks *new* browser input while the dialog is open; it does not stop a single-open (`openSelected`) that was *already dispatched* on a background thread before the dialog opened, so a build could still race it on the shared `Project`. The final implementation restores spec §8's intent: `AtlasBrowser.openProjectBuilder()` gates opening the builder dialog on the existing `opening` flag (refuses while a single-open is in flight), and WINDOW_MODAL blocks the reverse (no single-open can start while a build runs). Gate + modality together give the "a build and a single open cannot run concurrently" guarantee. Caught by the final whole-branch review; fixed in commit 4c20f11. ✅
- Partial-failure reporting → Task 2 `addEach` + Task 5 `finish` summary/alert. ✅
- No new menu item → confirmed (all wiring in the browser window). ✅
- Testing split (service logic unit-tested; image-add + dialog manual) → Tasks 1–2 automated, Tasks 3–5 manual verification steps. ✅
- Deferred set-list export/exam layer → not in any task (correctly out of scope). ✅
- `AtlasCase` equals/hashCode by `dziUrl` → Task 1. ✅

**2. Placeholder scan:** No TBD/TODO; every code step shows complete code; test bodies are concrete. ✅

**3. Type consistency:** `BuildResult(int, int, List<Failure>)`, `Failure(AtlasCase, String)`, `BuildOutcome(Project<BufferedImage>, BuildResult)`, `summary()`, `hasFailures()`, `selectNew(List, Set)`, `addCaseToProject(Project, AtlasCase)`, `createProject(File, List)`, `addCasesToProject(Project, List)` — used identically in Tasks 2, 3, and 5. `selection` / `updateSelectionCount` / `selectionCount` / `createProjectBtn` consistent across Tasks 4–5. ✅

**4. Note on the `AtlasProjectServiceTest.sample` factory:** it passes `dziUrl` twice (once as `titleEN`, once as the real `dziUrl` argument) purely so each sample has a distinct title and URL; only the `dziUrl` (9th arg) matters for the assertions.
