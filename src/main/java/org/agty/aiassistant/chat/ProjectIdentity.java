package org.agty.aiassistant.chat;

import com.intellij.openapi.components.*;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.*;

/** Stable local project identity used instead of the absolute project path. */
@Service(Service.Level.PROJECT)
@State(name = "AgtyAiAssistantProjectIdentity", storages = @Storage(value = StoragePathMacros.WORKSPACE_FILE, roamingType = RoamingType.DISABLED))
public final class ProjectIdentity implements PersistentStateComponent<ProjectIdentity.Data>, Disposable {
    private record ActiveIdentity(String id, String directory) { }
    private static final Map<ProjectIdentity, ActiveIdentity> ACTIVE = new WeakHashMap<>();
    public static final class Data {
        public String id = "";
        public String lastKnownDirectory = "";
        public List<String> legacyDirectories = new ArrayList<>();
    }

    private Data data = new Data();

    public static ProjectIdentity getInstance(Project project) {
        return project.getService(ProjectIdentity.class);
    }

    public String stableId(Project project) {
        String directory = canonicalDirectory(project);
        synchronized (ACTIVE) {
            if (data.id == null || data.id.isBlank()) data.id = UUID.randomUUID().toString();
            if (data.legacyDirectories == null) data.legacyDirectories = new ArrayList<>();
            // workspace.xml can be copied with a project. Its UUID must not give a second
            // directory access to the first project's prompts, contexts, rights and threads.
            boolean claimedElsewhere = ACTIVE.entrySet().stream().anyMatch(entry -> entry.getKey() != this
                    && entry.getValue().id.equals(data.id) && !entry.getValue().directory.equals(directory));
            if (claimedElsewhere || previousDirectoryStillExists(directory)) {
                data.id = UUID.randomUUID().toString();
                data.legacyDirectories.clear();
                data.lastKnownDirectory = directory;
            }
            if (!directory.isBlank() && !directory.equals(data.lastKnownDirectory)) {
                if (data.lastKnownDirectory != null && !data.lastKnownDirectory.isBlank() && !data.legacyDirectories.contains(data.lastKnownDirectory))
                    data.legacyDirectories.add(data.lastKnownDirectory);
                data.lastKnownDirectory = directory;
            }
            ACTIVE.put(this, new ActiveIdentity(data.id, directory));
            return data.id;
        }
    }

    private boolean previousDirectoryStillExists(String current) {
        String previous = data.lastKnownDirectory;
        if (previous == null || previous.isBlank() || previous.equals(current) || previous.equals("default-project")) return false;
        try {
            Path source = Path.of(previous);
            if (source.toRealPath().toString().equals(current)) return false;
            return true;
        } catch (java.nio.file.NoSuchFileException missing) { return false; }
        catch (java.io.IOException | RuntimeException uncertain) {
            // A denied or temporarily unavailable source is not evidence of a move.
            try { return !Files.notExists(Path.of(previous)); } catch (RuntimeException invalid) { return true; }
        }
    }

    public List<String> migrationCandidates(Project project) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        String current = canonicalDirectory(project);
        if (!current.isBlank()) candidates.add(current);
        if (data.lastKnownDirectory != null && !data.lastKnownDirectory.isBlank()) candidates.add(data.lastKnownDirectory);
        if (data.legacyDirectories != null) candidates.addAll(data.legacyDirectories);
        return new ArrayList<>(candidates);
    }

    static String canonicalDirectory(Project project) {
        String directory = project.getBasePath();
        if (directory == null || directory.isBlank()) return "default-project";
        try { return Path.of(directory).toRealPath().toString(); }
        catch (java.io.IOException ignored) { return Path.of(directory).toAbsolutePath().normalize().toString(); }
    }

    @Override public @NotNull Data getState() { return data; }
    @Override public void loadState(@NotNull Data state) { data = state; }
    @Override public void dispose() { synchronized (ACTIVE) { ACTIVE.remove(this); } }
}
