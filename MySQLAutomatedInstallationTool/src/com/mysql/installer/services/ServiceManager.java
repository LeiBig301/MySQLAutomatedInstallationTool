package com.mysql.installer.services;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.file.Path;

public class ServiceManager {
    public static boolean isServiceExists(String serviceName) {
        try {
            ProcessBuilder pb = new ProcessBuilder("sc", "query", serviceName);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("SERVICE_NAME: " + serviceName)) {
                        return true;
                    }
                }
            }
            
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean stopService(String serviceName) {
        try {
            ProcessBuilder pb = new ProcessBuilder("net", "stop", serviceName);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean deleteService(String serviceName) {
        try {
            ProcessBuilder pb = new ProcessBuilder("sc", "delete", serviceName);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean installService(Path mysqldExe, String serviceName, Path iniPath) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                mysqldExe.toString(),
                "--install", serviceName,
                "--defaults-file=" + iniPath.toString()
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean startService(String serviceName) {
        try {
            ProcessBuilder pb = new ProcessBuilder("net", "start", serviceName);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isServiceRunning(String serviceName) {
        try {
            ProcessBuilder pb = new ProcessBuilder("sc", "query", serviceName);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("STATE") && line.contains("RUNNING")) {
                        return true;
                    }
                }
            }
            
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isPortInUse(int port) {
        try (ServerSocket socket = new ServerSocket(port)) {
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    public static int findAvailablePort(int startPort) {
        int port = startPort;
        while (port <= 65535) {
            if (!isPortInUse(port)) {
                return port;
            }
            port++;
        }
        return -1;
    }

    public static String findServiceByPort(int port) {
        // 实现通过端口查找服务的逻辑
        // 这里需要读取服务的配置文件，查找使用指定端口的服务
        return null;
    }
}
