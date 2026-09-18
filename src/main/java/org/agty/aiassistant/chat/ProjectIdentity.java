package org.agty.aiassistant.chat;

import com.intellij.openapi.components.*;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import java.nio.file.Path;
import java.util.*;

/** Stable local project identity used instead of the absolute project path. */
@Service(Service.Level.PROJECT)
@State(name = "AgtyAiAssistantProjectIdentity", storages = @Storage(value = StoragePathMacros.WORKSPACE_FILE, roamingType = RoamingType.DISABLED))
public final class ProjectIdentity implements PersistentStateComponent<ProjectIdentity.Data> {
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
        if (data.id == null || data.id.isBlank()) data.id = UUID.randomUUID().toString();
        String directory = canonicalDirectory(project);
        if (!directory.isBlank() && !directory.equals(data.lastKnownDirectory)) {
            if (data.lastKnownDirectory != null && !data.lastKnownDirectory.isBlank() && !data.legacyDirectories.contains(data.lastKnownDirectory))
                data.legacyDirectories.add(data.lastKnownDirectory);
            data.lastKnownDirectory = directory;
        }
        return data.id;
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
}
