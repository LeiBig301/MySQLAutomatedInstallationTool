package com.mysql.installer.logging;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class LogManager {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static Path logFile;
    private static FileWriter logWriter;
    private static boolean initialized = false;

    public static void initialize() {
        if (initialized) return;

        try {
            // 创建日志目录
            Path logDir = Paths.get("logs");
            if (!Files.exists(logDir)) {
                Files.createDirectories(logDir);
            }

            // 创建日志文件，包含日期时间
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            logFile = logDir.resolve("mysql_installer_" + timestamp + ".log");
            logWriter = new FileWriter(logFile.toFile(), true);
            initialized = true;

            log("Log manager initialized. Log file: " + logFile);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void log(String message) {
        String timestamp = LocalDateTime.now().format(FORMATTER);
        String logEntry = "[" + timestamp + "] " + message;

        // 输出到控制台
        System.out.println(logEntry);

        // 写入日志文件
        if (initialized && logWriter != null) {
            try {
                logWriter.write(logEntry + "\n");
                logWriter.flush();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public static void error(String message, Throwable e) {
        log("ERROR: " + message);
        if (e != null) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            log(sw.toString());
        }
    }

    public static void info(String message) {
        log("INFO: " + message);
    }

    public static void warn(String message) {
        log("WARN: " + message);
    }

    public static Path getLogFile() {
        return logFile;
    }

    public static void close() {
        if (initialized && logWriter != null) {
            try {
                logWriter.close();
                initialized = false;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public static void cleanOldLogs() {
        try {
            Path logDir = Paths.get("logs");
            if (!Files.exists(logDir)) return;

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(logDir, "mysql_installer_*.log")) {
                for (Path file : stream) {
                    // 简单跳过，不删除旧日志，避免解析错误
                    // 后续可以添加更健壮的实现
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
