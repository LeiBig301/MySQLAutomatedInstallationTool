import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import javax.swing.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * MySQL 下载器（后台 SwingWorker + 多级下载策略）
 * 特性：
 * - 优先使用 Java 原生下载（增强请求头）
 * - 失败时自动降级为调用系统 curl 命令下载
 * - 多级降级策略确保最高成功率
 */
public class MySQLDownloader extends SwingWorker<Void, Integer> {

    private final String downloadUrl;
    private final Path destination;
    private final JProgressBar progressBar;
    private final JTextArea logArea;
    private Consumer<String> externalLogger;

    public MySQLDownloader(String downloadUrl, Path destination,
                           JProgressBar progressBar, JTextArea logArea) {
        this.downloadUrl = downloadUrl;
        this.destination = destination;
        this.progressBar = progressBar;
        this.logArea = logArea;
    }

    public void setExternalLogger(Consumer<String> logger) {
        this.externalLogger = logger;
    }

    // ==================== 公开的静态工具方法 ====================

    /**
     * 同步下载文件（供外部直接调用，内部集成降级策略）
     * @param url 下载链接
     * @param destination 目标文件路径
     * @param logger 日志回调（可为 null）
     * @return 是否下载成功
     */
    public static boolean downloadFile(String url, Path destination, Consumer<String> logger) {
        logMessageStatic(logger, "开始下载: " + url);

        // 策略1：尝试 Java 原生下载
        boolean javaSuccess = downloadWithJava(url, destination, logger);
        if (javaSuccess) {
            logMessageStatic(logger, "✅ Java 原生下载成功");
            return true;
        }

        // 策略2：降级为 curl 下载
        logMessageStatic(logger, "⚠️ Java 下载失败，尝试使用 curl 降级下载...");
        boolean curlSuccess = downloadWithCurl(url, destination, logger);
        if (curlSuccess) {
            logMessageStatic(logger, "✅ curl 下载成功");
            return true;
        }

        logMessageStatic(logger, "❌ 所有下载方式均失败");
        return false;
    }

    /**
     * 获取下载 URL（多级降级策略）
     * @param version MySQL 版本号，如 "8.0.35"
     * @param is64Bit 是否 64 位系统
     * @return 下载 URL
     */
    public static String fetchDownloadUrl(String version, boolean is64Bit) throws IOException {
        // 1. 构建稳定链接
        String stableUrl = buildStableUrl(version, is64Bit);
        System.out.println("尝试稳定链接: " + stableUrl);

        // 2. 快速可达性检查（增强版）
        if (isUrlAccessible(stableUrl)) {
            System.out.println("稳定链接可用，直接使用。");
            return stableUrl;
        }

        // 3. 降级1：从归档页面解析
        System.out.println("稳定链接不可用，尝试从归档页面解析...");
        try {
            String archiveUrl = fetchFromArchivePage(version, is64Bit);
            System.out.println("归档页面解析成功: " + archiveUrl);
            return archiveUrl;
        } catch (IOException e) {
            System.err.println("归档页面解析失败: " + e.getMessage());
        }

        // 4. 降级2：备用稳定链接格式
        String fallbackUrl = buildFallbackStableUrl(version, is64Bit);
        System.out.println("尝试备用稳定链接: " + fallbackUrl);
        if (isUrlAccessible(fallbackUrl)) {
            return fallbackUrl;
        }

        // 5. 降级3：直接构建已知的下载链接格式
        String directUrl = buildDirectUrl(version, is64Bit);
        System.out.println("尝试直接构建链接: " + directUrl);
        if (isUrlAccessible(directUrl)) {
            return directUrl;
        }

        // 6. 降级4：使用 CDN 加速链接
        String cdnUrl = buildCdnUrl(version, is64Bit);
        System.out.println("尝试 CDN 链接: " + cdnUrl);
        if (isUrlAccessible(cdnUrl)) {
            return cdnUrl;
        }

        // 7. 降级5：使用官方下载链接
        String officialUrl = buildOfficialUrl(version, is64Bit);
        System.out.println("尝试官方链接: " + officialUrl);
        if (isUrlAccessible(officialUrl)) {
            return officialUrl;
        }

        // 8. 降级6：使用替代 CDN 链接
        String altCdnUrl = buildAlternativeCdnUrl(version, is64Bit);
        System.out.println("尝试替代 CDN 链接: " + altCdnUrl);
        if (isUrlAccessible(altCdnUrl)) {
            return altCdnUrl;
        }

        // 9. 最终降级：返回替代 CDN 链接进行尝试，即使检测失败
        System.out.println("所有链接检测失败，返回替代 CDN 链接尝试下载...");
        return altCdnUrl;
    }

    // ==================== 内部下载实现 ====================

    /**
     * Java 原生下载（增强请求头）
     */
    private static boolean downloadWithJava(String urlString, Path destination, Consumer<String> logger) {
        HttpURLConnection conn = null;
        try {
            // 启用现代 TLS 协议
            System.setProperty("https.protocols", "TLSv1.2,TLSv1.3");
            System.setProperty("jdk.tls.client.protocols", "TLSv1.2,TLSv1.3");
            
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(45000);
            conn.setInstanceFollowRedirects(true);
            conn.setUseCaches(false);

            // 完整的浏览器模拟头
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            conn.setRequestProperty("Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8");
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9,zh-CN;q=0.8,zh;q=0.7");
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setRequestProperty("Connection", "keep-alive");
            conn.setRequestProperty("Referer", "https://dev.mysql.com/downloads/mysql/");
            conn.setRequestProperty("Cache-Control", "no-cache");
            conn.setRequestProperty("Upgrade-Insecure-Requests", "1");
            conn.setRequestProperty("Sec-Fetch-Dest", "document");
            conn.setRequestProperty("Sec-Fetch-Mode", "navigate");
            conn.setRequestProperty("Sec-Fetch-Site", "same-origin");
            conn.setRequestProperty("Sec-Fetch-User", "?1");

            int fileSize = conn.getContentLength();
            logMessageStatic(logger, "文件大小: " + (fileSize > 0 ? fileSize / 1024 / 1024 + " MB" : "未知"));

            Files.createDirectories(destination.getParent());

            try (InputStream in = conn.getInputStream();
                 OutputStream out = Files.newOutputStream(destination)) {

                byte[] buffer = new byte[8192];
                int bytesRead;
                long totalRead = 0;
                long lastLogTime = System.currentTimeMillis();

                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                    totalRead += bytesRead;

                    if (System.currentTimeMillis() - lastLogTime > 2000) {
                        if (fileSize > 0) {
                            int percent = (int) (totalRead * 100 / fileSize);
                            logMessageStatic(logger, String.format("下载进度: %d%% (%d MB / %d MB)",
                                    percent, totalRead / 1024 / 1024, fileSize / 1024 / 1024));
                        } else {
                            logMessageStatic(logger, "已下载: " + totalRead / 1024 / 1024 + " MB");
                        }
                        lastLogTime = System.currentTimeMillis();
                    }
                }
            }
            
            // 验证下载文件大小
            long downloadedSize = Files.size(destination);
            logMessageStatic(logger, "Java 下载文件大小: " + downloadedSize + " 字节");
            if (downloadedSize > 100 * 1024 * 1024) {
                return true;
            } else {
                logMessageStatic(logger, "❌ 下载文件大小异常，可能是错误页面");
                return false;
            }
        } catch (Exception e) {
            logMessageStatic(logger, "Java 下载异常: " + e.getMessage());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 使用 curl 命令下载（降级方案）
     */
    private static boolean downloadWithCurl(String urlString, Path destination, Consumer<String> logger) {
        // 检查 curl 是否可用
        if (!isCurlAvailable()) {
            logMessageStatic(logger, "❌ curl 命令不可用，请确保已安装 curl 并添加到 PATH");
            return false;
        }

        try {
            String[] cmd = {
                    "curl",
                    "-L",                       // 跟随重定向
                    "-o", destination.toString(),
                    "-A", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
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
                    "--compressed",              // 支持压缩
                    "--insecure",               // 忽略 SSL 错误
                    urlString
            };

            logMessageStatic(logger, "执行命令: " + String.join(" ", cmd));

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // 异步读取输出，防止进程阻塞
            new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        logMessageStatic(logger, "[curl] " + line);
                    }
                } catch (IOException ignored) {}
            }).start();

            int exitCode = process.waitFor();
            if (exitCode == 0 && Files.exists(destination)) {
                long fileSize = Files.size(destination);
                logMessageStatic(logger, "curl 下载文件大小: " + fileSize + " 字节");
                // 检查文件大小是否合理（MySQL 安装包至少应该大于 100MB）
                if (fileSize > 100 * 1024 * 1024) {
                    return true;
                } else {
                    logMessageStatic(logger, "❌ 下载文件大小异常，可能是错误页面");
                    return false;
                }
            } else {
                logMessageStatic(logger, "curl 退出码: " + exitCode);
                return false;
            }
        } catch (Exception e) {
            logMessageStatic(logger, "curl 执行异常: " + e.getMessage());
            return false;
        }
    }

    /**
     * 检测 curl 命令是否可用
     */
    private static boolean isCurlAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("curl", "--version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            int exitCode = p.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== URL 获取辅助方法 ====================

    private static String buildStableUrl(String version, boolean is64Bit) {
        String[] parts = version.split("\\.");
        String majorMinor = (parts.length >= 2) ? parts[0] + "." + parts[1] : version;
        String arch = is64Bit ? "winx64" : "win32";
        return String.format("https://dev.mysql.com/get/Downloads/MySQL-%s/mysql-%s-%s.zip",
                majorMinor, version, arch);
    }

    private static String buildFallbackStableUrl(String version, boolean is64Bit) {
        String[] parts = version.split("\\.");
        String arch = is64Bit ? "winx64" : "win32";
        return String.format("https://dev.mysql.com/get/Downloads/MySQL-%s/mysql-%s-%s.zip",
                parts[0] + ".0", version, arch);
    }

    private static String buildDirectUrl(String version, boolean is64Bit) {
        String arch = is64Bit ? "winx64" : "win32";
        return String.format("https://cdn.mysql.com/Downloads/MySQL-%s/mysql-%s-%s.zip",
                version.split("\\.")[0] + ".0", version, arch);
    }

    private static String buildCdnUrl(String version, boolean is64Bit) {
        String arch = is64Bit ? "winx64" : "win32";
        return String.format("https://cdn.mysql.com/Downloads/MySQL-%s/mysql-%s-%s.zip",
                version.split("\\.")[0] + "." + version.split("\\.")[1], version, arch);
    }

    private static String buildOfficialUrl(String version, boolean is64Bit) {
        String arch = is64Bit ? "winx64" : "win32";
        return String.format("https://dev.mysql.com/get/Downloads/MySQL-%s/mysql-%s-%s.zip",
                version.split("\\.")[0] + "." + version.split("\\.")[1], version, arch);
    }

    private static String buildAlternativeCdnUrl(String version, boolean is64Bit) {
        String arch = is64Bit ? "winx64" : "win32";
        return String.format("https://downloads.mysql.com/archives/get/p/23/file/mysql-%s-%s.zip",
                version, arch);
    }

    private static boolean isUrlAccessible(String urlString) {
        HttpURLConnection connection = null;
        try {
            // 启用现代 TLS 协议
            System.setProperty("https.protocols", "TLSv1.2,TLSv1.3");
            System.setProperty("jdk.tls.client.protocols", "TLSv1.2,TLSv1.3");
            
            connection = (HttpURLConnection) new URL(urlString).openConnection();
            connection.setRequestMethod("HEAD"); // 使用 HEAD 方法减少数据传输
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setInstanceFollowRedirects(true);
            connection.setUseCaches(false);
            
            // 完整的浏览器模拟头
            connection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            connection.setRequestProperty("Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8");
            connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9,zh-CN;q=0.8,zh;q=0.7");
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("Connection", "keep-alive");
            connection.setRequestProperty("Referer", "https://dev.mysql.com/downloads/mysql/");
            connection.setRequestProperty("Cache-Control", "no-cache");
            connection.setRequestProperty("Upgrade-Insecure-Requests", "1");
            connection.setRequestProperty("Sec-Fetch-Dest", "document");
            connection.setRequestProperty("Sec-Fetch-Mode", "navigate");
            connection.setRequestProperty("Sec-Fetch-Site", "same-origin");
            connection.setRequestProperty("Sec-Fetch-User", "?1");

            int code = connection.getResponseCode();
            // 允许 200 OK 和 302 重定向
            return code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_MOVED_TEMP;
        } catch (Exception e) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String fetchFromArchivePage(String version, boolean is64Bit) throws IOException {
        String archiveUrl = "https://downloads.mysql.com/archives/community/";
        Document doc = Jsoup.connect(archiveUrl)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .timeout(15000)
                .followRedirects(true)
                .get();

        // 策略1：直接扫描链接
        Elements allLinks = doc.select("a[href]");
        for (Element link : allLinks) {
            String href = link.attr("href");
            if (href.contains(version) && href.endsWith(".zip") && href.contains("win")) {
                if ((is64Bit && href.contains("winx64")) || (!is64Bit && href.contains("win32"))) {
                    return normalizeUrl(href);
                }
            }
        }

        // 策略2：解析表格
        Elements rows = doc.select("tr");
        for (Element row : rows) {
            Elements cells = row.select("td");
            if (cells.size() < 2) continue;
            String product = cells.get(0).text().toLowerCase();
            String verCell = cells.get(1).text();
            if (product.contains("community server") && verCell.startsWith(version)) {
                Elements downloadLinks = row.select("a[href*=.zip]");
                for (Element dl : downloadLinks) {
                    String href = dl.attr("href");
                    if ((is64Bit && href.contains("winx64")) || (!is64Bit && href.contains("win32"))) {
                        return normalizeUrl(href);
                    }
                }
            }
        }
        throw new IOException("未找到适用于 " + version + " 的 Windows 下载链接");
    }

    private static String normalizeUrl(String href) {
        if (href.startsWith("http://") || href.startsWith("https://")) {
            return href;
        }
        return "https://downloads.mysql.com" + (href.startsWith("/") ? href : "/" + href);
    }

    private static void logMessageStatic(Consumer<String> logger, String msg) {
        if (logger != null) {
            logger.accept(msg);
        }
        System.out.println(msg);
    }

    // ==================== SwingWorker 异步下载实现 ====================

    @Override
    protected Void doInBackground() throws Exception {
        // 使用统一的下载方法（内部已包含降级策略）
        boolean success = downloadFile(downloadUrl, destination, this::logMessage);
        if (!success) {
            throw new IOException("下载失败，所有尝试均无效");
        }
        return null;
    }

    @Override
    protected void process(java.util.List<Integer> chunks) {
        // 后续扩展 UI 进度条逻辑
    }

    @Override
    protected void done() {
        try {
            get();
            logMessage("✅ 下载完成！文件保存至: " + destination);
        } catch (Exception e) {
            logMessage("❌ 下载失败: " + e.getMessage());
        }
    }

    private void logMessage(String msg) {
        if (externalLogger != null) {
            externalLogger.accept(msg);
        }
        if (logArea != null) {
            SwingUtilities.invokeLater(() -> {
                logArea.append(msg + "\n");
                logArea.setCaretPosition(logArea.getDocument().getLength());
            });
        }
    }

    // 测试入口
    public static void main(String[] args) {
        try {
            String version = "8.0.35";
            boolean is64 = true;
            String url = fetchDownloadUrl(version, is64);
            System.out.println("最终下载链接: " + url);

            Path tempFile = Files.createTempFile("mysql-test-", ".zip");
            boolean ok = downloadFile(url, tempFile, System.out::println);
            System.out.println("下载结果: " + (ok ? "成功" : "失败"));
            Files.deleteIfExists(tempFile);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}