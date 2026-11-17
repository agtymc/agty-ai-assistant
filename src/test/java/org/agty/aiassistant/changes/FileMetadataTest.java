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
    @Test void windowsNamedStreamsDisableReplacementWithoutDestroyingStreamData() throws Exception {
        Assumptions.assumeTrue(Platform.isWindows());
        Path file = root.resolve("file"); Files.writeString(file, "ordinary data");
        try (var output = new java.io.FileOutputStream(file + ":private")) { output.write("private stream".getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        assertThrows(java.io.IOException.class, () -> FileMetadata.capture(file));
        try (var snapshot = WorkspaceChanges.capture(root)) { snapshot.finish(); }
        try (var input = new java.io.FileInputStream(file + ":private")) { assertEquals("private stream", new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)); }
        assertEquals("ordinary data", Files.readString(file));
    }
}
