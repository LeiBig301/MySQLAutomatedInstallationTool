package com.mysql.installer.i18n;

import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;
import java.util.ResourceBundle;

public class I18nManager {
    private static ResourceBundle bundle;
    private static Locale currentLocale;

    static {
        // 初始化时使用系统默认语言
        setLocale(Locale.getDefault());
    }

    public static void setLocale(Locale locale) {
        currentLocale = locale;
        try {
            // 尝试加载指定语言的资源文件
            bundle = ResourceBundle.getBundle("com.mysql.installer.i18n.Messages", locale);
        } catch (Exception e) {
            // 如果加载失败，使用默认语言
            try {
                bundle = ResourceBundle.getBundle("com.mysql.installer.i18n.Messages");
            } catch (Exception ex) {
                // 如果默认语言也加载失败，使用英文消息
                bundle = new ResourceBundle() {
                    @Override
                    protected Object handleGetObject(String key) {
                        return "[" + key + "]";
                    }

                    @Override
                    public Enumeration<String> getKeys() {
                        return Collections.emptyEnumeration();
                    }
                };
            }
        }
    }

    public static String get(String key, Object... args) {
        try {
            String message = bundle.getString(key);
            if (args.length > 0) {
                return String.format(message, args);
            }
            return message;
        } catch (Exception e) {
            return "[" + key + "]";
        }
    }

    public static Locale getCurrentLocale() {
        return currentLocale;
    }

    public static boolean isChinese() {
        return currentLocale.getLanguage().equals("zh");
    }

    public static void switchLanguage() {
        if (isChinese()) {
            setLocale(Locale.ENGLISH);
        } else {
            setLocale(Locale.SIMPLIFIED_CHINESE);
        }
    }
}
