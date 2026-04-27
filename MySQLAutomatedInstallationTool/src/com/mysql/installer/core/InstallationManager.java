package com.mysql.installer.core;

import com.mysql.installer.download.MySQLDownloader;
import com.mysql.installer.services.ServiceManager;
import com.mysql.installer.utils.EnvironmentVariableUtil;
import com.mysql.installer.utils.ZipUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class InstallationManager {
    private final AtomicBoolean cancelled;
    private final List<Runnable> rollbackActions;
    private Process currentProcess;
    private Path tempZipFile;
    private Path dataDirBackup;
    private Path iniBackup;
    private InstallationListener listener;

    public InstallationManager(InstallationListener listener) {
        this.cancelled = new AtomicBoolean(false);
        this.rollbackActions = new ArrayList<>();
        this.listener = listener;
    }

    public void install(String version, String installPath, String port, String serviceName, char[] passwordChars) throws IOException {
        listener.onProgress(5, "开始安装流程");

        Path basePath = Paths.get(installPath);
        Path binDir = basePath.resolve("bin");
        Path mysqldExe = binDir.resolve("mysqld.exe");

        if (!Files.exists(mysqldExe)) {
            listener.onLog("📥 未检测到 mysqld.exe，开始自动下载 MySQL " + version + " ...");
            boolean downloadSuccess = downloadAndExtractMySQL(version, basePath);
            if (!downloadSuccess) {
                listener.onLog("❌ 下载或解压失败，安装终止。");
                performRollback();
                return;
            }
            if (!Files.exists(mysqldExe)) {
                listener.onLog("❌ 解压后未找到 " + mysqldExe + "，请检查 ZIP 包结构。");
                performRollback();
                return;
            }
            listener.onLog("✅ MySQL 文件准备就绪。");
        } else {
            listener.onLog("ℹ️ 检测到已有 MySQL 文件，跳过下载步骤。");
        }
        listener.onProgress(30, "文件准备完成");

        int portNum = Integer.parseInt(port);
        // 端口检查和服务管理逻辑将在 ServiceManager 中实现

        // 后续安装步骤...
    }

    private boolean downloadAndExtractMySQL(String version, Path targetDir) throws IOException {
        listener.onLog("🔍 正在解析下载链接...");
        boolean is64Bit = SystemInfo.is64Bit();
        String downloadUrl = MySQLDownloader.fetchDownloadUrl(version, is64Bit);
        listener.onLog("下载地址: " + downloadUrl);

        tempZipFile = Files.createTempFile("mysql-" + version + "-", ".zip");
        tempZipFile.toFile().deleteOnExit();
        listener.onLog("📁 临时文件: " + tempZipFile);

        // 学校机房环境优化
        if (SystemInfo.isSchoolEnvironment()) {
            int delay = new java.util.Random().nextInt(5000) + 1000;
            listener.onLog("🏫 检测到学校环境，添加随机延迟 " + (delay/1000) + " 秒...");
            try { Thread.sleep(delay); } catch (InterruptedException ignored) {}
        }

        listener.onLog("⏬ 开始下载 MySQL ZIP 包，请稍候...");
        boolean downloaded = MySQLDownloader.downloadFile(downloadUrl, tempZipFile, listener::onLog);
        if (!downloaded) {
            listener.onLog("❌ 下载失败");
            return false;
        }
        listener.onLog("✅ 下载完成，文件大小: " + Files.size(tempZipFile) / 1024 / 1024 + " MB");

        listener.onLog("📦 正在解压文件到 " + targetDir + " ...");
        Files.createDirectories(targetDir);
        ZipUtils.extractZipWithProgress(tempZipFile, targetDir, (current, total) -> {
            int progress = (int) (current * 100 / total);
            listener.onProgress(10 + progress * 20 / 100, "解压中...");
        });

        return true;
    }

    public void performRollback() {
        listener.onLog(">>> 正在回滚操作，请稍候...");
        for (Runnable action : rollbackActions) {
            try {
                action.run();
            } catch (Exception e) {
                listener.onLog("⚠️ 回滚操作失败: " + e.getMessage());
            }
        }
        rollbackActions.clear();
    }

    public void cancel() {
        cancelled.set(true);
        if (currentProcess != null && currentProcess.isAlive()) {
            killProcessTree(currentProcess);
        }
    }

    private void killProcessTree(Process process) {
        // 实现进程树杀死逻辑
    }

    public interface InstallationListener {
        void onLog(String message);
        void onProgress(int progress, String message);
    }
}
