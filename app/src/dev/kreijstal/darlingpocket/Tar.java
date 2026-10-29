package dev.kreijstal.darlingpocket;

import android.system.ErrnoException;
import android.system.Os;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Unpacks a tar stream (ustar, with GNU long names and pax paths, as
 * tools/make-runtime.sh writes it): files with their modes, directories and
 * symbolic links. Hard links are copied.
 */
final class Tar {
    private Tar() {
    }

    static void extract(InputStream in, File root, Runtime.Progress progress) throws IOException {
        String canonicalRoot = root.getCanonicalPath() + File.separator;
        byte[] header = new byte[512];
        String longName = null, longLink = null;
        long count = 0;
        while (readFully(in, header)) {
            if (isZero(header))
                break;
            char type = (char) header[156];
            long size = octal(header, 124, 12);
            int mode = (int) octal(header, 100, 8) & 07777;

            // Headers that hold the next entry's long name or link: an entry
            // may have both, in either order.
            if (type == 'L' || type == 'K' || type == 'x') {
                byte[] data = new byte[(int) size];
                readFully(in, data);
                skip(in, pad(size));
                if (type == 'L') {
                    longName = cString(data);
                } else if (type == 'K') {
                    longLink = cString(data);
                } else {
                    String[] names = pax(data);
                    if (names[0] != null)
                        longName = names[0];
                    if (names[1] != null)
                        longLink = names[1];
                }
                continue;
            }
            if (type == 'g') {
                skip(in, size + pad(size));
                continue;
            }
            String name = longName != null ? longName : name(header);
            String link = longLink != null ? longLink : string(header, 157, 100);
            longName = longLink = null;

            File target = new File(root, name);
            String path = target.getCanonicalPath();
            // Nothing may land outside the root, even through a symbolic link.
            if (!(path + File.separator).startsWith(canonicalRoot) && !path.equals(root.getCanonicalPath()))
                throw new IOException("The archive writes outside its directory: " + name);
            File parent = target.getParentFile();
            if (parent != null)
                parent.mkdirs();

            switch (type) {
            case '5':
                target.mkdirs();
                chmod(target, mode);
                break;
            case '2':
                target.delete();
                Runtime.symlink(link, target);
                break;
            case '1': {
                File source = new File(root, link);
                try (InputStream from = new java.io.FileInputStream(source);
                        OutputStream out = new FileOutputStream(target)) {
                    Runtime.copy(from, out);
                }
                chmod(target, mode);
                break;
            }
            case '0':
            case '\0':
            case '7': {
                target.delete();
                try (OutputStream out = new FileOutputStream(target)) {
                    copy(in, out, size);
                }
                skip(in, pad(size));
                chmod(target, mode);
                break;
            }
            default:
                skip(in, size + pad(size));
                break;
            }
            if (++count % 2000 == 0)
                progress.update("Unpacking the runtime: " + count + " files");
        }
    }

    private static String name(byte[] header) {
        String name = string(header, 0, 100);
        // ustar keeps long paths as prefix/name.
        if (header[257] == 'u' && header[258] == 's' && header[259] == 't' && header[260] == 'a' && header[261] == 'r') {
            String prefix = string(header, 345, 155);
            if (!prefix.isEmpty())
                name = prefix + "/" + name;
        }
        return name;
    }

    private static String[] pax(byte[] data) {
        String path = null, linkpath = null;
        int offset = 0;
        while (offset < data.length) {
            int space = offset;
            while (space < data.length && data[space] != ' ')
                space++;
            if (space >= data.length)
                break;
            int length = Integer.parseInt(new String(data, offset, space - offset, StandardCharsets.US_ASCII));
            if (length <= 0)
                break;
            String record = new String(data, space + 1, offset + length - space - 2, StandardCharsets.UTF_8);
            int equals = record.indexOf('=');
            if (equals > 0) {
                String key = record.substring(0, equals), value = record.substring(equals + 1);
                if (key.equals("path"))
                    path = value;
                else if (key.equals("linkpath"))
                    linkpath = value;
            }
            offset += length;
        }
        return new String[] {path, linkpath};
    }

    private static void chmod(File file, int mode) throws IOException {
        try {
            Os.chmod(file.getAbsolutePath(), mode == 0 ? 0644 : mode);
        } catch (ErrnoException e) {
            throw new IOException("Cannot set the mode of " + file + ": " + e.getMessage());
        }
    }

    private static boolean readFully(InputStream in, byte[] buffer) throws IOException {
        int done = 0;
        while (done < buffer.length) {
            int n = in.read(buffer, done, buffer.length - done);
            if (n < 0) {
                if (done == 0)
                    return false;
                throw new IOException("The archive ends in the middle of an entry");
            }
            done += n;
        }
        return true;
    }

    private static void copy(InputStream in, OutputStream out, long size) throws IOException {
        byte[] buffer = new byte[1 << 16];
        while (size > 0) {
            int n = in.read(buffer, 0, (int) Math.min(buffer.length, size));
            if (n < 0)
                throw new IOException("The archive ends in the middle of a file");
            out.write(buffer, 0, n);
            size -= n;
        }
    }

    private static void skip(InputStream in, long count) throws IOException {
        while (count > 0) {
            long n = in.skip(count);
            if (n <= 0) {
                if (in.read() < 0)
                    throw new IOException("The archive ends early");
                n = 1;
            }
            count -= n;
        }
    }

    private static long pad(long size) {
        return (512 - size % 512) % 512;
    }

    private static boolean isZero(byte[] header) {
        for (byte b : header)
            if (b != 0)
                return false;
        return true;
    }

    private static String string(byte[] header, int offset, int length) {
        int end = offset;
        while (end < offset + length && header[end] != 0)
            end++;
        return new String(header, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static String cString(byte[] data) {
        int end = 0;
        while (end < data.length && data[end] != 0)
            end++;
        return new String(data, 0, end, StandardCharsets.UTF_8);
    }

    private static long octal(byte[] header, int offset, int length) {
        long value = 0;
        for (int i = offset; i < offset + length; i++) {
            byte b = header[i];
            if (b == 0 || b == ' ') {
                if (value != 0)
                    break;
                continue;
            }
            if (b < '0' || b > '7')
                break;
            value = value * 8 + (b - '0');
        }
        return value;
    }
}
