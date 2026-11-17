package org.agty.aiassistant.changes;

import com.sun.jna.*;
import com.sun.jna.ptr.NativeLongByReference;
import com.sun.jna.ptr.PointerByReference;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.MessageDigest;
import java.util.*;

/** Preserve access decisions when replacing an inode; a permission-only chmod loses owners and ACLs. */
public final class FileMetadata {
    private static final int MAX_ACL = 1024 * 1024;
    public record Access(UserPrincipal owner, GroupPrincipal group, Integer unixMode, List<AclEntry> windowsAcl,
                         String nativeAcl, List<Boolean> dos) {
        public String fingerprint() {
            try {
                String value = owner.getName() + "\n" + (group == null ? "" : group.getName()) + "\n" + unixMode + "\n"
                        + windowsAcl + "\n" + nativeAcl + "\n" + dos;
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
            } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        }
    }
    public static Access capture(Path path) throws IOException {
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Метаданные доступны только для обычного файла.");
            UserPrincipal owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
            if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
                var attributes = Files.readAttributes(path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                int mode = ((Number) Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS)).intValue() & 07777;
                if ((mode & 06000) != 0) throw new IOException("Файл имеет setuid/setgid; автоматическая замена запрещена.");
                String acl = Platform.isLinux() ? linuxAcl(path) : Platform.isMac() ? macAccess(path) : unsupported();
                return new Access(owner, attributes.group(), mode, null, acl, null);
            }
            if (Platform.isWindows()) rejectAlternateStreams(path);
            var acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (acl == null) throw new IOException("Файловая система не предоставляет проверяемые ACL.");
            var flags = Files.readAttributes(path, DosFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return new Access(owner, null, null, List.copyOf(acl.getAcl()), null,
                    List.of(flags.isReadOnly(), flags.isHidden(), flags.isSystem()));
        } catch (LinkageError | RuntimeException e) { throw new IOException("Нельзя достоверно прочитать права файла; замена запрещена.", e); }
    }
    public static void apply(Access access, Path target) throws IOException {
        try {
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Целевой файл метаданных недоступен.");
            if (!Files.getOwner(target, LinkOption.NOFOLLOW_LINKS).equals(access.owner)) Files.setOwner(target, access.owner);
            if (access.unixMode != null) {
                var view = Files.getFileAttributeView(target, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                if (view == null) throw new IOException("Целевая файловая система не поддерживает исходные POSIX права.");
                if (!view.readAttributes().group().equals(access.group)) view.setGroup(access.group);
                Files.setAttribute(target, "unix:mode", access.unixMode, LinkOption.NOFOLLOW_LINKS);
                if (Platform.isLinux()) linuxAcl(target, access.nativeAcl); else if (Platform.isMac()) macAccess(target, access.nativeAcl); else unsupported();
            } else {
                var view = Files.getFileAttributeView(target, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                if (view == null) throw new IOException("Целевая файловая система не поддерживает исходные ACL.");
                view.setAcl(access.windowsAcl);
                Files.setAttribute(target, "dos:hidden", access.dos.get(1), LinkOption.NOFOLLOW_LINKS);
                Files.setAttribute(target, "dos:system", access.dos.get(2), LinkOption.NOFOLLOW_LINKS);
                Files.setAttribute(target, "dos:readonly", access.dos.get(0), LinkOption.NOFOLLOW_LINKS);
            }
            if (!capture(target).fingerprint().equals(access.fingerprint()))
                throw new IOException("Права скопированы не полностью; замена исходного файла запрещена.");
        } catch (LinkageError | RuntimeException e) { throw new IOException("Нельзя сохранить права файла; замена запрещена.", e); }
    }
    public static void copyAccess(Path source, Path target) throws IOException { apply(capture(source), target); }
    public static String fingerprint(Path path) throws IOException { return capture(path).fingerprint(); }
    private static String unsupported() throws IOException { throw new IOException("Проверка ACL этой платформы не поддерживается."); }
    private static NativeLibrary libc() { return NativeLibrary.getInstance(Platform.C_LIBRARY_NAME); }
    private static IOException nativeError(String operation) { return new IOException(operation + ": errno " + Native.getLastError()); }
    private static String linuxAcl(Path path) throws IOException {
        return new com.google.gson.Gson().toJson(linuxAccessAttributes(path));
    }
    private static Map<String, String> linuxAccessAttributes(Path path) throws IOException {
        var list = libc().getFunction("llistxattr");
        long size = list.invokeLong(new Object[]{path.toString(), Pointer.NULL, new NativeLong(0)});
        if (size < 0 || size > MAX_ACL) throw nativeError("Список security attributes");
        Map<String, String> attributes = new TreeMap<>();
        if (size == 0) return attributes;
        try (var names = new Memory(size)) {
            long actual = list.invokeLong(new Object[]{path.toString(), names, new NativeLong(size)});
            if (actual < 0 || actual > size) throw nativeError("Список security attributes");
            long total = actual;
            for (String name : new String(names.getByteArray(0, (int) actual), StandardCharsets.UTF_8).split("\u0000")) {
                if (Set.of("security.capability", "security.ima", "security.evm").contains(name))
                    throw new IOException("Файл имеет привилегии или подпись целостности; автоматическая замена запрещена.");
                var get = libc().getFunction("lgetxattr");
                long length = get.invokeLong(new Object[]{path.toString(), name, Pointer.NULL, new NativeLong(0)});
                total += Math.max(0, length);
                if (length < 0 || total > MAX_ACL) throw nativeError("Чтение security attribute");
                try (var value = new Memory(Math.max(1, length))) {
                    long read = get.invokeLong(new Object[]{path.toString(), name, value, new NativeLong(length)});
                    if (read < 0 || read > length) throw nativeError("Чтение security attribute");
                    attributes.put(name, Base64.getEncoder().encodeToString(value.getByteArray(0, (int) read)));
                }
            }
        }
        return attributes;
    }
    private static void linuxAcl(Path path, String encoded) throws IOException {
        Map<String, String> desired = new TreeMap<>();
        var json = com.google.gson.JsonParser.parseString(encoded).getAsJsonObject();
        for (var entry : json.entrySet()) desired.put(entry.getKey(), entry.getValue().getAsString());
        var current = linuxAccessAttributes(path);
        for (String name : current.keySet()) if (!desired.containsKey(name)) {
            if (libc().getFunction("lremovexattr").invokeInt(new Object[]{path.toString(), name}) != 0)
                throw nativeError("Удаление унаследованного security attribute");
        }
        for (var entry : desired.entrySet()) if (!entry.getValue().equals(current.get(entry.getKey()))) {
            byte[] bytes = Base64.getDecoder().decode(entry.getValue());
            int result = libc().getFunction("lsetxattr").invokeInt(new Object[]{path.toString(), entry.getKey(), bytes, new NativeLong(bytes.length), 0});
            if (result != 0) throw nativeError("Сохранение security attribute");
        }
    }
    private static void rejectAlternateStreams(Path path) throws IOException {
        var library = NativeLibrary.getInstance("kernel32");
        try (var data = new Memory(8 + 296 * 2L)) {
            Pointer handle = library.getFunction("FindFirstStreamW", Function.ALT_CONVENTION)
                    .invokePointer(new Object[]{new WString(path.toString()), 0, data, 0});
            if (handle == null || Pointer.nativeValue(handle) == -1L) {
                if (Native.getLastError() == 38) return;
                throw nativeError("Проверка alternate data streams");
            }
            try {
                do {
                    if (!data.getWideString(8).equals("::$DATA"))
                        throw new IOException("Файл содержит alternate data streams; автоматическая замена запрещена, чтобы сохранить их.");
                } while (library.getFunction("FindNextStreamW", Function.ALT_CONVENTION).invokeInt(new Object[]{handle, data}) != 0);
                if (Native.getLastError() != 38) throw nativeError("Проверка alternate data streams");
            } finally { library.getFunction("FindClose", Function.ALT_CONVENTION).invokeInt(new Object[]{handle}); }
        }
    }
    private static String macAccess(Path path) throws IOException {
        var result = new com.google.gson.JsonObject(); result.addProperty("acl", macAcl(path));
        result.add("attributes", new com.google.gson.Gson().toJsonTree(macAttributes(path)));
        return result.toString();
    }
    private static Map<String, String> macAttributes(Path path) throws IOException {
        var list = libc().getFunction("listxattr");
        long size = list.invokeLong(new Object[]{path.toString(), Pointer.NULL, new NativeLong(0), 1});
        if (size < 0 || size > MAX_ACL) throw nativeError("Список macOS attributes");
        Map<String, String> attributes = new TreeMap<>();
        if (size == 0) return attributes;
        try (var names = new Memory(size)) {
            long actual = list.invokeLong(new Object[]{path.toString(), names, new NativeLong(size), 1});
            if (actual < 0 || actual > size) throw nativeError("Список macOS attributes");
            long total = actual;
            for (String name : new String(names.getByteArray(0, (int) actual), StandardCharsets.UTF_8).split("\u0000")) {
                if (name.equals("com.apple.decmpfs")) throw new IOException("Файл использует macOS compression metadata; автоматическая замена запрещена.");
                var get = libc().getFunction("getxattr");
                long length = get.invokeLong(new Object[]{path.toString(), name, Pointer.NULL, new NativeLong(0), 0, 1});
                total += Math.max(0, length);
                if (length < 0 || total > MAX_ACL) throw nativeError("Чтение macOS attribute");
                try (var value = new Memory(Math.max(1, length))) {
                    long read = get.invokeLong(new Object[]{path.toString(), name, value, new NativeLong(length), 0, 1});
                    if (read < 0 || read > length) throw nativeError("Чтение macOS attribute");
                    attributes.put(name, Base64.getEncoder().encodeToString(value.getByteArray(0, (int) read)));
                }
            }
        }
        return attributes;
    }
    private static void macAccess(Path path, String encoded) throws IOException {
        var json = com.google.gson.JsonParser.parseString(encoded).getAsJsonObject();
        var desired = json.getAsJsonObject("attributes"); var current = macAttributes(path);
        for (String name : current.keySet()) if (!desired.has(name)) {
            if (libc().getFunction("removexattr").invokeInt(new Object[]{path.toString(), name, 1}) != 0)
                throw nativeError("Удаление унаследованного macOS attribute");
        }
        for (var entry : desired.entrySet()) if (!entry.getValue().getAsString().equals(current.get(entry.getKey()))) {
            byte[] bytes = Base64.getDecoder().decode(entry.getValue().getAsString());
            if (libc().getFunction("setxattr").invokeInt(new Object[]{path.toString(), entry.getKey(), bytes, new NativeLong(bytes.length), 0, 1}) != 0)
                throw nativeError("Сохранение macOS attribute");
        }
        macAcl(path, json.get("acl").getAsString());
    }
    private static String macAcl(Path path) throws IOException {
        var library = libc(); Pointer security = library.getFunction("filesec_init").invokePointer(new Object[]{});
        if (security == null) throw nativeError("filesec_init");
        Pointer acl = null, text = null;
        try (var stat = new Memory(4096)) {
            // statx_np fills the native stat buffer and a separate, opaque filesec object.
            if (library.getFunction("statx_np").invokeInt(new Object[]{path.toString(), stat, security}) != 0) throw nativeError("statx_np");
            var reference = new PointerByReference();
            if (library.getFunction("filesec_get_property").invokeInt(new Object[]{security, 5, reference}) != 0) {
                if (Native.getLastError() == 2) return ""; // FILESEC_ACL property absent, after successful statx_np
                throw nativeError("Чтение FILESEC_ACL");
            }
            acl = reference.getValue(); if (acl == null) return "";
            var length = new NativeLongByReference();
            text = library.getFunction("acl_to_text").invokePointer(new Object[]{acl, length});
            long size = length.getValue().longValue();
            if (text == null || size < 0 || size > MAX_ACL) throw nativeError("Чтение macOS ACL");
            String value = new String(text.getByteArray(0, (int) size), StandardCharsets.UTF_8);
            return value.strip().equals("!#acl 1") ? "" : value;
        } finally {
            if (text != null) library.getFunction("acl_free").invokeInt(new Object[]{text});
            if (acl != null) library.getFunction("acl_free").invokeInt(new Object[]{acl});
            library.getFunction("filesec_free").invokeVoid(new Object[]{security});
        }
    }
    private static void macAcl(Path path, String text) throws IOException {
        var library = libc();
        Pointer acl = text.isEmpty() ? library.getFunction("acl_init").invokePointer(new Object[]{0})
                : library.getFunction("acl_from_text").invokePointer(new Object[]{text});
        if (acl == null) throw nativeError("Создание macOS ACL");
        try { if (library.getFunction("acl_set_file").invokeInt(new Object[]{path.toString(), 0x100, acl}) != 0) throw nativeError("Сохранение macOS ACL"); }
        finally { library.getFunction("acl_free").invokeInt(new Object[]{acl}); }
    }
    private FileMetadata() { }
}
