package com.mysql.installer.core;

import java.net.InetAddress;
import java.net.UnknownHostException;

public class SystemInfo {
    public static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("windows");
    }

    public static boolean is64Bit() {
        return System.getProperty("os.arch").contains("64") || System.getenv("PROCESSOR_ARCHITEW6432") != null;
    }

    public static boolean isSchoolEnvironment() {
        try {
            // 检查主机名是否包含学校相关关键词
            String hostname = InetAddress.getLocalHost().getHostName().toLowerCase();
            if (hostname.contains("school") || hostname.contains("edu") || hostname.contains("campus") || 
                hostname.contains("student") || hostname.contains("class") || hostname.contains("lab")) {
                return true;
            }

            // 检查 IP 地址是否为内网地址
            String ip = InetAddress.getLocalHost().getHostAddress();
            if (ip.startsWith("10.") || ip.startsWith("172.16.") || ip.startsWith("192.168.")) {
                // 内网地址，可能是学校网络
                return true;
            }
        } catch (UnknownHostException e) {
            // 忽略异常
        }
        return false;
    }

    public static String getWindowsVersion() {
        return System.getProperty("os.version");
    }

    public static String getWindowsVersionName() {
        String osVersion = getWindowsVersion();
        return getWindowsVersionName(osVersion);
    }

    public static String getWindowsVersionName(String osVersion) {
        switch (osVersion) {
            case "10.0":
                return "10/11";
            case "6.3":
                return "8.1";
            case "6.2":
                return "8";
            case "6.1":
                return "7";
            case "6.0":
                return "Vista";
            case "5.2":
                return "Server 2003";
            case "5.1":
                return "XP";
            case "5.0":
                return "2000";
            default:
                return osVersion;
        }
    }

    public static boolean isWindows7() {
        String osVersion = getWindowsVersion();
        return osVersion.startsWith("6.1");
    }
}
