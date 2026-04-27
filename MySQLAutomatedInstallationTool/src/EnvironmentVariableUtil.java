import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Windows 环境变量操作工具类（改进版）
 */
public class EnvironmentVariableUtil {

    private static final String ENV_PATH = "PATH";
    private static final String REG_KEY =
            "HKLM\\SYSTEM\\CurrentControlSet\\Control\\Session Manager\\Environment";

    /**
     * 向系统 PATH 变量追加新路径（若未存在）
     */
    public static void addToSystemPath(String newPath) throws IOException {
        String currentPath = WinRegistry.getString(
                WinRegistry.HKEY_LOCAL_MACHINE, REG_KEY, ENV_PATH);
        if (currentPath == null) currentPath = "";

        String normalizedNew = newPath.trim().replace('\\', '/').toLowerCase();
        boolean alreadyExists = false;
        for (String part : currentPath.split(";")) {
            if (part.trim().replace('\\', '/').toLowerCase().equals(normalizedNew)) {
                alreadyExists = true;
                break;
            }
        }

        if (!alreadyExists) {
            String updatedPath = currentPath.isEmpty() ? newPath : currentPath + ";" + newPath;
            WinRegistry.setString(
                    WinRegistry.HKEY_LOCAL_MACHINE, REG_KEY, ENV_PATH, updatedPath);

            // 验证写入
            String verifyPath = WinRegistry.getString(
                    WinRegistry.HKEY_LOCAL_MACHINE, REG_KEY, ENV_PATH);
            if (!updatedPath.equals(verifyPath)) {
                throw new IOException("环境变量写入验证失败，可能权限不足。");
            }
            broadcastEnvironmentChange();
        }
    }

    /**
     * 通知系统环境变量已更改（使用 .NET 方法，自动广播）
     */
    private static void broadcastEnvironmentChange() {
        try {
            String psCommand =
                    "[Environment]::SetEnvironmentVariable('Path', " +
                            "[Environment]::GetEnvironmentVariable('Path', 'Machine'), 'Machine')";
            new ProcessBuilder("powershell", "-Command", psCommand).start();
        } catch (IOException e) {
            System.err.println("环境变量广播失败: " + e.getMessage());
        }
    }

    /**
     * 从系统 PATH 变量中移除包含指定关键字的路径
     */
    public static void removeFromSystemPath(String keyword) throws IOException {
        String currentPath = WinRegistry.getString(
                WinRegistry.HKEY_LOCAL_MACHINE, REG_KEY, ENV_PATH);
        if (currentPath == null) currentPath = "";

        StringBuilder newPathBuilder = new StringBuilder();
        boolean found = false;

        for (String part : currentPath.split(";")) {
            String trimmedPart = part.trim();
            if (!trimmedPart.isEmpty() && !trimmedPart.toLowerCase().contains(keyword.toLowerCase())) {
                if (newPathBuilder.length() > 0) {
                    newPathBuilder.append("; ");
                }
                newPathBuilder.append(trimmedPart);
            } else if (!trimmedPart.isEmpty()) {
                found = true;
            }
        }

        if (found) {
            String newPath = newPathBuilder.toString();
            WinRegistry.setString(
                    WinRegistry.HKEY_LOCAL_MACHINE, REG_KEY, ENV_PATH, newPath);

            // 验证写入
            String verifyPath = WinRegistry.getString(
                    WinRegistry.HKEY_LOCAL_MACHINE, REG_KEY, ENV_PATH);
            if (!newPath.equals(verifyPath)) {
                throw new IOException("环境变量写入验证失败，可能权限不足。");
            }
            broadcastEnvironmentChange();
        }
    }

    /**
     * Windows 注册表操作封装（命令方式增强版）
     */
    public static class WinRegistry {
        public static final int HKEY_LOCAL_MACHINE = 0x80000002;
        private static final Charset ANSI_CHARSET = getAnsiCharset();

        private static Charset getAnsiCharset() {
            try {
                String cp = System.getProperty("sun.jnu.encoding");
                if (cp == null) cp = System.getProperty("file.encoding");
                return Charset.forName(cp != null ? cp : "GBK");
            } catch (Exception e) {
                return Charset.defaultCharset();
            }
        }

        /**
         * 读取注册表字符串值
         */
        public static String getString(int hkey, String key, String valueName) throws IOException {
            Process p = Runtime.getRuntime().exec(
                    "reg query \"" + key + "\" /v " + valueName);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), ANSI_CHARSET))) {
                StringBuilder output = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
                p.waitFor();

                // 改进的解析：查找包含值名称和类型的那一行
                Pattern pattern = Pattern.compile(
                        "^\\s*" + Pattern.quote(valueName) + "\\s+REG_(?:EXPAND_)?SZ\\s+(.+)$",
                        Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);
                Matcher m = pattern.matcher(output.toString());
                if (m.find()) {
                    return m.group(1).trim();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "";
        }

        /**
         * 设置注册表字符串值（类型为 REG_EXPAND_SZ）
         */
        public static void setString(int hkey, String key, String valueName, String data)
                throws IOException {
            String escapedData = data.replace("\"", "\\\"");
            String cmd = String.format(
                    "reg add \"%s\" /v %s /t REG_EXPAND_SZ /d \"%s\" /f",
                    key, valueName, escapedData);
            Process p = Runtime.getRuntime().exec(cmd);
            try {
                int exitCode = p.waitFor();
                if (exitCode != 0) {
                    throw new IOException("reg add 命令执行失败，退出码: " + exitCode);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("命令被中断", e);
            }
        }
    }
}
