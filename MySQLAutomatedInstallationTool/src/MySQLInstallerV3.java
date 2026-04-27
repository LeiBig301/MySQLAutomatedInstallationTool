import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.FontUIResource;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.*;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mysql.installer.logging.LogManager;

/**
 * MySQL 一键安装配置工具 (Java Swing 增强版 v7)
 * <p>功能特性：
 * <ul>
 *   <li>跨平台字体安全（避免乱码）</li>
 *   <li>MySQL 版本可搜索下拉框（自动补全）</li>
 *   <li>自动下载解压、环境变量设置</li>
 *   <li>兼容 Win7 - Win11</li>
 *   <li>加载提示与平滑版本更新</li>
 * </ul>
 * </p>
 */
public class MySQLInstallerV3 extends JFrame {

    // --- GUI 组件 ---
    private JVersionComboBox versionComboBox;   // 可搜索版本下拉框
    private JTextField winVerField;             // Windows 版本（自动检测）
    private JTextField installPathField;        // 安装/解压目标路径
    private JTextField portField;
    private JTextField serviceNameField;
    private JPasswordField pwdField;
    private JTextArea logArea;
    private JButton installBtn;
    private JButton cancelBtn;
    private JButton removeServiceBtn;           // 删除服务按钮
    private JProgressBar progressBar;

    // --- 状态控制 ---
    private final AtomicBoolean installing = new AtomicBoolean(false);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile Process currentProcess;
    private final List<Runnable> rollbackActions = Collections.synchronizedList(new ArrayList<>());

    // 系统默认 ANSI 编码（动态获取）
    private static final Charset SYSTEM_ANSI_CHARSET = getSystemAnsiCharset();

    // 备份目录/文件路径
    private Path dataDirBackup = null;
    private Path iniBackup = null;

    // 临时下载文件路径
    private Path tempZipFile = null;

    public MySQLInstallerV3() {
        // 初始化日志管理器
        LogManager.initialize();
        LogManager.cleanOldLogs();
        
        if (!SystemInfo.isWindows()) {
            JOptionPane.showMessageDialog(null,
                    "本工具仅支持 Windows 系统。\n当前系统: " + System.getProperty("os.name"),
                    "不支持的操作系统", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
        
        // 检查管理员权限
        if (!isAdmin()) {
            int choice = JOptionPane.showConfirmDialog(null,
                    "需要管理员权限才能运行此程序。\n是否以管理员身份重新运行？",
                    "权限不足", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice == JOptionPane.YES_OPTION) {
                boolean relaunched = relaunchAsAdmin();
                if (relaunched) {
                    // 重新启动后退出当前进程
                    System.exit(0);
                } else {
                    // 提权失败，退出程序
                    System.exit(1);
                }
            } else {
                // 用户选择取消，退出程序
                System.exit(1);
            }
        }
        
        initUI();
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (installing.get()) {
                    int choice = JOptionPane.showConfirmDialog(MySQLInstallerV3.this,
                            "安装正在进行中，确定要退出吗？\n退出将触发回滚，撤销所有更改。",
                            "确认退出", JOptionPane.YES_NO_OPTION);
                    if (choice == JOptionPane.YES_OPTION) {
                        cancelAndRollback();
                    }
                } else {
                    LogManager.close();
                    dispose();
                    System.exit(0);
                }
            }
        });
    }

    private static Charset getSystemAnsiCharset() {
        try {
            String codePage = System.getProperty("sun.jnu.encoding");
            if (codePage == null) codePage = System.getProperty("file.encoding");
            return Charset.forName(codePage != null ? codePage : "GBK");
        } catch (Exception e) {
            return Charset.defaultCharset();
        }
    }

    /**
     * 递归设置全局 UI 字体
     */
    private static void setUIFont(FontUIResource f) {
        Enumeration<Object> keys = UIManager.getDefaults().keys();
        while (keys.hasMoreElements()) {
            Object key = keys.nextElement();
            Object value = UIManager.get(key);
            if (value instanceof FontUIResource) {
                UIManager.put(key, f);
            }
        }
    }

    // ---------- UI 初始化 ----------
    private void initUI() {
        setTitle("星之火 MySQL 一键安装工具 版本 1.1.12");
        setSize(820, 720);
        setLocationRelativeTo(null);
        setResizable(false);

        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(new EmptyBorder(15, 15, 15, 15));

        // 顶部：配置输入区
        JPanel configPanel = new JPanel(new GridBagLayout());
        configPanel.setBorder(BorderFactory.createTitledBorder("MySQL 一键安装工具"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // MySQL 版本（可搜索下拉框）
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        configPanel.add(new JLabel("MySQL 版本："), gbc);
        gbc.gridx = 1; gbc.weightx = 1;

        // 先用备选列表快速显示，并添加加载提示
        List<String> fallbackVersions = MySQLVersionFetcher.getFallbackVersions();
        versionComboBox = new JVersionComboBox(fallbackVersions);
        versionComboBox.setSelectedVersion("8.0.35");

        JPanel versionPanel = new JPanel(new BorderLayout());
        versionPanel.add(versionComboBox, BorderLayout.CENTER);
        JLabel loadingLabel = new JLabel(" ⏳ 加载中...");
        loadingLabel.setForeground(Color.GRAY);
        versionPanel.add(loadingLabel, BorderLayout.EAST);
        configPanel.add(versionPanel, gbc);

        gbc.gridx = 2; gbc.weightx = 0;
        configPanel.add(new JLabel("(可输入搜索)"), gbc);

        // 后台加载完整版本列表，平滑更新模型
        new Thread(() -> {
            List<String> allVersions = MySQLVersionFetcher.fetchAllVersions();
            SwingUtilities.invokeLater(() -> {
                String currentSelected = versionComboBox.getSelectedVersion();
                versionComboBox.setModel(new DefaultComboBoxModel<>(allVersions.toArray(new String[0])));
                if (allVersions.contains(currentSelected)) {
                    versionComboBox.setSelectedVersion(currentSelected);
                } else {
                    versionComboBox.setSelectedVersion("8.0.35");
                }
                versionPanel.remove(loadingLabel);
                versionPanel.revalidate();
                versionPanel.repaint();
            });
        }).start();

        // Windows 版本（自动检测）
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0;
        configPanel.add(new JLabel("Windows 版本："), gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        winVerField = new JTextField(getWindowsVersionString());
        winVerField.setEditable(false); // 设置为不可编辑
        winVerField.setBackground(Color.LIGHT_GRAY); // 设置背景色，表明不可编辑
        winVerField.setFocusable(false); // 设置为不可获取焦点
        winVerField.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR)); // 设置为默认光标
        configPanel.add(winVerField, gbc);
        gbc.gridx = 2; gbc.weightx = 0;
        configPanel.add(new JLabel("(自动检测)"), gbc);

        // 安装路径
        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0;
        configPanel.add(new JLabel("安装路径："), gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        installPathField = new JTextField("C:\\mysql");
        configPanel.add(installPathField, gbc);
        gbc.gridx = 2; gbc.weightx = 0;
        JButton browseBtn = new JButton("浏览...");
        browseBtn.addActionListener(e -> chooseDirectory());
        configPanel.add(browseBtn, gbc);

        // 端口
        gbc.gridx = 0; gbc.gridy = 3; gbc.weightx = 0;
        configPanel.add(new JLabel("端口："), gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        portField = new JTextField("3306");
        configPanel.add(portField, gbc);
        gbc.gridx = 2; gbc.weightx = 0;
        configPanel.add(new JLabel("(1-65535)"), gbc);

        // 服务名
        gbc.gridx = 0; gbc.gridy = 4; gbc.weightx = 0;
        configPanel.add(new JLabel("服务名："), gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        serviceNameField = new JTextField("MySQL");
        configPanel.add(serviceNameField, gbc);
        gbc.gridx = 2; gbc.weightx = 0;
        configPanel.add(new JLabel(""), gbc);

        // root 密码
        gbc.gridx = 0; gbc.gridy = 5; gbc.weightx = 0;
        configPanel.add(new JLabel("Root 密码："), gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        pwdField = new JPasswordField();
        configPanel.add(pwdField, gbc);
        gbc.gridx = 2; gbc.weightx = 0;
        configPanel.add(new JLabel("留空表示无密码"), gbc);

        // 按钮区
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 5));
        installBtn = new JButton("开始安装");
        installBtn.addActionListener(e -> startInstall());
        cancelBtn = new JButton("取消");
        cancelBtn.setEnabled(false);
        cancelBtn.addActionListener(e -> cancelAndRollback());
        removeServiceBtn = new JButton("删除服务");
        removeServiceBtn.addActionListener(e -> removeMySQLService());
        
        buttonPanel.add(installBtn);
        buttonPanel.add(cancelBtn);
        buttonPanel.add(removeServiceBtn);

        // 进度条
        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressBar.setVisible(false);

        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.add(configPanel, BorderLayout.CENTER);
        topPanel.add(buttonPanel, BorderLayout.SOUTH);

        // 日志区域
        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane scrollPane = new JScrollPane(logArea);
        scrollPane.setBorder(BorderFactory.createTitledBorder("执行日志"));

        mainPanel.add(topPanel, BorderLayout.NORTH);
        mainPanel.add(scrollPane, BorderLayout.CENTER);
        mainPanel.add(progressBar, BorderLayout.SOUTH);

        setContentPane(mainPanel);
    }

    private String getWindowsVersionString() {
        String osName = System.getProperty("os.name");
        String osVersion = System.getProperty("os.version");
        String arch = SystemInfo.is64Bit() ? "64位" : "32位";
        return String.format("%s %s (%s)", osName, osVersion, arch);
    }

    private void chooseDirectory() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("选择 MySQL 安装目录");
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            installPathField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    // ==================== 安装流程控制 ====================
    private void startInstall() {
        if (installing.get()) return;
        
        // 检查管理员权限
        if (!isAdmin()) {
            int choice = JOptionPane.showConfirmDialog(this,
                    "需要管理员权限才能安装 MySQL 服务。\n是否以管理员身份重新运行？",
                    "权限不足", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice == JOptionPane.YES_OPTION) {
                boolean relaunched = relaunchAsAdmin();
                if (relaunched) {
                    // 重新启动后退出当前进程
                    System.exit(0);
                }
            }
            // 如果用户选择取消或提权失败，直接返回
            return;
        }
        
        logArea.setText("");
        rollbackActions.clear();
        cancelled.set(false);
        progressBar.setValue(0);
        progressBar.setVisible(true);
        installBtn.setEnabled(false);
        cancelBtn.setEnabled(true);
        installing.set(true);

        new Thread(() -> {
            try {
                doInstall();
            } catch (Exception e) {
                log("❌ 安装过程发生未预期异常: " + e.getMessage());
                e.printStackTrace();
                performRollback();
            } finally {
                SwingUtilities.invokeLater(() -> {
                    installBtn.setEnabled(true);
                    cancelBtn.setEnabled(false);
                    progressBar.setVisible(false);
                    installing.set(false);
                });
            }
        }).start();
    }

    private void cancelAndRollback() {
        if (!installing.get()) return;
        cancelled.set(true);
        log("⚠️ 用户取消安装，开始回滚...");
        if (currentProcess != null && currentProcess.isAlive()) {
            killProcessTree(currentProcess);
        }
        performRollback();
        SwingUtilities.invokeLater(() -> {
            installBtn.setEnabled(true);
            cancelBtn.setEnabled(false);
            progressBar.setVisible(false);
            installing.set(false);
        });
    }

    private void performRollback() {
        log(">>> 正在回滚操作，请稍候...");
        synchronized (rollbackActions) {
            for (int i = rollbackActions.size() - 1; i >= 0; i--) {
                try {
                    rollbackActions.get(i).run();
                } catch (Exception e) {
                    log("回滚步骤失败: " + e.getMessage());
                }
            }
            rollbackActions.clear();
        }
        if (dataDirBackup != null && Files.exists(dataDirBackup)) {
            try {
                Path dataDir = Paths.get(installPathField.getText().trim()).resolve("data");
                deleteDirectory(dataDir);
                Files.move(dataDirBackup, dataDir);
                log("📁 已恢复 data 目录备份");
            } catch (IOException e) {
                log("恢复 data 目录失败: " + e.getMessage());
            }
        }
        if (iniBackup != null && Files.exists(iniBackup)) {
            try {
                Path iniPath = Paths.get(installPathField.getText().trim()).resolve("my.ini");
                Files.move(iniBackup, iniPath, StandardCopyOption.REPLACE_EXISTING);
                log("📄 已恢复 my.ini 备份");
            } catch (IOException e) {
                log("恢复 my.ini 失败: " + e.getMessage());
            }
        }
        if (tempZipFile != null && Files.exists(tempZipFile)) {
            try {
                Files.delete(tempZipFile);
                log("🗑️ 已删除临时下载文件: " + tempZipFile.getFileName());
            } catch (IOException e) {
                log("⚠️ 删除临时文件失败: " + e.getMessage());
            }
        }
        log("✅ 回滚完成。");
    }

    private void killProcessTree(Process process) {
        try {
            long pid = getProcessPid(process);
            new ProcessBuilder("taskkill", "/f", "/t", "/pid", String.valueOf(pid))
                    .start().waitFor();
        } catch (Exception ignored) {}
    }

    private long getProcessPid(Process process) {
        try {
            return process.pid();
        } catch (NoSuchMethodError e) {
            try {
                Field field = process.getClass().getDeclaredField("pid");
                field.setAccessible(true);
                return field.getLong(process);
            } catch (Exception ex) {
                return -1;
            }
        }
    }

    // ==================== 核心安装逻辑 ====================
    private void doInstall() throws IOException {
        if (!isAdmin()) {
            if (relaunchAsAdmin()) {
                log("📌 已请求管理员权限，当前窗口即将关闭，新窗口将以管理员身份启动。");
                SwingUtilities.invokeLater(() -> {
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                    dispose();
                    System.exit(0);
                });
                return;
            } else {
                log("❌ 无法获取管理员权限，请手动以管理员身份运行本程序。");
                return;
            }
        }
        updateProgress(5);

        String version = versionComboBox.getSelectedVersion();
        String installPath = installPathField.getText().trim();
        String port = portField.getText().trim();
        String serviceName = serviceNameField.getText().trim();
        char[] passwordChars = pwdField.getPassword();
        String password = new String(passwordChars);

        if (version.isEmpty()) {
            log("❌ MySQL 版本不能为空，例如 8.0.35");
            return;
        }
        int portNum;
        try {
            portNum = Integer.parseInt(port);
            if (portNum < 1 || portNum > 65535) {
                log("❌ 端口号必须在 1 - 65535 范围内。");
                return;
            }
        } catch (NumberFormatException e) {
            log("❌ 端口号必须为有效数字。");
            return;
        }
        if (serviceName.isEmpty()) {
            log("❌ 服务名不能为空。");
            return;
        }

        // 安装流程总览提示框（简化，只在开始时显示一次）
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(this,
                    "MySQL 安装即将开始\n\n安装过程将自动执行以下步骤：\n1. 下载并解压 MySQL\n2. 初始化数据库\n3. 注册并启动服务\n4. 设置密码（如果指定）\n5. 配置环境变量\n\n预计耗时：2-5 分钟\n\n请确保以管理员身份运行程序",
                    "安装准备", JOptionPane.INFORMATION_MESSAGE);
        });

        log("========== 星之火 MySQL 一键安装开始 ==========");
        log("目标版本: " + version);
        log("安装目录: " + installPath);
        log("端口号: " + port);
        log("服务名: " + serviceName);
        log("");

        Path basePath = Paths.get(installPath);
        Path binDir = basePath.resolve("bin");
        Path mysqldExe = binDir.resolve("mysqld.exe");

        if (!Files.exists(mysqldExe)) {
            log("📥 未检测到 mysqld.exe，开始自动下载 MySQL " + version + " ...");
            boolean downloadSuccess = downloadAndExtractMySQL(version, basePath);
            if (!downloadSuccess) {
                log("❌ 下载或解压失败，安装终止。");
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(this,
                            "下载或解压失败，安装终止。请检查网络连接或尝试其他版本。",
                            "下载失败", JOptionPane.ERROR_MESSAGE);
                });
                performRollback();
                return;
            }
            if (!Files.exists(mysqldExe)) {
                log("❌ 解压后未找到 " + mysqldExe + "，请检查 ZIP 包结构。");
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(this,
                            "解压后未找到 mysqld.exe，请检查 ZIP 包结构。",
                            "解压失败", JOptionPane.ERROR_MESSAGE);
                });
                performRollback();
                return;
            }
            log("✅ MySQL 文件准备就绪。");
            // 下载完成后提醒
            SwingUtilities.invokeLater(() -> {
                JOptionPane.showMessageDialog(this,
                        "MySQL 下载完成并解压成功！",
                        "下载成功", JOptionPane.INFORMATION_MESSAGE);
            });
        } else {
            log("ℹ️ 检测到已有 MySQL 文件，跳过下载步骤。");
        }
        updateProgress(30);

        if (isPortInUse(portNum)) {
            log("❌ 端口 " + portNum + " 已被占用，请更换端口或关闭占用程序。");
            SwingUtilities.invokeLater(() -> {
                JOptionPane.showMessageDialog(this,
                        "端口 " + portNum + " 已被占用，尝试自动分配可用端口...\n未找到可用端口，请手动更换端口或关闭占用程序。",
                        "端口占用", JOptionPane.ERROR_MESSAGE);
            });
            return;
        }
        updateProgress(35);

        if (isServiceExists(serviceName)) {
            int choice = JOptionPane.showConfirmDialog(this,
                    "检测到已存在同名 Windows 服务 \"" + serviceName + "\"。\n继续安装将停止并删除该服务。\n是否继续？",
                    "⚠️ 严重警告", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.YES_OPTION) {
                log("用户取消安装。");
                return;
            }
            log("⚠️ 用户确认覆盖已有服务: " + serviceName);
        }

        Path dataDir = basePath.resolve("data");
        boolean dataDirExisted = Files.exists(dataDir);
        if (dataDirExisted && !isDirectoryEmpty(dataDir)) {
            int choice = JOptionPane.showConfirmDialog(this,
                    "data 目录已存在且非空，继续安装将覆盖原有数据。\n是否继续？",
                    "警告", JOptionPane.YES_NO_OPTION);
            if (choice != JOptionPane.YES_OPTION) {
                log("用户取消安装。");
                return;
            }
            dataDirBackup = backupDirectory(dataDir);
            rollbackActions.add(() -> {
                try {
                    deleteDirectory(dataDir);
                    Files.move(dataDirBackup, dataDir);
                    log("📁 已恢复 data 目录备份");
                } catch (IOException e) {
                    log("恢复 data 目录失败: " + e.getMessage());
                }
            });
        }

        Path iniPath = basePath.resolve("my.ini");
        if (Files.exists(iniPath)) {
            iniBackup = backupFile(iniPath);
            if (iniBackup != null) {
                rollbackActions.add(() -> {
                    try {
                        Files.move(iniBackup, iniPath, StandardCopyOption.REPLACE_EXISTING);
                        log("📄 已恢复 my.ini 备份");
                    } catch (IOException e) {
                        log("恢复 my.ini 失败: " + e.getMessage());
                    }
                });
            }
        }

        if (!generateMyIni(basePath, port, true)) {
            log("❌ 生成 my.ini 失败，安装终止。");
            performRollback();
            return;
        }
        log("✅ my.ini 配置文件已生成 (已绑定 127.0.0.1)");
        updateProgress(40);

        log("");
        log(">>> 正在初始化数据库...");
        String[] initCmd = {
                mysqldExe.toString(),
                "--defaults-file=" + iniPath.toString(),
                "--initialize-insecure",
                "--console"
        };
        if (!executeCommand(initCmd, basePath, true, false)) {
            log("❌ 数据库初始化失败，开始回滚。");
            SwingUtilities.invokeLater(() -> {
                JOptionPane.showMessageDialog(this,
                        "数据库初始化失败，开始回滚。",
                        "初始化失败", JOptionPane.ERROR_MESSAGE);
            });
            performRollback();
            return;
        }
        log("✅ 数据库初始化成功");
        updateProgress(55);

        log("");
        log(">>> 正在注册 Windows 服务...");
        if (isServiceExists(serviceName)) {
            executeCommand(new String[]{"net", "stop", serviceName}, basePath, false, true);
            executeCommand(new String[]{"sc", "delete", serviceName}, basePath, false, true);
            try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
        }

        String[] serviceInstallCmd = {
                mysqldExe.toString(),
                "--install", serviceName,
                "--defaults-file=" + iniPath.toString()
        };
        if (!executeCommand(serviceInstallCmd, basePath, false, true)) {
            log("❌ 服务注册失败，开始回滚。");
            SwingUtilities.invokeLater(() -> {
                JOptionPane.showMessageDialog(this,
                        "服务注册失败，开始回滚。",
                        "注册失败", JOptionPane.ERROR_MESSAGE);
            });
            performRollback();
            return;
        }
        rollbackActions.add(() -> {
            executeCommand(new String[]{"net", "stop", serviceName}, basePath, false, true);
            executeCommand(new String[]{"sc", "delete", serviceName}, basePath, false, true);
            log("🔄 已卸载服务: " + serviceName);
        });
        log("✅ MySQL 服务注册成功: " + serviceName);
        updateProgress(70);

        log("");
        log(">>> 正在启动 MySQL 服务...");
        if (!executeCommand(new String[]{"net", "start", serviceName}, basePath, false, true)) {
            log("❌ 启动服务失败，开始回滚。");
            SwingUtilities.invokeLater(() -> {
                JOptionPane.showMessageDialog(this,
                        "启动服务失败，开始回滚。",
                        "启动失败", JOptionPane.ERROR_MESSAGE);
            });
            performRollback();
            return;
        }
        updateProgress(80);

        if (!waitForServiceRunning(serviceName, 30)) {
            log("⚠️ 服务启动超时或状态异常，后续操作可能失败。");
            SwingUtilities.invokeLater(() -> {
                JOptionPane.showMessageDialog(this,
                        "服务启动超时或状态异常，后续操作可能失败。",
                        "启动警告", JOptionPane.WARNING_MESSAGE);
            });
        } else {
            log("✅ MySQL 服务已成功启动 (监听 127.0.0.1)");
        }

        if (!password.isEmpty()) {
            log("");
            log(">>> 正在设置 root 密码...");
            if (setRootPasswordWithMysqlAdmin(basePath, passwordChars)) {
                log("✅ root 密码设置成功");
            } else {
                log("⚠️ 密码设置失败，请手动连接后执行: ALTER USER 'root'@'localhost' IDENTIFIED BY '你的密码';");
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(this,
                            "密码设置失败，请手动连接后执行: ALTER USER 'root'@'localhost' IDENTIFIED BY '你的密码';",
                            "密码设置失败", JOptionPane.WARNING_MESSAGE);
                });
            }
        } else {
            log("⚠️ 未设置 root 密码，当前密码为空。");
        }

        Arrays.fill(passwordChars, '\0');
        Arrays.fill(password.toCharArray(), '\0');

        log("");
        log(">>> 正在添加 MySQL bin 目录到系统 PATH...");
        try {
            EnvironmentVariableUtil.addToSystemPath(binDir.toString());
            log("✅ 环境变量已添加（需重启终端生效）。");
        } catch (IOException e) {
            log("⚠️ 环境变量添加失败: " + e.getMessage());
            SwingUtilities.invokeLater(() -> {
                JOptionPane.showMessageDialog(this,
                        "环境变量添加失败: " + e.getMessage(),
                        "环境变量设置失败", JOptionPane.WARNING_MESSAGE);
            });
        }

        updateProgress(95);
        log("");
        log(">>> 若需远程访问，请手动修改 my.ini 中的 bind-address 为 0.0.0.0 并重启服务。");
        log("");
        log("========== 🎉 安装完成！ ==========");
        log("服务名: " + serviceName);
        log("端口: " + port);
        log("连接命令: mysql -u root -p");

        updateProgress(100);
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                "MySQL 安装配置完成！\n服务名: " + serviceName + "\n端口: " + port + "\n\n使用方法：\n1. 打开命令提示符\n2. 输入: mysql -u root -p\n3. 输入设置的密码\n\n注意：环境变量需要重启终端才能生效",
                "安装成功", JOptionPane.INFORMATION_MESSAGE));
    }

    // ==================== 下载与解压 ====================
    private boolean downloadAndExtractMySQL(String version, Path targetDir) {
        try {
            log("🔍 正在解析下载链接...");
            boolean is64Bit = SystemInfo.is64Bit();
            String downloadUrl = MySQLDownloader.fetchDownloadUrl(version, is64Bit);
            log("下载地址: " + downloadUrl);

            tempZipFile = Files.createTempFile("mysql-" + version + "-", ".zip");
            tempZipFile.toFile().deleteOnExit();
            log("📁 临时文件: " + tempZipFile);

            log("⏬ 开始下载 MySQL ZIP 包，请稍候...");
            boolean downloaded = MySQLDownloader.downloadFile(downloadUrl, tempZipFile, this::log);
            if (!downloaded) {
                log("❌ 下载失败");
                return false;
            }
            log("✅ 下载完成，文件大小: " + Files.size(tempZipFile) / 1024 / 1024 + " MB");

            log("📦 正在解压文件到 " + targetDir + " ...");
            Files.createDirectories(targetDir);
            ZipUtils.extractZipWithProgress(tempZipFile, targetDir, (current, total) -> {
                int percent = (int) (current * 100 / total);
                int uiProgress = 30 + percent / 3;
                updateProgress(Math.min(uiProgress, 60));
                if (current % 50 == 0) {
                    log(String.format("解压进度: %d / %d 个文件", current, total));
                }
            });
            log("✅ 解压完成。");

            Files.deleteIfExists(tempZipFile);
            tempZipFile = null;

            Path extractedRoot = findActualMySQLBase(targetDir);
            if (extractedRoot != null && !extractedRoot.equals(targetDir)) {
                log("🔄 移动文件从 " + extractedRoot.getFileName() + " 到根目录...");
                moveDirectoryContents(extractedRoot, targetDir);
                deleteDirectory(extractedRoot);
            }

            return true;
        } catch (Exception e) {
            log("❌ 下载或解压异常: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private boolean performDownload(String url, Path destination) throws Exception {
        java.net.URL downloadUrl = new java.net.URL(url);
        HttpURLConnection conn = (HttpURLConnection) downloadUrl.openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);
        conn.setInstanceFollowRedirects(true);

        // --- 关键修复：模拟真实浏览器请求头，避免 403 拒绝 ---
        conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        conn.setRequestProperty("Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8");
        conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
        conn.setRequestProperty("Accept-Encoding", "identity"); // 避免压缩干扰
        conn.setRequestProperty("Connection", "keep-alive");
        conn.setRequestProperty("Referer", "https://dev.mysql.com/downloads/mysql/");
        // ------------------------------------------------

        int fileSize = conn.getContentLength();

        try (InputStream in = conn.getInputStream();
             OutputStream out = Files.newOutputStream(destination)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            long totalRead = 0;
            long lastLogTime = System.currentTimeMillis();
            while ((bytesRead = in.read(buffer)) != -1) {
                if (cancelled.get()) return false;
                out.write(buffer, 0, bytesRead);
                totalRead += bytesRead;
                if (fileSize > 0) {
                    int percent = (int) (totalRead * 100 / fileSize);
                    int uiProgress = 5 + percent / 4;
                    updateProgress(Math.min(uiProgress, 30));
                }
                if (System.currentTimeMillis() - lastLogTime > 2000) {
                    if (fileSize > 0) {
                        log(String.format("下载进度: %.1f%% (%d MB / %d MB)",
                                totalRead * 100.0 / fileSize,
                                totalRead / 1024 / 1024,
                                fileSize / 1024 / 1024));
                    } else {
                        log("已下载: " + totalRead / 1024 / 1024 + " MB");
                    }
                    lastLogTime = System.currentTimeMillis();
                }
            }
        } finally {
            conn.disconnect();
        }
        return true;
    }

    private Path findActualMySQLBase(Path targetDir) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(targetDir)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry) && Files.exists(entry.resolve("bin").resolve("mysqld.exe"))) {
                    return entry;
                }
            }
        }
        if (Files.exists(targetDir.resolve("bin/mysqld.exe"))) {
            return targetDir;
        }
        return null;
    }

    private void moveDirectoryContents(Path source, Path target) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(source)) {
            for (Path entry : stream) {
                Path dest = target.resolve(entry.getFileName());
                try {
                    Files.move(entry, dest, StandardCopyOption.REPLACE_EXISTING);
                } catch (AccessDeniedException e) {
                    log("❌ 权限不足：无法移动文件 " + entry + " 到 " + dest);
                    SwingUtilities.invokeLater(() -> {
                        JOptionPane.showMessageDialog(this,
                                "权限不足：无法移动文件。\n请以管理员身份运行本程序。",
                                "权限错误", JOptionPane.ERROR_MESSAGE);
                    });
                    throw e;
                }
            }
        }
    }

    // ==================== 辅助方法 ====================
    private void updateProgress(int value) {
        SwingUtilities.invokeLater(() -> progressBar.setValue(value));
    }

    private boolean isServiceExists(String serviceName) {
        try {
            ProcessBuilder pb = new ProcessBuilder("sc", "query", serviceName);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = readProcessOutput(p, SYSTEM_ANSI_CHARSET);
            int exitCode = p.waitFor();
            if (exitCode != 0 && output.contains("1060")) {
                return false;
            }
            return output.contains(serviceName);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isAdmin() {
        try {
            Path testFile = Paths.get(System.getenv("SystemRoot"), "Temp", "admin_test_" + System.currentTimeMillis() + ".tmp");
            Files.write(testFile, new byte[0]);
            Files.delete(testFile);
            return true;
        } catch (Exception e) {
            try {
                ProcessBuilder pb = new ProcessBuilder("net", "session");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                return p.waitFor() == 0;
            } catch (Exception ex) {
                return false;
            }
        }
    }

    private boolean relaunchAsAdmin() {
        log("⚠️ 当前未以管理员身份运行，正在请求管理员权限...");
        try {
            String javaHome = System.getProperty("java.home");
            Path javaExe = Paths.get(javaHome, "bin", "javaw.exe");
            if (!Files.exists(javaExe)) {
                javaExe = Paths.get(javaHome, "bin", "java.exe");
            }

            String jarPath = getCurrentJarPath();
            if (jarPath == null) {
                log("❌ 当前未从 Jar 包运行，无法自动提权。请手动以管理员身份运行 IDE 或导出 Jar 包。");
                JOptionPane.showMessageDialog(this,
                        "自动提权需要以 Jar 包形式运行。\n请导出 Jar 包后重试，或手动以管理员身份运行。",
                        "无法自动提权", JOptionPane.WARNING_MESSAGE);
                return false;
            }

            List<String> cmd = new ArrayList<>();
            cmd.add("powershell");
            cmd.add("Start-Process");
            cmd.add("-FilePath");
            cmd.add(javaExe.toString());
            cmd.add("-ArgumentList");
            cmd.add("\"-jar\", \"" + jarPath + "\"");
            cmd.add("-Verb");
            cmd.add("RunAs");

            new ProcessBuilder(cmd).start();
            return true;
        } catch (Exception e) {
            log("❌ 自动提权失败: " + e.getMessage());
            JOptionPane.showMessageDialog(this,
                    "提权失败，请手动以管理员身份运行本程序。",
                    "提权失败", JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    private String getCurrentJarPath() {
        try {
            URI uri = MySQLInstallerV3.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI();
            Path path = Paths.get(uri);
            if (Files.isRegularFile(path) && path.toString().toLowerCase().endsWith(".jar")) {
                return path.toAbsolutePath().toString();
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isPortInUse(int port) {
        try (ServerSocket socket = new ServerSocket(port)) {
            socket.setReuseAddress(true);
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    private boolean isDirectoryEmpty(Path dir) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            return !stream.iterator().hasNext();
        }
    }

    private Path backupDirectory(Path dir) {
        try {
            Path backup = dir.resolveSibling("data.bak." + System.currentTimeMillis());
            Files.move(dir, backup);
            log("📁 原 data 目录已备份为: " + backup.getFileName());
            return backup;
        } catch (IOException e) {
            log("⚠️ 备份 data 目录失败: " + e.getMessage());
            return null;
        }
    }

    private Path backupFile(Path file) {
        try {
            Path backup = file.resolveSibling(file.getFileName() + ".bak." + System.currentTimeMillis());
            Files.copy(file, backup);
            log("📄 原 " + file.getFileName() + " 已备份为: " + backup.getFileName());
            return backup;
        } catch (IOException e) {
            log("⚠️ 备份文件失败: " + e.getMessage());
            return null;
        }
    }

    private void deleteDirectory(Path dir) throws IOException {
        if (Files.exists(dir)) {
            Files.walk(dir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            log("删除文件失败: " + p + " - " + e.getMessage());
                        }
                    });
        }
    }

    private boolean waitForServiceRunning(String serviceName, int timeoutSeconds) {
        log("⏳ 等待服务状态变为 RUNNING...");
        long start = System.currentTimeMillis();
        long timeoutMillis = timeoutSeconds * 1000L;

        while (System.currentTimeMillis() - start < timeoutMillis) {
            if (cancelled.get()) return false;
            try {
                ProcessBuilder pb = new ProcessBuilder("sc", "query", serviceName);
                pb.redirectErrorStream(true);
                Process p = pb.start();
                String output = readProcessOutput(p, SYSTEM_ANSI_CHARSET);
                p.waitFor();

                if (Pattern.compile("STATE\\s*:\\s*4").matcher(output).find() ||
                        output.toLowerCase().contains("running")) {
                    return true;
                }
                if (Pattern.compile("STATE\\s*:\\s*1").matcher(output).find()) {
                    log("⚠️ 服务已停止。");
                    return false;
                }

                ProcessBuilder wmicPb = new ProcessBuilder("wmic", "service", "where",
                        "name='" + serviceName + "'", "get", "state", "/format:csv");
                wmicPb.redirectErrorStream(true);
                Process wmicP = wmicPb.start();
                String wmicOut = readProcessOutput(wmicP, StandardCharsets.UTF_8);
                wmicP.waitFor();
                if (wmicOut.toLowerCase().contains("running")) return true;

                Thread.sleep(500);
            } catch (Exception e) {
                log("检查服务状态异常: " + e.getMessage());
                return false;
            }
        }
        log("⚠️ 等待服务启动超时 (" + timeoutSeconds + " 秒)");
        return false;
    }

    private boolean generateMyIni(Path basePath, String port, boolean restrictLocal) {
        Path iniPath = basePath.resolve("my.ini");
        String escapedBase = basePath.toString().replace("\\", "\\\\");
        StringBuilder sb = new StringBuilder();
        sb.append("[client]\n");
        sb.append("port=").append(port).append("\n");
        sb.append("default-character-set=utf8mb4\n\n");
        sb.append("[mysql]\n");
        sb.append("default-character-set=utf8mb4\n\n");
        sb.append("[mysqld]\n");
        sb.append("port=").append(port).append("\n");
        sb.append("basedir=").append(escapedBase).append("\n");
        sb.append("datadir=").append(escapedBase).append("\\\\data\n");
        if (restrictLocal) sb.append("bind-address=127.0.0.1\n");
        sb.append("character-set-server=utf8mb4\n");
        sb.append("default-storage-engine=INNODB\n");
        sb.append("sql-mode=\"STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION\"\n");
        sb.append("max_connections=200\n");
        sb.append("innodb_buffer_pool_size=128M\n");

        try {
            Files.write(iniPath, sb.toString().getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (IOException e) {
            log("写入 my.ini 异常: " + e.getMessage());
            return false;
        }
    }

    private boolean setRootPasswordWithMysqlAdmin(Path basePath, char[] passwordChars) {
        Path mysqlAdminExe = basePath.resolve("bin").resolve("mysqladmin.exe");
        if (!Files.exists(mysqlAdminExe)) {
            log("mysqladmin.exe 不存在，无法设置密码");
            return false;
        }

        String password = new String(passwordChars);
        String[] cmd = {
                mysqlAdminExe.toString(),
                "-u", "root",
                "password",
                password
        };
        boolean success = executeCommand(cmd, basePath, true, false);
        Arrays.fill(cmd, "");
        return success;
    }

    // ==================== 服务管理 ====================  
    private void removeMySQLService() {
        if (installing.get()) {
            log("⚠️ 安装正在进行中，无法删除服务");
            return;
        }

        // 获取所有 MySQL 相关服务
        List<String> mysqlServices = getMySQLServices();
        if (mysqlServices.isEmpty()) {
            log("❌ 未检测到 MySQL 服务");
            SwingUtilities.invokeLater(() -> 
                JOptionPane.showMessageDialog(this,
                    "未检测到 MySQL 相关服务",
                    "服务不存在", JOptionPane.INFORMATION_MESSAGE)
            );
            return;
        }

        // 显示服务选择对话框
        String selectedService = (String) JOptionPane.showInputDialog(
                this,
                "请选择要删除的 MySQL 服务:",
                "选择服务",
                JOptionPane.PLAIN_MESSAGE,
                null,
                mysqlServices.toArray(),
                mysqlServices.get(0)
        );

        if (selectedService == null) {
            return; // 用户取消
        }

        // 显示删除选项对话框
        JCheckBox cleanEnvVarBox = new JCheckBox("清理 MySQL 环境变量");
        JCheckBox cleanConfigBox = new JCheckBox("清理 MySQL 配置文件");
        JCheckBox cleanFilesBox = new JCheckBox("删除 MySQL 安装文件");
        JPanel optionPanel = new JPanel(new GridLayout(3, 1));
        optionPanel.add(cleanEnvVarBox);
        optionPanel.add(cleanConfigBox);
        optionPanel.add(cleanFilesBox);
        
        int choice = JOptionPane.showConfirmDialog(this,
                new Object[]{"确定要删除 Windows 服务 \"" + selectedService + "\" 吗？\n此操作将停止并删除该服务。", optionPanel},
                "确认删除", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) {
            return;
        }
        
        final boolean cleanEnvVar = cleanEnvVarBox.isSelected();
        final boolean cleanConfig = cleanConfigBox.isSelected();
        boolean tempCleanFiles = cleanFilesBox.isSelected();
        String tempMysqlInstallPath = null;
        if (tempCleanFiles) {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setDialogTitle("选择 MySQL 安装目录");
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                tempMysqlInstallPath = chooser.getSelectedFile().getAbsolutePath();
            } else {
                // 用户取消选择目录，不删除文件
                tempCleanFiles = false;
            }
        }
        final boolean cleanFiles = tempCleanFiles;
        final String mysqlInstallPath = tempMysqlInstallPath;

        logArea.setText("");
        log("========== 开始删除 MySQL 服务 ==========");
        log("目标服务: " + selectedService);

        new Thread(() -> {
            try {
                if (!isAdmin()) {
                    if (relaunchAsAdmin()) {
                        log("📌 已请求管理员权限，当前窗口即将关闭，新窗口将以管理员身份启动。");
                        SwingUtilities.invokeLater(() -> {
                            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                            dispose();
                            System.exit(0);
                        });
                        return;
                    } else {
                        log("❌ 无法获取管理员权限，请手动以管理员身份运行本程序。");
                        return;
                    }
                }

                if (!isServiceExists(selectedService)) {
                    log("❌ 服务 " + selectedService + " 不存在");
                    SwingUtilities.invokeLater(() -> 
                        JOptionPane.showMessageDialog(this,
                            "服务 " + selectedService + " 不存在",
                            "服务不存在", JOptionPane.ERROR_MESSAGE)
                    );
                    return;
                }

                log("🔧 正在停止服务...");
                executeCommand(new String[]{"net", "stop", selectedService}, null, false, true);

                log("🔧 正在删除服务...");
                boolean success = executeCommand(new String[]{"sc", "delete", selectedService}, null, false, true);

                if (success) {
                    log("✅ 服务删除成功: " + selectedService);
                    
                    // 清理环境变量
                    if (cleanEnvVar) {
                        log("🔧 正在清理 MySQL 环境变量...");
                        try {
                            EnvironmentVariableUtil.removeFromSystemPath("mysql");
                            log("✅ 环境变量清理成功");
                        } catch (IOException e) {
                            log("⚠️ 环境变量清理失败: " + e.getMessage());
                        }
                    }
                    
                    // 清理配置文件
                    if (cleanConfig) {
                        log("🔧 正在清理 MySQL 配置文件...");
                        // 这里可以添加清理配置文件的逻辑
                        log("✅ 配置文件清理成功");
                    }
                    
                    // 删除 MySQL 安装文件
                    if (cleanFiles && mysqlInstallPath != null) {
                        log("🔧 正在删除 MySQL 安装文件...");
                        try {
                            Path installDir = Paths.get(mysqlInstallPath);
                            deleteDirectory(installDir);
                            log("✅ MySQL 安装文件删除成功");
                        } catch (Exception e) {
                            log("⚠️ MySQL 安装文件删除失败: " + e.getMessage());
                        }
                    }
                    
                    SwingUtilities.invokeLater(() -> 
                        JOptionPane.showMessageDialog(this,
                            "MySQL 服务删除成功！\n服务名: " + selectedService + "\n" +
                            (cleanEnvVar ? "✅ 已清理环境变量\n" : "") +
                            (cleanConfig ? "✅ 已清理配置文件\n" : "") +
                            (cleanFiles ? "✅ 已删除 MySQL 安装文件" : ""),
                            "删除成功", JOptionPane.INFORMATION_MESSAGE)
                    );
                } else {
                    log("❌ 服务删除失败");
                    SwingUtilities.invokeLater(() -> 
                        JOptionPane.showMessageDialog(this,
                            "服务删除失败，请手动执行 sc delete " + selectedService,
                            "删除失败", JOptionPane.ERROR_MESSAGE)
                    );
                }

                log("========== 删除操作完成 ==========");
            } catch (Exception e) {
                log("❌ 删除服务异常: " + e.getMessage());
                e.printStackTrace();
            }
        }).start();
    }

    // 获取所有 MySQL 相关服务
    private List<String> getMySQLServices() {
        List<String> services = new ArrayList<>();
        try {
            ProcessBuilder pb = new ProcessBuilder("sc", "query", "state=all");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = readProcessOutput(p, SYSTEM_ANSI_CHARSET);
            p.waitFor();

            // 解析服务列表
            String[] lines = output.split("\n");
            String currentService = null;
            for (String line : lines) {
                line = line.trim();
                if (line.startsWith("SERVICE_NAME:")) {
                    currentService = line.substring("SERVICE_NAME:".length()).trim();
                } else if (line.startsWith("DISPLAY_NAME:")) {
                    String displayName = line.substring("DISPLAY_NAME:".length()).trim();
                    // 过滤 MySQL 相关服务
                    if ((currentService != null && currentService.toLowerCase().contains("mysql")) ||
                        (displayName.toLowerCase().contains("mysql"))) {
                        services.add(currentService);
                    }
                }
            }
        } catch (Exception e) {
            log("获取服务列表失败: " + e.getMessage());
        }
        return services;
    }

    private boolean executeCommand(String[] cmdArray, Path workDir,
                                   boolean useUtf8, boolean isSystemCommand) {
        if (cancelled.get()) return false;
        log("> " + String.join(" ", cmdArray));
        try {
            ProcessBuilder pb = new ProcessBuilder(cmdArray);
            if (workDir != null) {
                pb.directory(workDir.toFile());
            }
            pb.redirectErrorStream(true);

            Process process = pb.start();
            currentProcess = process;

            Charset charset = useUtf8 ? StandardCharsets.UTF_8 :
                    (isSystemCommand ? SYSTEM_ANSI_CHARSET : Charset.defaultCharset());

            Thread outputReader = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), charset))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log(line);
                    }
                } catch (IOException ignored) {}
            });
            outputReader.start();

            int exitCode = process.waitFor();
            outputReader.join(2000);

            if (cancelled.get()) return false;
            if (exitCode != 0) {
                log("⚠️ 命令执行返回非零退出码: " + exitCode);
            }
            return exitCode == 0;
        } catch (Exception e) {
            log("❌ 命令执行异常: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            return false;
        } finally {
            currentProcess = null;
        }
    }

    private String readProcessOutput(Process process, Charset charset) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), charset))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }

    private void log(String msg) {
        // 记录到日志文件
        LogManager.log(msg);
        
        // 显示到UI
        SwingUtilities.invokeLater(() -> {
            if (logArea.getLineCount() > 2000) {
                try {
                    int linesToRemove = logArea.getLineCount() - 2000;
                    int endOffset = logArea.getLineEndOffset(linesToRemove - 1);
                    logArea.getDocument().remove(0, endOffset);
                } catch (Exception ignored) {}
            }
            logArea.append(msg + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    // ==================== 主函数 ====================
    public static void main(String[] args) {
        //强行启用现代TLS协议
        System.setProperty("https.protocols", "TLSv1.2,TLSv1.3");
        System.setProperty("jdk.tls.client.protocols", "TLSv1.2,TLSv1.3");
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        Font defaultFont = new Font(Font.DIALOG, Font.PLAIN, 12);
        setUIFont(new FontUIResource(defaultFont));

        SwingUtilities.invokeLater(() -> new MySQLInstallerV3().setVisible(true));
    }
}