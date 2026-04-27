import java.io.*;
import java.nio.file.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * ZIP 解压工具，支持进度回调
 */
public class ZipUtils {

    public interface ProgressCallback {
        void onProgress(long current, long total);
    }

    public static void extractZipWithProgress(Path zipPath, Path destDir,
                                              ProgressCallback callback) throws IOException {
        long totalEntries = countZipEntries(zipPath);
        long processed = 0;

        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipPath))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path entryPath = destDir.resolve(entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    Files.copy(zis, entryPath, StandardCopyOption.REPLACE_EXISTING);
                }
                processed++;
                if (callback != null) {
                    callback.onProgress(processed, totalEntries);
                }
                zis.closeEntry();
            }
        }
    }

    private static long countZipEntries(Path zipPath) throws IOException {
        long count = 0;
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipPath))) {
            while (zis.getNextEntry() != null) {
                count++;
                zis.closeEntry();
            }
        }
        return count;
    }
}