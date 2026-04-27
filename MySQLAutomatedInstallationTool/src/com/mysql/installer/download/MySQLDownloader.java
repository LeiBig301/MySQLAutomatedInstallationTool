package com.mysql.installer.download;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MySQLDownloader {
    public static String fetchDownloadUrl(String version, boolean is64Bit) {
        // 多级降级策略获取下载链接
        String[] downloadUrls = {
            // 官方链接
            "https://dev.mysql.com/get/Downloads/MySQL-" + version.split("\\.")[0] + "." + version.split("\\.")[1] + "/mysql-" + version + "-winx64.zip",
            "https://cdn.mysql.com/Downloads/MySQL-" + version.split("\\.")[0] + "." + version.split("\\.")[1] + "/mysql-" + version + "-winx64.zip",
            "https://dev.mysql.com/get/Downloads/MySQL-" + version.split("\\.")[0] + "/mysql-" + version + "-winx64.zip",
            
            // 镜像站点
            "https://mirrors.tuna.tsinghua.edu.cn/mysql/downloads/MySQL-" + version.split("\\.")[0] + "." + version.split("\\.")[1] + "/mysql-" + version + "-winx64.zip",
            "https://mirrors.aliyun.com/mysql/MySQL-" + version.split("\\.")[0] + "." + version.split("\\.")[1] + "/mysql-" + version + "-winx64.zip",
            "https://repo1.maven.org/maven2/mysql/mysql-connector-java/" + version + "/mysql-connector-java-" + version + ".jar",
            
            // 其他备用链接
            "https://downloads.mysql.com/archives/get/p/23/file/mysql-" + version + "-winx64.zip",
            "https://dev.mysql.com/downloads/file/?id=" + version.replace(".", "") + "1",
            "https://mysql.mirror.iweb.com/Downloads/MySQL-" + version.split("\\.")[0] + "." + version.split("\\.")[1] + "/mysql-" + version + "-winx64.zip"
        };

        for (String url : downloadUrls) {
            if (isUrlAccessible(url)) {
                return url;
            }
        }

        // 最后返回清华大学镜像链接
        return "https://mirrors.tuna.tsinghua.edu.cn/mysql/downloads/MySQL-" + version.split("\\.")[0] + "." + version.split("\\.")[1] + "/mysql-" + version + "-winx64.zip";
    }

    public static boolean downloadFile(String url, Path destination, DownloadLogger logger) {
        // 先尝试 Java 原生下载
        if (downloadWithJava(url, destination, logger)) {
            return true;
        }

        // 降级到 curl 命令
        logger.log("⚠️ Java 下载失败，尝试使用 curl 降级下载...");
        return downloadWithCurl(url, destination, logger);
    }

    private static boolean downloadWithJava(String url, Path destination, DownloadLogger logger) {
        try {
            URL downloadUrl = new URL(url);
            HttpURLConnection conn = (HttpURLConnection) downloadUrl.openConnection();

            // 设置浏览器模拟头
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8");
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            conn.setRequestProperty("Referer", "https://dev.mysql.com/downloads/mysql/");
            conn.setRequestProperty("Connection", "keep-alive");
            conn.setRequestProperty("Upgrade-Insecure-Requests", "1");
            conn.setRequestProperty("Accept-Encoding", "gzip, deflate, br");

            conn.setConnectTimeout(30000);
            conn.setReadTimeout(60000);
            conn.connect();

            int responseCode = conn.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                logger.log("Java 下载异常: " + url);
                return false;
            }

            try (InputStream in = conn.getInputStream();
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(destination.toFile()))) {

                byte[] buffer = new byte[8192];
                int bytesRead;
                long totalRead = 0;
                long contentLength = conn.getContentLengthLong();

                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                    totalRead += bytesRead;
                }

                // 验证文件大小
                if (totalRead > 100 * 1024 * 1024) {
                    return true;
                } else {
                    logger.log("下载文件大小异常: " + totalRead + " bytes");
                    return false;
                }
            }
        } catch (Exception e) {
            logger.log("Java 下载异常: " + e.getMessage());
            return false;
        }
    }

    private static boolean downloadWithCurl(String url, Path destination, DownloadLogger logger) {
        try {
            // 尝试多个不同的 User-Agent
            String[] userAgents = {
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_4) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:124.0) Gecko/20100101 Firefox/124.0",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0"
            };

            // 尝试不同的 curl 配置
            for (String userAgent : userAgents) {
                logger.log("尝试使用 User-Agent: " + userAgent);
                
                String[] cmd = {
                    "curl",
                    "-L",
                    "-o", destination.toString(),
                    "-A", userAgent,
                    "-H", "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                    "-H", "Accept-Language: en-US,en;q=0.9,zh-CN;q=0.8,zh;q=0.7",
                    "-H", "Accept-Encoding: gzip, deflate, br",
                    "-H", "Connection: keep-alive",
                    "-H", "Referer: https://dev.mysql.com/downloads/mysql/",
                    "-H", "Upgrade-Insecure-Requests: 1",
                    "-H", "Sec-Fetch-Dest: document",
                    "-H", "Sec-Fetch-Mode: navigate",
                    "-H", "Sec-Fetch-Site: same-origin",
                    "-H", "Sec-Fetch-User: ?1",
                    "--compressed",
                    "--insecure",
                    "--max-time", "600", // 10分钟超时
                    "--retry", "3", // 重试3次
                    "--retry-delay", "5", // 重试间隔5秒
                    url
                };

                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                Process process = pb.start();

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        logger.log("[curl] " + line);
                    }
                }

                int exitCode = process.waitFor();
                if (exitCode == 0) {
                    // 验证文件大小
                    long fileSize = destination.toFile().length();
                    if (fileSize > 100 * 1024 * 1024) {
                        logger.log("✅ curl 下载成功");
                        return true;
                    } else if (fileSize > 0) {
                        logger.log("下载文件大小: " + fileSize + " bytes");
                        // 可能是重定向页面，继续尝试下一个 User-Agent
                    } else {
                        logger.log("❌ 下载文件为空");
                    }
                } else {
                    logger.log("❌ curl 下载失败，退出码: " + exitCode);
                }
            }

            return false;
        } catch (Exception e) {
            logger.log("❌ curl 下载异常: " + e.getMessage());
            return false;
        }
    }

    private static boolean isUrlAccessible(String url) {
        try {
            URL testUrl = new URL(url);
            HttpURLConnection conn = (HttpURLConnection) testUrl.openConnection();
            conn.setRequestMethod("GET"); // 使用GET而不是HEAD，因为有些服务器会阻止HEAD请求
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);

            // 设置更完整的浏览器模拟头
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8");
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9,zh-CN;q=0.8,zh;q=0.7");
            conn.setRequestProperty("Accept-Encoding", "gzip, deflate, br");
            conn.setRequestProperty("Connection", "keep-alive");
            conn.setRequestProperty("Referer", "https://dev.mysql.com/downloads/mysql/");
            conn.setRequestProperty("Upgrade-Insecure-Requests", "1");
            conn.setRequestProperty("Sec-Fetch-Dest", "document");
            conn.setRequestProperty("Sec-Fetch-Mode", "navigate");
            conn.setRequestProperty("Sec-Fetch-Site", "same-origin");
            conn.setRequestProperty("Sec-Fetch-User", "?1");

            // 允许重定向
            conn.setInstanceFollowRedirects(true);

            int responseCode = conn.getResponseCode();
            // 检查是否是成功的响应或重定向
            return responseCode == HttpURLConnection.HTTP_OK || 
                   responseCode == HttpURLConnection.HTTP_MOVED_TEMP || 
                   responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                   responseCode == HttpURLConnection.HTTP_SEE_OTHER ||
                   responseCode == 307; // HTTP_TEMP_REDIRECT
        } catch (Exception e) {
            // 忽略异常，返回false
            return false;
        }
    }

    public interface DownloadLogger {
        void log(String message);
    }
}
