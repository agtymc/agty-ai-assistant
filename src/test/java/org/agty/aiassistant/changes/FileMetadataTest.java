package org.agty.aiassistant.changes;

import com.sun.jna.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FileMetadataTest {
    @TempDir Path root;
    @Test void replacementPreservesOriginalOwnerGroupPermissionsAndAcl() throws Exception {
        Path source = root.resolve("source"), target = root.resolve("target");
        Files.writeString(source, "confidential"); Files.writeString(target, "replacement");
        if (Files.getFileStore(root).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(source, PosixFilePermissions.fromString("rw-------"));
            Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-r--r--"));
        }
        String expected = FileMetadata.fingerprint(source);
        FileMetadata.copyAccess(source, target);
        assertEquals(expected, FileMetadata.fingerprint(target));
        assertEquals("replacement", Files.readString(target));
        assertEquals("confidential", Files.readString(source));
    }
    @Test void extendedPosixAclSurvivesManualPatchAndPermissionEditsBlockUndo() throws Exception {
        Assumptions.assumeTrue(Platform.isLinux());
        Path file = root.resolve("file"); Files.writeString(file, "before\n");
        ByteBuffer acl = ByteBuffer.allocate(4 + 5 * 8).order(ByteOrder.LITTLE_ENDIAN); acl.putInt(2);
        for (int[] entry : new int[][]{{1, 6, -1}, {2, 4, 65534}, {4, 0, -1}, {16, 4, -1}, {32, 0, -1}})
            acl.putShort((short) entry[0]).putShort((short) entry[1]).putInt(entry[2]);
        assertEquals(0, NativeLibrary.getInstance("c").getFunction("lsetxattr").invokeInt(new Object[]{file.toString(), "system.posix_acl_access", acl.array(), new NativeLong(acl.capacity()), 0}));
        String initial = FileMetadata.fingerprint(file);
        var set = ChangeSet.fromUnifiedDiff("diff --git a/file b/file\n--- a/file\n+++ b/file\n@@ -1 +1 @@\n-before\n+after\n", Map.of("file", "before\n"));
        var applied = ManualPatch.apply(root, set, Set.of());
        assertEquals(initial, FileMetadata.fingerprint(file));
        assertEquals(0, NativeLibrary.getInstance("c").getFunction("lremovexattr").invokeInt(new Object[]{file.toString(), "system.posix_acl_access"}));
        assertNotEquals(initial, FileMetadata.fingerprint(file));
        assertThrows(java.io.IOException.class, applied::undo);
        assertEquals("after\n", Files.readString(file));
    }
    @Test void userExtendedAttributesAreNotLostDuringReplacement() throws Exception {
        Assumptions.assumeTrue(Platform.isLinux());
        Path source = root.resolve("source"), target = root.resolve("target");
        Files.writeString(source, "original"); Files.writeString(target, "replacement");
        var view = Files.getFileAttributeView(source, java.nio.file.attribute.UserDefinedFileAttributeView.class);
        view.write("agty-user-data", ByteBuffer.wrap("private metadata".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        FileMetadata.copyAccess(source, target);
        assertEquals(FileMetadata.fingerprint(source), FileMetadata.fingerprint(target));
        assertTrue(Files.getFileAttributeView(target, java.nio.file.attribute.UserDefinedFileAttributeView.class).list().contains("agty-user-data"));
    }
    @Test void extendedMacAclSurvivesPatchAndExportAndPermissionEditsInvalidateConsent() throws Exception {
        Assumptions.assumeTrue(Platform.isMac());
        Path file = root.resolve("file"), exported = root.resolve("export.md");
        Files.writeString(file, "before\n"); Files.writeString(exported, "old export\n");
        for (Path fixture : List.of(file, exported)) {
            Files.setPosixFilePermissions(fixture, PosixFilePermissions.fromString("rw-------"));
            String ordinary = FileMetadata.fingerprint(fixture);
            chmod("+a", "user:nobody allow read", fixture.toString());
            assertNotEquals(ordinary, FileMetadata.fingerprint(fixture), "Fixture must contain an actual extended ACL");
        }
        String originalAcl = FileMetadata.fingerprint(file), exportAcl = FileMetadata.fingerprint(exported);
        var set = ChangeSet.fromUnifiedDiff("diff --git a/file b/file\n--- a/file\n+++ b/file\n@@ -1 +1 @@\n-before\n+after\n", Map.of("file", "before\n"));
        var applied = ManualPatch.apply(root, set, Set.of());
        assertEquals(originalAcl, FileMetadata.fingerprint(file));
        applied.undo();
        assertEquals("before\n", Files.readString(file));
        assertEquals(originalAcl, FileMetadata.fingerprint(file));
        var second = ManualPatch.apply(root, set, Set.of());
        chmod("-N", file.toString());
        assertNotEquals(originalAcl, FileMetadata.fingerprint(file));
        assertThrows(java.io.IOException.class, second::undo);
        assertEquals("after\n", Files.readString(file));
        try (var export = new org.agty.aiassistant.security.SafeExport(exported)) {
            export.stage("new export\n"); export.commit();
        }
        assertEquals(exportAcl, FileMetadata.fingerprint(exported));
        assertEquals("new export\n", Files.readString(exported));
        try (var stale = new org.agty.aiassistant.security.SafeExport(exported)) {
            stale.stage("must not replace\n");
            chmod("-N", exported.toString());
            assertNotEquals(exportAcl, FileMetadata.fingerprint(exported));
            assertThrows(java.io.IOException.class, stale::commit);
        }
        assertEquals("new export\n", Files.readString(exported));
    }

    private static void chmod(String... arguments) throws Exception {
        var command = new ArrayList<>(List.of("/bin/chmod")); command.addAll(List.of(arguments));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS), "chmod fixture timed out");
            String output = new String(process.getInputStream().readNBytes(4096), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
    @Test void windowsNamedStreamsDisableReplacementWithoutDestroyingStreamData() throws Exception {
        Assumptions.assumeTrue(Platform.isWindows());
        Path file = root.resolve("file"); Files.writeString(file, "ordinary data");
        byte[] privateData = "private stream".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        windowsStream(file + ":private", privateData);
        assertThrows(java.io.IOException.class, () -> FileMetadata.capture(file));
        try (var snapshot = WorkspaceChanges.capture(root)) { snapshot.finish(); }
        assertArrayEquals(privateData, windowsStream(file + ":private", null));
        assertEquals("ordinary data", Files.readString(file));
    }

    /** IDEA disables java.io ADS paths. Create the real NTFS fixture through the OS, without weakening that setting. */
    private static byte[] windowsStream(String name, byte[] write) {
        var library = NativeLibrary.getInstance("kernel32");
        Pointer handle = library.getFunction("CreateFileW", Function.ALT_CONVENTION).invokePointer(new Object[]{
                new WString(name), write == null ? 0x80000000 : 0x40000000, 7, Pointer.NULL,
                write == null ? 3 : 2, 0x80, Pointer.NULL});
        int error = Native.getLastError();
        assertTrue(handle != null && Pointer.nativeValue(handle) != -1L, "CreateFileW failed: " + error);
        try {
            byte[] buffer = write == null ? new byte[1024] : write;
            var count = new com.sun.jna.ptr.IntByReference();
            int result = library.getFunction(write == null ? "ReadFile" : "WriteFile", Function.ALT_CONVENTION)
                    .invokeInt(new Object[]{handle, buffer, buffer.length, count, Pointer.NULL});
            error = Native.getLastError();
            assertNotEquals(0, result, "Stream I/O failed: " + error);
            if (write != null) assertEquals(write.length, count.getValue());
            return Arrays.copyOf(buffer, count.getValue());
        } finally {
            assertNotEquals(0, library.getFunction("CloseHandle", Function.ALT_CONVENTION).invokeInt(new Object[]{handle}));
        }
    }
}
