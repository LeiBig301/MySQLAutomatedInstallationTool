import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MySQL 版本列表获取工具（多源爬取 + 超全备选列表）
 */
public class MySQLVersionFetcher {

    // 超全备选版本列表（从 MySQL 5.0 到最新 9.x 的主要版本）
    private static final List<String> FALLBACK_VERSIONS = buildFallbackVersions();

    private static List<String> buildFallbackVersions() {
        List<String> list = new ArrayList<>();
        // 9.x 系列
        addVersions(list, "9.5", 0, 1);
        addVersions(list, "9.4", 0, 1);
        addVersions(list, "9.3", 0, 1);
        addVersions(list, "9.2", 0, 1);
        addVersions(list, "9.1", 0, 1);
        addVersions(list, "9.0", 0, 2);
        // 8.4 LTS 系列
        addVersions(list, "8.4", 0, 5);
        // 8.0 系列（常用）
        // 避免添加已经被官方移除的版本
        for (int i = 11; i <= 41; i++) {
            // 跳过已被官方移除的版本
            if (i != 29 && i != 38) {
                list.add("8.0." + i);
            }
        }
        // 5.7 系列
        addVersions(list, "5.7", 10, 44);
        // 5.6 系列
        addVersions(list, "5.6", 10, 51);
        // 5.5 系列
        addVersions(list, "5.5", 8, 62);
        // 5.1 系列
        addVersions(list, "5.1", 30, 73);
        // 5.0 系列
        addVersions(list, "5.0", 15, 96);
        // 去重并排序
        list = new ArrayList<>(new LinkedHashSet<>(list));
        list.sort((v1, v2) -> compareVersions(v2, v1));
        return list;
    }

    private static void addVersions(List<String> list, String base, int startPatch, int endPatch) {
        for (int i = startPatch; i <= endPatch; i++) {
            list.add(base + "." + i);
        }
    }

    public static List<String> fetchAllVersions() {
        List<String> versions = new ArrayList<>();
        boolean networkFailed = false;

        // 尝试方式1：标准归档页面（带重试）
        try {
            versions = fetchWithRetry("https://downloads.mysql.com/archives/community/");
            if (!versions.isEmpty()) {
                return versions;
            }
        } catch (Exception e) {
            networkFailed = true;
        }

        // 尝试方式2：dev 页面（带重试）
        try {
            versions = fetchDevWithRetry();
            if (!versions.isEmpty()) {
                return versions;
            }
        } catch (Exception e) {
            networkFailed = true;
        }

        // 尝试方式3：使用镜像站点
        try {
            versions = fetchFromMirrorSite();
            if (!versions.isEmpty()) {
                return versions;
            }
        } catch (Exception e) {
            networkFailed = true;
        }

        if (networkFailed) {
            System.err.println("MySQL 官网版本列表获取失败，使用内置备选列表（共 " + FALLBACK_VERSIONS.size() + " 个版本）。");
        }

        return new ArrayList<>(FALLBACK_VERSIONS);
    }

    private static List<String> fetchWithRetry(String url) throws IOException {
        int maxRetries = 3;
        int retryDelay = 2000; // 2 seconds

        for (int i = 0; i < maxRetries; i++) {
            try {
                Document doc = Jsoup.connect(url)
                        .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                        .header("Accept-Language", "en-US,en;q=0.9,zh-CN;q=0.8,zh;q=0.7")
                        .header("Accept-Encoding", "gzip, deflate, br")
                        .header("Connection", "keep-alive")
                        .header("Upgrade-Insecure-Requests", "1")
                        .header("Sec-Fetch-Dest", "document")
                        .header("Sec-Fetch-Mode", "navigate")
                        .header("Sec-Fetch-Site", "same-origin")
                        .header("Sec-Fetch-User", "?1")
                        .timeout(20000)
                        .followRedirects(true)
                        .ignoreHttpErrors(true)
                        .get();
                
                List<String> versions = parseVersionFromDocument(doc);
                if (!versions.isEmpty()) {
                    return versions;
                }
            } catch (IOException e) {
                if (i < maxRetries - 1) {
                    System.err.println("尝试获取版本列表失败，正在重试... (" + (i + 1) + "/" + maxRetries + ")");
                    try {
                        Thread.sleep(retryDelay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    retryDelay *= 2; // 指数退避
                } else {
                    throw e;
                }
            }
        }
        return new ArrayList<>();
    }

    private static List<String> fetchDevWithRetry() throws IOException {
        int maxRetries = 3;
        int retryDelay = 2000; // 2 seconds

        for (int i = 0; i < maxRetries; i++) {
            try {
                String devUrl = "https://dev.mysql.com/downloads/mysql/";
                Document doc = Jsoup.connect(devUrl)
                        .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                        .header("Accept-Language", "en-US,en;q=0.9,zh-CN;q=0.8,zh;q=0.7")
                        .header("Accept-Encoding", "gzip, deflate, br")
                        .header("Connection", "keep-alive")
                        .header("Upgrade-Insecure-Requests", "1")
                        .header("Sec-Fetch-Dest", "document")
                        .header("Sec-Fetch-Mode", "navigate")
                        .header("Sec-Fetch-Site", "same-origin")
                        .header("Sec-Fetch-User", "?1")
                        .timeout(20000)
                        .followRedirects(true)
                        .ignoreHttpErrors(true)
                        .get();

                List<String> versions = new ArrayList<>();
                Elements options = doc.select("select#version option");
                Pattern versionPattern = Pattern.compile("(\\d+\\.\\d+\\.\\d+)");
                for (Element opt : options) {
                    String val = opt.attr("value");
                    Matcher m = versionPattern.matcher(val);
                    if (m.find()) {
                        versions.add(m.group(1));
                    }
                }
                versions.sort((v1, v2) -> compareVersions(v2, v1));
                
                if (!versions.isEmpty()) {
                    return versions;
                }
            } catch (IOException e) {
                if (i < maxRetries - 1) {
                    System.err.println("尝试获取开发页面版本列表失败，正在重试... (" + (i + 1) + "/" + maxRetries + ")");
                    try {
                        Thread.sleep(retryDelay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    retryDelay *= 2; // 指数退避
                } else {
                    throw e;
                }
            }
        }
        return new ArrayList<>();
    }

    private static List<String> fetchFromMirrorSite() throws IOException {
        // 尝试从镜像站点获取版本信息
        String[] mirrorSites = {
            "https://mirrors.tuna.tsinghua.edu.cn/mysql/downloads/",
            "https://mirrors.aliyun.com/mysql/"
        };

        for (String mirror : mirrorSites) {
            try {
                Document doc = Jsoup.connect(mirror)
                        .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        .timeout(20000)
                        .followRedirects(true)
                        .ignoreHttpErrors(true)
                        .get();

                List<String> versions = new ArrayList<>();
                // 尝试从镜像站点的页面中提取版本信息
                Elements links = doc.select("a[href*='mysql-']");
                Pattern versionPattern = Pattern.compile("mysql-(\\d+\\.\\d+\\.\\d+)");
                for (Element link : links) {
                    String href = link.attr("href");
                    Matcher m = versionPattern.matcher(href);
                    if (m.find()) {
                        String version = m.group(1);
                        if (!versions.contains(version)) {
                            versions.add(version);
                        }
                    }
                }
                versions.sort((v1, v2) -> compareVersions(v2, v1));
                
                if (!versions.isEmpty()) {
                    return versions;
                }
            } catch (Exception e) {
                // 忽略镜像站点的错误，尝试下一个
                continue;
            }
        }
        return new ArrayList<>();
    }



    private static List<String> parseVersionFromDocument(Document doc) {
        List<String> versions = new ArrayList<>();
        Elements rows = doc.select("tr");
        Pattern versionPattern = Pattern.compile("(\\d+\\.\\d+\\.\\d+)");

        for (Element row : rows) {
            Elements cells = row.select("td");
            if (cells.size() > 1) {
                String product = cells.get(0).text();
                String versionCell = cells.get(1).text();
                if (product.contains("MySQL Community Server")) {
                    Matcher m = versionPattern.matcher(versionCell);
                    if (m.find()) {
                        String version = m.group(1);
                        if (!versions.contains(version)) {
                            versions.add(version);
                        }
                    }
                }
            }
        }
        versions.sort((v1, v2) -> compareVersions(v2, v1));
        return versions;
    }

    private static int compareVersions(String v1, String v2) {
        String[] parts1 = v1.split("\\.");
        String[] parts2 = v2.split("\\.");
        int len = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < len; i++) {
            int p1 = i < parts1.length ? Integer.parseInt(parts1[i]) : 0;
            int p2 = i < parts2.length ? Integer.parseInt(parts2[i]) : 0;
            if (p1 != p2) return Integer.compare(p1, p2);
        }
        return 0;
    }

    public static List<String> getFallbackVersions() {
        return new ArrayList<>(FALLBACK_VERSIONS);
    }
    
    /**
     * 根据Windows版本过滤MySQL版本
     * @param allVersions 所有可用的MySQL版本
     * @return 与当前Windows版本兼容的MySQL版本列表
     */
    public static List<String> filterVersionsByWindowsVersion(List<String> allVersions) {
        List<String> filteredVersions = new ArrayList<>();
        boolean isWindows7 = isWindows7();
        
        for (String version : allVersions) {
            if (isCompatibleWithWindowsVersion(version, isWindows7)) {
                filteredVersions.add(version);
            }
        }
        
        return filteredVersions;
    }
    
    /**
     * 检查当前是否为Windows 7
     * @return true if Windows 7
     */
    private static boolean isWindows7() {
        String osVersion = System.getProperty("os.version");
        return osVersion.startsWith("6.1"); // Windows 7 version number is 6.1
    }
    
    /**
     * 检查MySQL版本是否与Windows版本兼容
     * @param mysqlVersion MySQL版本
     * @param isWindows7 是否为Windows 7
     * @return true if compatible
     */
    private static boolean isCompatibleWithWindowsVersion(String mysqlVersion, boolean isWindows7) {
        // Windows 7 不支持 MySQL 9.x 版本
        if (isWindows7) {
            String majorVersion = mysqlVersion.split("\\.")[0];
            int major = Integer.parseInt(majorVersion);
            return major < 9;
        }
        // Windows 10/11 支持所有版本
        return true;
    }
}