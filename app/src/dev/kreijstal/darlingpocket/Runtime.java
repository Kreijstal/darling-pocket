package dev.kreijstal.darlingpocket;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Where Darling lives in the app, and how it is started.
 *
 * <ul>
 * <li>The host programs (launcher, mldr, darlingserver) and the host libraries
 * the Mach-O side opens are the APK's native libraries: Android executes only
 * those.</li>
 * <li>The Mach-O tree ({@code libexec/darling}) is a runtime archive, unpacked
 * into {@code files/darling} once. Its sha256 must be {@link BuildConfig#RUNTIME_SHA256}.</li>
 * <li>The container (Darling's prefix) is {@code files/p}, short so that
 * darlingserver's socket path fits in {@code sun_path}.</li>
 * </ul>
 */
final class Runtime {
    /** Where adb can put an archive without root, for the first start. */
    static final String ARCHIVE_NAME = "darling-runtime.tar.gz";
    /** The container's first-start setup, in the runtime (cydia/setup); it does nothing once done. */
    static final String SETUP = "/usr/libexec/darling-pocket/setup";

    final File files, nativeDir, install, prefix, cache, display;

    Runtime(Context context) {
        files = context.getFilesDir();
        nativeDir = new File(context.getApplicationInfo().nativeLibraryDir);
        install = new File(files, "darling");
        prefix = new File(files, "p");
        cache = context.getCacheDir();
        display = new File(files, "pocket.sock");
    }

    File libexec() {
        return new File(install, "libexec/darling");
    }

    File stamp() {
        return new File(install, ".runtime-sha256");
    }

    boolean installed() {
        return BuildConfig.RUNTIME_SHA256.equals(readStamp());
    }

    private String readStamp() {
        try (InputStream in = new FileInputStream(stamp())) {
            byte[] bytes = new byte[128];
            int n = in.read(bytes);
            return n > 0 ? new String(bytes, 0, n, "US-ASCII").trim() : "";
        } catch (IOException e) {
            return "";
        }
    }

    /** An archive adb pushed to the app's external files, installed instead of downloading one. */
    static File pushedArchive(Context context) {
        File external = context.getExternalFilesDir(null);
        return external == null ? null : new File(external, ARCHIVE_NAME);
    }

    interface Progress {
        void update(String what);
    }

    /**
     * Opens the runtime archive this app was built for, from its release
     * ({@link BuildConfig#RUNTIME_URL}), reporting each megabyte that comes.
     */
    static InputStream download(Progress progress) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(BuildConfig.RUNTIME_URL).openConnection();
        // Hash the exact published tar.gz bytes, without transparent HTTP decoding.
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(60000);
        int code = connection.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            connection.disconnect();
            throw new IOException("Downloading " + BuildConfig.RUNTIME_URL + ": HTTP " + code);
        }
        long total = connection.getContentLengthLong() >> 20;
        return new FilterInputStream(connection.getInputStream()) {
            private long done, shown = -1;

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                int n = super.read(buffer, offset, length);
                if (n > 0 && (done += n) >> 20 != shown) {
                    shown = done >> 20;
                    progress.update("Downloading the runtime: " + shown + (total > 0 ? " of " + total : "") + " MB");
                }
                return n;
            }

            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    connection.disconnect();
                }
            }
        };
    }

    /**
     * Unpacks a runtime archive (tar.gz of {@code libexec/darling}) after
     * checking its sha256. The old tree is removed first; the container is kept.
     */
    void install(InputStream archive, Progress progress) throws IOException {
        progress.update("Checking the runtime archive");
        File staged = new File(cache, "runtime.tar.gz");
        String sha256;
        try (DigestInputStream in = new DigestInputStream(archive, MessageDigest.getInstance("SHA-256"));
                OutputStream out = new FileOutputStream(staged)) {
            copy(in, out);
            sha256 = hex(in.getMessageDigest().digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        if (!BuildConfig.RUNTIME_SHA256.equals(sha256)) {
            staged.delete();
            throw new IOException("This runtime archive does not match Darling Pocket " + BuildConfig.VERSION_NAME
                    + ". Use Install the runtime to download this version's archive. (sha256 " + sha256
                    + ", expected " + BuildConfig.RUNTIME_SHA256 + ")");
        }

        progress.update("Removing the old runtime");
        deleteTree(install);
        if (!install.mkdirs())
            throw new IOException("Cannot create " + install);
        try (InputStream in = new GZIPInputStream(new BufferedInputStream(new FileInputStream(staged), 1 << 16))) {
            Tar.extract(in, install, progress);
        } finally {
            staged.delete();
        }
        adapt();
        try (OutputStream out = new FileOutputStream(stamp())) {
            out.write(sha256.getBytes("US-ASCII"));
        }
        progress.update("Installed");
    }

    /**
     * Creates the container, which Darling does on its first start, and sets
     * it up (Cydia: {@code cydia/setup}), then stops it again. The output goes
     * to {@code files/logs/container.log}.
     */
    void createContainer(Progress progress) throws IOException {
        if (prefix.isDirectory())
            return;
        progress.update("Creating the container and setting up Cydia (the first start takes minutes)");
        File logs = new File(files, "logs");
        logs.mkdirs();
        ProcessBuilder builder = shell(SETUP);
        builder.redirectOutput(new File(logs, "container.log"));
        try {
            int status = builder.start().waitFor();
            shutdown().start().waitFor();
            if (status != 0 || !prefix.isDirectory())
                throw new IOException("Darling could not create or set up its container (exit " + status
                        + "); see files/logs/container.log");
        } catch (InterruptedException e) {
            throw new IOException(e);
        }
    }

    /** Points the tree at this phone: Android has no /tmp. */
    private void adapt() throws IOException {
        File tmp = new File(cache, "tmp");
        tmp.mkdirs();
        new File(cache, "shm").mkdirs();
        File link = new File(libexec(), "private/tmp");
        link.delete();
        symlink("../Volumes/SystemRoot" + tmp.getAbsolutePath(), link);
    }

    /** The guest's name for a host file: Darling shows the host's root at /Volumes/SystemRoot. */
    static String guestPath(File host) {
        return "/Volumes/SystemRoot" + host.getAbsolutePath();
    }

    /** {@code darling shell env ... command}, ready to start. */
    ProcessBuilder shell(String... command) {
        List<String> args = new ArrayList<>();
        args.add(new File(nativeDir, "libdarling.so").getAbsolutePath());
        args.add("shell");
        args.add("env");
        // These reach the guest's processes, so mldr finds the host libraries
        // and the apps find the display.
        args.add("LD_LIBRARY_PATH=" + nativeDir.getAbsolutePath());
        args.add("DARLING_SHM_DIR=" + new File(cache, "shm").getAbsolutePath());
        args.add("FONTCONFIG_FILE=" + new File(files, "etc/fonts/fonts.conf").getAbsolutePath());
        args.add("DARLING_POCKET_DISPLAY=" + guestPath(display));
        // usernotificationsd writes what is coming there, for Android's AlarmManager (Alarms).
        args.add("USERNOTIFICATIONS_SCHEDULE=" + guestPath(new File(files, Alarms.SCHEDULE)));
        // The phone's time zone, from the runtime's zoneinfo: Android has no /etc/localtime.
        args.add("TZ=" + java.util.TimeZone.getDefault().getID());
        for (String arg : command)
            args.add(arg);
        ProcessBuilder builder = new ProcessBuilder(args);
        Map<String, String> env = builder.environment();
        env.put("DARLING_INSTALL_PREFIX", install.getAbsolutePath());
        env.put("DPREFIX", prefix.getAbsolutePath());
        env.put("DARLING_MLDR_PATH", new File(nativeDir, "libmldr.so").getAbsolutePath());
        env.put("DARLING_SERVER_PATH", new File(nativeDir, "libdarlingserver.so").getAbsolutePath());
        env.put("LD_LIBRARY_PATH", nativeDir.getAbsolutePath());
        env.put("HOME", files.getAbsolutePath());
        env.put("TMPDIR", cache.getAbsolutePath());
        // A crashing guest would leave a core of up to hundreds of MB in the container.
        env.put("DARLING_NO_CORE_DUMPS", "1");
        builder.directory(files);
        builder.redirectErrorStream(true);
        return builder;
    }

    /** {@code darling shutdown}: stops the container and whatever runs in it. */
    ProcessBuilder shutdown() {
        ProcessBuilder builder = new ProcessBuilder(new File(nativeDir, "libdarling.so").getAbsolutePath(), "shutdown");
        builder.environment().putAll(shell().environment());
        builder.directory(files);
        builder.redirectErrorStream(true);
        return builder;
    }

    /** The apps installed in the container, by bundle directory. */
    File applications() {
        return new File(prefix, "Applications");
    }

    static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[1 << 16];
        for (int n; (n = in.read(buffer)) > 0;)
            out.write(buffer, 0, n);
    }

    static String hex(byte[] bytes) {
        StringBuilder s = new StringBuilder();
        for (byte b : bytes)
            s.append(String.format("%02x", b & 0xff));
        return s.toString();
    }

    static void symlink(String target, File link) throws IOException {
        try {
            Os.symlink(target, link.getAbsolutePath());
        } catch (ErrnoException e) {
            throw new IOException("Cannot link " + link + ": " + e.getMessage());
        }
    }

    /** Deletes a directory without following its symbolic links. */
    static void deleteTree(File file) throws IOException {
        try {
            if (!android.system.OsConstants.S_ISLNK(Os.lstat(file.getAbsolutePath()).st_mode)) {
                File[] children = file.listFiles();
                if (children != null)
                    for (File child : children)
                        deleteTree(child);
            }
        } catch (ErrnoException e) {
            return; // gone already
        }
        if (!file.delete())
            throw new IOException("Cannot delete " + file);
    }

    static void ensureFile(InputStream source, File target) throws IOException {
        target.getParentFile().mkdirs();
        try (OutputStream out = new FileOutputStream(target)) {
            copy(source, out);
        }
    }
}
