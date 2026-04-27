package com.mysql.installer.utils;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ZipUtils {
    public interface ProgressCallback {
        void onProgress(long current, long total);
    }

    public static void extractZipWithProgress(Path zipPath, Path targetDir, ProgressCallback callback) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipPath.toFile()))) {
            long totalSize = Files.size(zipPath);
            long currentSize = 0;

            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path entryPath = targetDir.resolve(entry.getName());

                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    try (OutputStream os = Files.newOutputStream(entryPath)) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            os.write(buffer, 0, len);
                            currentSize += len;
                            callback.onProgress(currentSize, totalSize);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
    }

    public static void extractZip(Path zipPath, Path targetDir) throws IOException {
        extractZipWithProgress(zipPath, targetDir, (current, total) -> {
            // 默认实现，不做任何处理
        });
    }
}
