package com.mysql.installer.utils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class EnvironmentVariableUtil {
    public static void addToSystemPath(String path) throws IOException {
        String currentPath = getSystemPath();
        if (!currentPath.contains(path)) {
            String newPath = currentPath + ";" + path;
            setSystemPath(newPath);
        }
    }

    public static String getSystemPath() throws IOException {
        ProcessBuilder pb = new ProcessBuilder("reg", "query", "HKLM\\SYSTEM\\CurrentControlSet\\Control\\Session Manager\\Environment", "/v", "PATH");
        pb.redirectErrorStream(true);
        Process process = pb.start();

        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("PATH")) {
                    int index = line.indexOf("REG_SZ");
                    if (index != -1) {
                        return line.substring(index + 6).trim();
                    }
                }
            }
        }
        return "";
    }

    public static void setSystemPath(String path) throws IOException {
        ProcessBuilder pb = new ProcessBuilder("reg", "add", "HKLM\\SYSTEM\\CurrentControlSet\\Control\\Session Manager\\Environment", "/v", "PATH", "/t", "REG_SZ", "/d", path, "/f");
        pb.redirectErrorStream(true);
        Process process = pb.start();
        try {
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("Failed to set system PATH");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while setting system PATH", e);
        }
    }

    public static List<String> parsePath(String path) {
        List<String> paths = new ArrayList<>();
        String[] parts = path.split(";+");
        for (String part : parts) {
            if (!part.trim().isEmpty()) {
                paths.add(part.trim());
            }
        }
        return paths;
    }

    public static void removeFromSystemPath(String keyword) throws IOException {
        String currentPath = getSystemPath();
        List<String> paths = parsePath(currentPath);
        List<String> newPaths = new ArrayList<>();
        
        for (String path : paths) {
            if (!path.toLowerCase().contains(keyword.toLowerCase())) {
                newPaths.add(path);
            }
        }
        
        if (newPaths.size() != paths.size()) {
            String newPath = String.join(";", newPaths);
            setSystemPath(newPath);
        }
    }
}
