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
                logger.warn("Failed to add \"{}\" to project: {}", c.getTitle(), ex.getMessage(), ex);
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
