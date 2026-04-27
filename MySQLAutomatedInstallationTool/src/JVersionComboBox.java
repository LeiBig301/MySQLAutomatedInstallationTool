import javax.swing.*;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.JTextComponent;
import java.awt.event.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 带搜索和自动补全功能的 MySQL 版本选择下拉框（性能优化版）
 * 使用防抖机制，避免频繁重建模型导致卡顿。
 */
public class JVersionComboBox extends JComboBox<String> {

    private final List<String> allVersions;
    private boolean isAutoCompleting = false;
    private boolean autoCompleteEnabled = false;
    private Timer debounceTimer;
    private String lastFilterText = "";

    public JVersionComboBox(List<String> versions) {
        super(versions.toArray(new String[0]));
        this.allVersions = new ArrayList<>(versions);
        setEditable(true);
        initAutoComplete();
        initDebounceTimer();
    }

    private void initDebounceTimer() {
        debounceTimer = new Timer(150, e -> {
            JTextComponent editor = (JTextComponent) getEditor().getEditorComponent();
            performAutoComplete(editor.getText());
        });
        debounceTimer.setRepeats(false);
    }

    private void initAutoComplete() {
        JTextComponent editor = (JTextComponent) getEditor().getEditorComponent();

        editor.addKeyListener(new KeyAdapter() {
            @Override
            public void keyReleased(KeyEvent e) {
                int code = e.getKeyCode();
                if (code == KeyEvent.VK_UP || code == KeyEvent.VK_DOWN ||
                        code == KeyEvent.VK_LEFT || code == KeyEvent.VK_RIGHT ||
                        code == KeyEvent.VK_ENTER || code == KeyEvent.VK_ESCAPE) {
                    return;
                }
                if (autoCompleteEnabled) {
                    debounceTimer.restart();
                }
            }
        });

        editor.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) {
                if (autoCompleteEnabled) debounceTimer.restart();
            }
            @Override public void removeUpdate(DocumentEvent e) {
                if (autoCompleteEnabled) debounceTimer.restart();
            }
            @Override public void changedUpdate(DocumentEvent e) {}
        });

        addItemListener(e -> {
            if (e.getStateChange() == ItemEvent.SELECTED && !isAutoCompleting) {
                String selected = e.getItem().toString();
                editor.setText(selected);
                editor.selectAll();
            }
        });

        SwingUtilities.invokeLater(() -> autoCompleteEnabled = true);
    }

    private void performAutoComplete(String input) {
        if (isAutoCompleting) return;
        if (!isDisplayable() || !isVisible()) return;

        // 避免重复处理相同文本
        if (Objects.equals(input, lastFilterText)) {
            return;
        }
        lastFilterText = input;

        if (input == null || input.trim().isEmpty()) {
            resetToFullListWithEmptySelection();
            return;
        }

        isAutoCompleting = true;
        try {
            String lowerInput = input.toLowerCase();
            List<String> matched = allVersions.stream()
                    .filter(v -> v.toLowerCase().contains(lowerInput))
                    .collect(Collectors.toList());

            if (matched.isEmpty()) {
                // 无匹配项，显示只包含当前输入的临时模型
                setModel(new DefaultComboBoxModel<>(new String[]{input}));
                // 选中当前输入
                setSelectedItem(input);
            } else {
                DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>(matched.toArray(new String[0]));
                setModel(model);
                // 选中当前输入（若在列表中）
                setSelectedItem(input);
            }

            if (isShowing()) {
                showPopup();
            }
            JTextComponent editor = (JTextComponent) getEditor().getEditorComponent();
            // 保持编辑框内容为用户输入的值（防止被选中项覆盖）
            editor.setText(input);
            editor.setCaretPosition(input.length());
        } finally {
            isAutoCompleting = false;
        }
    }

    /**
     * 清空输入框时，恢复全量列表，但不自动选中任何项，编辑框保持空白
     */
    private void resetToFullListWithEmptySelection() {
        if (lastFilterText != null && lastFilterText.isEmpty()) {
            return; // 已经是空状态
        }
        lastFilterText = "";
        isAutoCompleting = true;
        try {
            setModel(new DefaultComboBoxModel<>(allVersions.toArray(new String[0])));
            // 关键：设置选中项为 null，避免默认选中第一个
            setSelectedItem(null);
        } finally {
            isAutoCompleting = false;
        }
        JTextComponent editor = (JTextComponent) getEditor().getEditorComponent();
        editor.setText("");
    }

    public String getSelectedVersion() {
        Object selected = getSelectedItem();
        if (selected != null) {
            return selected.toString().trim();
        }
        JTextComponent editor = (JTextComponent) getEditor().getEditorComponent();
        return editor.getText().trim();
    }

    public void setSelectedVersion(String version) {
        setSelectedItem(version);
    }
}