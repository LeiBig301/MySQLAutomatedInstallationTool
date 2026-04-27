import java.util.Locale;

/**
 * 系统信息工具类
 * 用于判断当前操作系统类型及架构
 */
public class SystemInfo {

    /**
     * 判断当前操作系统是否为 Windows
     */
    public static boolean isWindows() {
        String osName = System.getProperty("os.name");
        return osName != null && osName.toLowerCase(Locale.ENGLISH).contains("win");
    }

    /**
     * 判断是否为 64 位架构
     */
    public static boolean is64Bit() {
        String arch = System.getProperty("os.arch");
        if (arch == null) return false;
        arch = arch.toLowerCase(Locale.ENGLISH);
        // 覆盖 amd64, x86_64, aarch64 等
        return arch.contains("64");
    }

    /**
     * 获取系统架构标识符（用于下载文件名）
     */
    public static String getSystemArch() {
        return is64Bit() ? "x64" : "x86";
    }

    public static void main(String[] args) {
        System.out.println("Is Windows: " + isWindows());
        System.out.println("Is 64-bit: " + is64Bit());
        System.out.println("System Arch: " + getSystemArch());
    }
}