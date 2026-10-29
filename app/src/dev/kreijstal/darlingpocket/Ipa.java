package dev.kreijstal.darlingpocket;

import android.system.ErrnoException;
import android.system.Os;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Installs an .ipa: its {@code Payload/X.app} goes to the container's /Applications. */
final class Ipa {
    private Ipa() {
    }

    /** Returns the installed bundle. */
    static File install(InputStream ipa, File applications) throws IOException {
        applications.mkdirs();
        File staging = new File(applications, ".installing");
        Runtime.deleteTree(staging);
        staging.mkdirs();
        String canonical = staging.getCanonicalPath() + File.separator;
        String bundle = null;
        try (ZipInputStream zip = new ZipInputStream(ipa)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                String name = entry.getName();
                if (!name.startsWith("Payload/"))
                    continue;
                String inside = name.substring("Payload/".length());
                int slash = inside.indexOf('/');
                String top = slash < 0 ? inside : inside.substring(0, slash);
                if (!top.endsWith(".app"))
                    continue;
                if (bundle == null)
                    bundle = top;
                else if (!bundle.equals(top))
                    throw new IOException("The IPA holds more than one app");
                File target = new File(staging, inside);
                if (!(target.getCanonicalPath() + File.separator).startsWith(canonical))
                    throw new IOException("The IPA writes outside its bundle: " + name);
                if (entry.isDirectory()) {
                    target.mkdirs();
                    continue;
                }
                target.getParentFile().mkdirs();
                try (OutputStream out = new FileOutputStream(target)) {
                    Runtime.copy(zip, out);
                }
                if (isMachO(target))
                    chmod(target, 0755);
            }
        }
        if (bundle == null)
            throw new IOException("No Payload/*.app in this file");
        File installed = new File(applications, bundle);
        Runtime.deleteTree(installed);
        if (!new File(staging, bundle).renameTo(installed))
            throw new IOException("Cannot move the app into place");
        Runtime.deleteTree(staging);
        return installed;
    }

    /**
     * The bundle's executable: a Mach-O executable at its top level, the one
     * named like the bundle if there are several. (Info.plist says which, but
     * it is usually a binary plist.)
     */
    static File executable(File bundle) throws IOException {
        String name = bundle.getName().replaceFirst("\\.app$", "");
        File named = new File(bundle, name);
        if (named.isFile() && isExecutable(named))
            return named;
        File[] files = bundle.listFiles();
        if (files != null)
            for (File file : files)
                if (file.isFile() && isExecutable(file))
                    return file;
        return null;
    }

    // MH_EXECUTE, in a thin or a fat file.
    private static boolean isExecutable(File file) throws IOException {
        byte[] header = new byte[32];
        try (InputStream in = new FileInputStream(file)) {
            if (in.read(header) != 32)
                return false;
        }
        int magic = word(header, 0, false);
        if (magic == 0xcafebabe) {
            // The first architecture's offset, big endian.
            int offset = word(header, 16, false);
            try (InputStream in = new FileInputStream(file)) {
                if (in.skip(offset) != offset || in.read(header) != 32)
                    return false;
            }
            magic = word(header, 0, false);
        }
        if (magic != 0xcffaedfe && magic != 0xcefaedfe)
            return false;
        return word(header, 12, true) == 2;
    }

    private static int word(byte[] b, int at, boolean little) {
        if (little)
            return (b[at] & 0xff) | (b[at + 1] & 0xff) << 8 | (b[at + 2] & 0xff) << 16 | (b[at + 3] & 0xff) << 24;
        return (b[at] & 0xff) << 24 | (b[at + 1] & 0xff) << 16 | (b[at + 2] & 0xff) << 8 | (b[at + 3] & 0xff);
    }

    // An IPA's zip often has no Unix modes, so executables are known by their header.
    private static boolean isMachO(File file) throws IOException {
        byte[] magic = new byte[4];
        try (InputStream in = new FileInputStream(file)) {
            if (in.read(magic) != 4)
                return false;
        }
        int m = (magic[0] & 0xff) << 24 | (magic[1] & 0xff) << 16 | (magic[2] & 0xff) << 8 | (magic[3] & 0xff);
        return m == 0xcffaedfe || m == 0xcefaedfe || m == 0xcafebabe || m == 0xfeedfacf;
    }

    private static void chmod(File file, int mode) throws IOException {
        try {
            Os.chmod(file.getAbsolutePath(), mode);
        } catch (ErrnoException e) {
            throw new IOException(e.getMessage());
        }
    }
}
