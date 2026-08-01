import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.event.*;
import javax.swing.undo.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.print.PrinterException;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.prefs.Preferences;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class Waqompad extends JFrame {

    // ---------- Global (app-wide) state ----------
    private static final Preferences PREFS = Preferences.userNodeForPackage(Waqompad.class);
    private static final int MASK = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
    private static final int MAX_RECENT = 5;
    private static final AtomicBoolean RECOVERY_CHECKED = new AtomicBoolean(false);
    private static final File AUTOSAVE_DIR = new File(System.getProperty("user.home"), ".waqompad_autosave");

    private final List<String> recentFiles = new ArrayList<>();
    private int fontSize = 18;
    private boolean darkMode = false;

    private JMenuBar menuBar;
    private JMenu recentMenu;
    private JCheckBoxMenuItem darkModeItem;
    private JCheckBoxMenuItem wordWrapItem;
    private JCheckBoxMenuItem lineNumbersItem;
    private JCheckBoxMenuItem readOnlyItem;
    private JCheckBoxMenuItem alwaysOnTopItem;
    private JCheckBoxMenuItem statusBarItem;
    private JTabbedPane tabbedPane;
    private JPanel statusPanel;
    private final JLabel statusBar = new JLabel("Ln 1, Col 1");
    private final JLabel wordCountLabel = new JLabel("Words: 0  Chars: 0");
    private final JLabel zoomLabel = new JLabel("100%");
    private javax.swing.Timer autoSaveTimer;
    private final javax.swing.Timer wordCountDebounce = new javax.swing.Timer(200, e -> updateWordCount());

    // Theme colors
    private static final Color LIGHT_BG = Color.WHITE;
    private static final Color LIGHT_FG = Color.BLACK;
    private static final Color LIGHT_SELECTION = new Color(180, 213, 255);
    private static final Color LIGHT_STATUSBAR_BG = new Color(240, 240, 240);
    private static final Color LIGHT_GUTTER_BG = new Color(245, 245, 245);
    private static final Color LIGHT_GUTTER_FG = new Color(120, 120, 120);

    private static final Color DARK_BG = new Color(30, 30, 30);
    private static final Color DARK_FG = new Color(220, 220, 220);
    private static final Color DARK_SELECTION = new Color(70, 90, 140);
    private static final Color DARK_STATUSBAR_BG = new Color(45, 45, 45);
    private static final Color DARK_MENU_BG = new Color(40, 40, 40);
    private static final Color DARK_MENU_FG = new Color(220, 220, 220);
    private static final Color DARK_GUTTER_BG = new Color(37, 37, 37);
    private static final Color DARK_GUTTER_FG = new Color(130, 130, 130);

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                System.setProperty("apple.awt.application.name", "WaqomPad");
                // Cross-platform (Metal) L&F is used instead of the native one: native look-and-feels
                // (Aqua on macOS in particular) ignore custom menu/tab colors and center-align tabs,
                // which broke dark-mode readability and tab placement.
                UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
                flattenLookAndFeel();
            } catch (Exception ignored) {}
            new Waqompad().setVisible(true);
        });
    }

    /**
     * Strips down Metal L&F's default chrome (borders, insets, heavier fonts) so the app reads
     * as a plain, flat text editor rather than a generic Java app — closer to Notepad's minimal style.
     */
    private static void flattenLookAndFeel() {
        Font uiFont = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
        String[] fontKeys = {
            "MenuBar.font", "Menu.font", "MenuItem.font", "CheckBoxMenuItem.font", "RadioButtonMenuItem.font",
            "Label.font", "Button.font", "TextField.font", "TextArea.font", "OptionPane.font",
            "OptionPane.messageFont", "OptionPane.buttonFont", "TabbedPane.font", "CheckBox.font",
            "ComboBox.font", "Spinner.font", "ToolTip.font"
        };
        for (String key : fontKeys) UIManager.put(key, uiFont);

        Border flatMenuItemBorder = BorderFactory.createEmptyBorder(4, 12, 4, 12);
        UIManager.put("Menu.border", flatMenuItemBorder);
        UIManager.put("MenuItem.border", flatMenuItemBorder);
        UIManager.put("CheckBoxMenuItem.border", flatMenuItemBorder);
        UIManager.put("PopupMenu.border", BorderFactory.createLineBorder(new Color(200, 200, 200)));
        UIManager.put("MenuBar.border", BorderFactory.createEmptyBorder(2, 4, 2, 4));

        UIManager.put("TabbedPane.tabInsets", new Insets(6, 14, 6, 14));
        UIManager.put("TabbedPane.contentBorderInsets", new Insets(0, 0, 0, 0));
        UIManager.put("TabbedPane.selectedTabPadInsets", new Insets(0, 0, 0, 0));
        UIManager.put("TabbedPane.tabsOverlapBorder", false);

        UIManager.put("ScrollPane.border", BorderFactory.createEmptyBorder());
        UIManager.put("SplitPane.border", BorderFactory.createEmptyBorder());
        UIManager.put("Button.margin", new Insets(4, 10, 4, 10));
    }

    public Waqompad() {
        super("Untitled - WaqomPad");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        loadPreferences();

        setSize(1100, 720);
        setIconImage(loadIcon());

        tabbedPane = new JTabbedPane();
        tabbedPane.setBorder(BorderFactory.createEmptyBorder());
        tabbedPane.addChangeListener(e -> onTabChanged());
        add(tabbedPane, BorderLayout.CENTER);

        statusBar.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        wordCountLabel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        zoomLabel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        JPanel rightStatus = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        rightStatus.setOpaque(false);
        rightStatus.add(wordCountLabel);
        rightStatus.add(zoomLabel);
        statusPanel = new JPanel(new BorderLayout());
        statusPanel.add(statusBar, BorderLayout.WEST);
        statusPanel.add(rightStatus, BorderLayout.EAST);
        statusPanel.setOpaque(true);
        add(statusPanel, BorderLayout.SOUTH);

        menuBar = createMenuBar();
        setJMenuBar(menuBar);

        applySavedWindowBounds();
        addWindowListener(new WindowAdapter() { public void windowClosing(WindowEvent e) { exitApp(); } });

        AUTOSAVE_DIR.mkdirs();
        wordCountDebounce.setRepeats(false);
        addTab("Untitled");
        setupAutoSave();
        if (RECOVERY_CHECKED.compareAndSet(false, true)) checkForRecovery();

        updateTitle();
        updateStatusBar();
        updateWordCount();
        updateZoomLabel();
        applyTheme();
        rebuildRecentMenu();
    }

    // ---------- Tab helpers ----------

    private EditorTab currentTab() {
        Component c = tabbedPane.getSelectedComponent();
        return c instanceof EditorTab ? (EditorTab) c : null;
    }

    private EditorTab addTab(String title) {
        EditorTab tab = new EditorTab();
        tab.textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, fontSize));
        tab.textArea.setMargin(new Insets(4, 6, 4, 6));
        boolean wrap = wordWrapItem != null && wordWrapItem.isSelected();
        tab.textArea.setLineWrap(wrap);
        tab.textArea.setWrapStyleWord(wrap);

        tabbedPane.addTab(title, tab);
        setupTabComponent(tab, title);
        wireTabListeners(tab);
        tabbedPane.setSelectedComponent(tab);
        applyThemeToTab(tab);
        if (lineNumbersItem != null) refreshLineNumberVisibility();
        return tab;
    }

    /** Shows/hides the line-number gutter on every open tab based on the Line Numbers and Word Wrap toggles. */
    private void refreshLineNumberVisibility() {
        boolean show = lineNumbersItem.isSelected() && !wordWrapItem.isSelected();
        for (EditorTab t : allTabs()) {
            if (show) t.lineNumberArea.updateWidth();
            t.scrollPane.setRowHeaderView(show ? t.lineNumberArea : null);
        }
    }

    private void setupTabComponent(EditorTab tab, String title) {
        JPanel tabComp = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        tabComp.setOpaque(false);
        tabComp.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 0));
        JLabel label = new JLabel(title);
        tab.titleLabel = label;
        JButton closeBtn = new JButton("\u2715");
        closeBtn.setFont(closeBtn.getFont().deriveFont(10f));
        closeBtn.setMargin(new Insets(0, 2, 0, 2));
        closeBtn.setContentAreaFilled(false);
        closeBtn.setBorderPainted(false);
        closeBtn.setFocusPainted(false);
        closeBtn.setToolTipText("Close tab");
        closeBtn.addActionListener(e -> closeTab(tab));
        tabComp.add(label);
        tabComp.add(closeBtn);
        tab.tabComponent = tabComp;
        tab.closeButton = closeBtn;
        int idx = tabbedPane.indexOfComponent(tab);
        if (idx >= 0) tabbedPane.setTabComponentAt(idx, tabComp);
    }

    private void wireTabListeners(EditorTab tab) {
        tab.textArea.getDocument().addUndoableEditListener(e -> tab.undoManager.addEdit(e.getEdit()));
        tab.textArea.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { markModified(tab); }
            public void removeUpdate(DocumentEvent e) { markModified(tab); }
            public void changedUpdate(DocumentEvent e) { markModified(tab); }
        });
        tab.textArea.addCaretListener(e -> { if (currentTab() == tab) updateStatusBar(); });
        // Plain mouse-wheel/trackpad scrolling always passes through untouched (JScrollPane
        // handles it natively). Holding the platform shortcut key (Cmd on macOS, Ctrl on
        // Windows/Linux) while scrolling or swiping on a trackpad zooms instead.
        tab.textArea.addMouseWheelListener(e -> {
            if ((e.getModifiersEx() & MASK) != 0) {
                int rotation = e.getWheelRotation();
                if (rotation == 0 && e.getPreciseWheelRotation() != 0) {
                    rotation = e.getPreciseWheelRotation() > 0 ? 1 : -1;
                }
                setEditorFontSize(fontSize - rotation * 2);
                e.consume();
            }
        });
    }

    private void onTabChanged() {
        EditorTab tab = currentTab();
        updateTitle();
        updateStatusBar();
        updateWordCount();
        if (tab != null && readOnlyItem != null) readOnlyItem.setSelected(!tab.textArea.isEditable());
    }

    private String tabLabel(EditorTab tab) {
        return (tab.isModified ? "*" : "") + (tab.currentFile == null ? "Untitled" : tab.currentFile.getName());
    }

    private void updateTabTitle(EditorTab tab) {
        String label = tabLabel(tab);
        if (tab.titleLabel != null) tab.titleLabel.setText(label);
        int idx = tabbedPane.indexOfComponent(tab);
        if (idx >= 0) tabbedPane.setTitleAt(idx, label);
        if (currentTab() == tab) updateTitle();
    }

    // ---------- Menu ----------

    /** Plain menu item, no accelerator. */
    private JMenuItem mi(String title, ActionListener action) {
        JMenuItem item = new JMenuItem(title);
        item.addActionListener(action);
        return item;
    }

    /** Menu item with an explicit KeyStroke accelerator (for shortcuts outside the standard platform mask). */
    private JMenuItem mi(String title, KeyStroke accelerator, ActionListener action) {
        JMenuItem item = mi(title, action);
        item.setAccelerator(accelerator);
        return item;
    }

    private JMenuBar createMenuBar() {
        JMenuBar menuBar = new JMenuBar();
        JMenu file = new JMenu("File");
        file.add(createItem("New Tab", KeyEvent.VK_N, 0, e -> newTab()));
        file.add(createItem("New Window", KeyEvent.VK_N, InputEvent.SHIFT_DOWN_MASK, e -> new Waqompad().setVisible(true)));
        file.add(createItem("Open", KeyEvent.VK_O, 0, e -> openFile()));
        recentMenu = new JMenu("Open Recent");
        file.add(recentMenu);
        file.add(createItem("Save", KeyEvent.VK_S, 0, e -> { EditorTab t = currentTab(); if (t != null) saveTab(t); }));
        file.add(createItem("Save As", KeyEvent.VK_S, InputEvent.SHIFT_DOWN_MASK, e -> { EditorTab t = currentTab(); if (t != null) saveTabAs(t); }));
        file.add(createItem("Close Tab", KeyEvent.VK_W, 0, e -> { EditorTab t = currentTab(); if (t != null) closeTab(t); }));
        file.addSeparator();
        file.add(mi("Page Setup", e -> JOptionPane.showMessageDialog(this, "Page setup is handled by the native print dialog.")));
        file.add(createItem("Print", KeyEvent.VK_P, 0, e -> printFile()));
        file.addSeparator();
        file.add(mi("Exit", e -> exitApp()));

        JMenu edit = new JMenu("Edit");
        edit.add(createItem("Undo", KeyEvent.VK_Z, 0, e -> { EditorTab t = currentTab(); if (t != null && t.undoManager.canUndo()) t.undoManager.undo(); }));
        edit.add(createItem("Redo", KeyEvent.VK_Y, 0, e -> { EditorTab t = currentTab(); if (t != null && t.undoManager.canRedo()) t.undoManager.redo(); }));
        edit.addSeparator();
        edit.add(createItem("Cut", KeyEvent.VK_X, 0, e -> { EditorTab t = currentTab(); if (t != null) t.textArea.cut(); }));
        edit.add(createItem("Copy", KeyEvent.VK_C, 0, e -> { EditorTab t = currentTab(); if (t != null) t.textArea.copy(); }));
        edit.add(createItem("Paste", KeyEvent.VK_V, 0, e -> { EditorTab t = currentTab(); if (t != null) t.textArea.paste(); }));
        edit.add(mi("Delete", e -> { EditorTab t = currentTab(); if (t != null) t.textArea.replaceSelection(""); }));
        edit.addSeparator();
        edit.add(mi("Search with Bing", e -> searchWithBing()));
        edit.add(createItem("Find", KeyEvent.VK_F, 0, e -> showFindDialog()));
        edit.add(mi("Find Next", e -> findText(false)));
        edit.add(mi("Find Previous", e -> findText(true)));
        edit.add(createItem("Replace", KeyEvent.VK_H, 0, e -> showReplaceDialog()));
        edit.add(createItem("Go To", KeyEvent.VK_G, 0, e -> goToLine()));
        edit.addSeparator();
        edit.add(createItem("Select All", KeyEvent.VK_A, 0, e -> { EditorTab t = currentTab(); if (t != null) t.textArea.selectAll(); }));
        edit.add(mi("Time/Date", KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), e -> insertTimeDate()));
        edit.addSeparator();

        JMenu caseMenu = new JMenu("Convert Case");
        caseMenu.add(mi("UPPERCASE", e -> convertSelectionCase(0)));
        caseMenu.add(mi("lowercase", e -> convertSelectionCase(1)));
        caseMenu.add(mi("Title Case", e -> convertSelectionCase(2)));
        edit.add(caseMenu);

        edit.add(mi("Duplicate Line", KeyStroke.getKeyStroke(KeyEvent.VK_D, MASK), e -> duplicateLine()));
        edit.add(mi("Move Line Up", KeyStroke.getKeyStroke(KeyEvent.VK_UP, InputEvent.ALT_DOWN_MASK), e -> moveLine(-1)));
        edit.add(mi("Move Line Down", KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, InputEvent.ALT_DOWN_MASK), e -> moveLine(1)));

        JMenu format = new JMenu("Format");
        wordWrapItem = new JCheckBoxMenuItem("Word Wrap", PREFS.getBoolean("wordWrap", false));
        wordWrapItem.addActionListener(e -> {
            boolean wrap = wordWrapItem.isSelected();
            for (EditorTab t : allTabs()) {
                t.textArea.setLineWrap(wrap);
                t.textArea.setWrapStyleWord(wrap);
            }
            refreshLineNumberVisibility();
        });
        format.add(wordWrapItem);
        readOnlyItem = new JCheckBoxMenuItem("Read-Only Mode");
        readOnlyItem.addActionListener(e -> { EditorTab t = currentTab(); if (t != null) t.textArea.setEditable(!readOnlyItem.isSelected()); });
        format.add(readOnlyItem);
        format.add(mi("Font", e -> chooseFont()));

        JMenu view = new JMenu("View");
        view.add(mi("Zoom In", KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, MASK), e -> setEditorFontSize(fontSize + 2)));
        view.add(mi("Zoom Out", KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, MASK), e -> setEditorFontSize(fontSize - 2)));
        view.add(mi("Restore Default Zoom", KeyStroke.getKeyStroke(KeyEvent.VK_0, MASK), e -> setEditorFontSize(18)));
        view.addSeparator();
        statusBarItem = new JCheckBoxMenuItem("Status Bar", true);
        statusBarItem.addActionListener(e -> statusPanel.setVisible(statusBarItem.isSelected()));
        view.add(statusBarItem);
        lineNumbersItem = new JCheckBoxMenuItem("Line Numbers", PREFS.getBoolean("lineNumbers", false));
        lineNumbersItem.addActionListener(e -> refreshLineNumberVisibility());
        view.add(lineNumbersItem);
        alwaysOnTopItem = new JCheckBoxMenuItem("Always on Top", PREFS.getBoolean("alwaysOnTop", false));
        alwaysOnTopItem.addActionListener(e -> setAlwaysOnTop(alwaysOnTopItem.isSelected()));
        view.add(alwaysOnTopItem);
        setAlwaysOnTop(alwaysOnTopItem.isSelected());
        view.addSeparator();
        darkModeItem = new JCheckBoxMenuItem("Dark Mode", darkMode);
        darkModeItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_D, MASK | InputEvent.SHIFT_DOWN_MASK));
        darkModeItem.addActionListener(e -> { darkMode = darkModeItem.isSelected(); applyTheme(); });
        view.add(darkModeItem);

        JMenu help = new JMenu("Help");
        help.add(mi("View Help", e -> JOptionPane.showMessageDialog(this,
                "WaqomPad Help\n\nUse File, Edit, Format, View and Help menus just like classic Notepad.\n"
                + "Open several files at once as tabs across the top; click a tab's \u2715 to close it.\n"
                + "View > Dark Mode toggles the theme. View > Line Numbers shows a gutter.\n"
                + "Ctrl/Cmd+Scroll zooms in and out.\n"
                + "Unsaved work is auto-saved periodically per tab and offered back to you if WaqomPad closes unexpectedly.")));
        help.add(mi("Send Feedback", e -> JOptionPane.showMessageDialog(this, "Feedback is offline in this version. Share feedback manually.")));
        help.add(mi("About WaqomPad", e -> JOptionPane.showMessageDialog(this,
                "WaqomPad\nProfessional Java Swing Notepad\nNative cross-platform desktop text editor.",
                "About WaqomPad", JOptionPane.INFORMATION_MESSAGE)));

        menuBar.add(file); menuBar.add(edit); menuBar.add(format); menuBar.add(view); menuBar.add(help);
        return menuBar;
    }

    /** Menu item using the platform shortcut mask (Ctrl on Windows/Linux, Cmd on macOS) plus any extra modifier. */
    private JMenuItem createItem(String title, int key, int extraMask, ActionListener action) {
        return mi(title, KeyStroke.getKeyStroke(key, MASK | extraMask), action);
    }

    private List<EditorTab> allTabs() {
        List<EditorTab> list = new ArrayList<>();
        for (int i = 0; i < tabbedPane.getTabCount(); i++) {
            Component c = tabbedPane.getComponentAt(i);
            if (c instanceof EditorTab) list.add((EditorTab) c);
        }
        return list;
    }

    // ---------- Status bar / title ----------

    private void markModified(EditorTab tab) {
        if (!tab.isModified) { tab.isModified = true; updateTabTitle(tab); }
        if (currentTab() != tab) return; // background tab edits never happen from typing; nothing else to refresh
        updateStatusBar();
        if (tab.lineNumberArea.isShowing()) tab.lineNumberArea.updateWidth();
        wordCountDebounce.restart();
    }

    private void updateTitle() {
        EditorTab t = currentTab();
        setTitle((t == null ? "WaqomPad" : tabLabel(t) + " - WaqomPad"));
    }

    private void updateStatusBar() {
        EditorTab t = currentTab();
        if (t == null) { statusBar.setText(""); return; }
        try {
            int pos = t.textArea.getCaretPosition();
            int line = t.textArea.getLineOfOffset(pos);
            int col = pos - t.textArea.getLineStartOffset(line);
            statusBar.setText("Ln " + (line + 1) + ", Col " + (col + 1));
        } catch (Exception ignored) {}
    }

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private void updateWordCount() {
        EditorTab t = currentTab();
        if (t == null) { wordCountLabel.setText(""); return; }
        String text = t.textArea.getText();
        int chars = text.length();
        String trimmed = text.trim();
        int words = trimmed.isEmpty() ? 0 : WHITESPACE.split(trimmed).length;
        wordCountLabel.setText("Words: " + words + "  Chars: " + chars);
    }

    private void updateZoomLabel() {
        int percent = Math.round(fontSize / 18f * 100);
        zoomLabel.setText(percent + "%");
    }

    // ---------- Theme ----------

    private void applyTheme() {
        for (EditorTab t : allTabs()) applyThemeToTab(t);
        Color statusBg = darkMode ? DARK_STATUSBAR_BG : LIGHT_STATUSBAR_BG;
        Color fg = darkMode ? DARK_FG : LIGHT_FG;
        statusBar.setBackground(statusBg);
        statusBar.setForeground(fg);
        wordCountLabel.setBackground(statusBg);
        wordCountLabel.setForeground(fg);
        zoomLabel.setBackground(statusBg);
        zoomLabel.setForeground(fg);
        statusPanel.setBackground(statusBg);
        Color lineColor = darkMode ? new Color(55, 55, 55) : new Color(220, 220, 220);
        statusPanel.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, lineColor));
        getContentPane().setBackground(darkMode ? DARK_BG : LIGHT_BG);
        if (tabbedPane != null) {
            tabbedPane.setBackground(darkMode ? DARK_MENU_BG : UIManager.getColor("TabbedPane.background"));
            tabbedPane.setForeground(darkMode ? DARK_MENU_FG : UIManager.getColor("TabbedPane.foreground"));
        }
        if (menuBar != null) applyMenuTheme(menuBar, darkMode);
        repaint();
    }

    private void applyThemeToTab(EditorTab tab) {
        Color bg = darkMode ? DARK_BG : LIGHT_BG;
        Color fg = darkMode ? DARK_FG : LIGHT_FG;
        Color selection = darkMode ? DARK_SELECTION : LIGHT_SELECTION;

        tab.textArea.setBackground(bg);
        tab.textArea.setForeground(fg);
        tab.textArea.setCaretColor(fg);
        tab.textArea.setSelectionColor(selection);
        tab.textArea.setSelectedTextColor(darkMode ? Color.WHITE : Color.BLACK);

        tab.scrollPane.getViewport().setBackground(bg);
        tab.scrollPane.setBackground(bg);

        tab.lineNumberArea.setBackground(darkMode ? DARK_GUTTER_BG : LIGHT_GUTTER_BG);
        tab.lineNumberArea.setForeground(darkMode ? DARK_GUTTER_FG : LIGHT_GUTTER_FG);
        tab.lineNumberArea.repaint();

        Color tabBg = darkMode ? DARK_MENU_BG : UIManager.getColor("TabbedPane.background");
        Color tabFg = darkMode ? DARK_MENU_FG : UIManager.getColor("TabbedPane.foreground");
        if (tab.tabComponent != null) {
            tab.tabComponent.setOpaque(true);
            tab.tabComponent.setBackground(tabBg);
        }
        if (tab.titleLabel != null) tab.titleLabel.setForeground(tabFg);
        if (tab.closeButton != null) tab.closeButton.setForeground(tabFg);
    }

    private void applyMenuTheme(JMenuBar bar, boolean dark) {
        Color menuBg = dark ? DARK_MENU_BG : UIManager.getColor("MenuBar.background");
        Color menuFg = dark ? DARK_MENU_FG : UIManager.getColor("MenuBar.foreground");
        bar.setBackground(menuBg);
        bar.setForeground(menuFg);
        Color lineColor = dark ? new Color(55, 55, 55) : new Color(220, 220, 220);
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, lineColor),
                BorderFactory.createEmptyBorder(2, 4, 2, 4)));
        for (int i = 0; i < bar.getMenuCount(); i++) {
            JMenu menu = bar.getMenu(i);
            if (menu == null) continue;
            styleMenuComponent(menu, dark);
            applyMenuItemsTheme(menu, dark);
        }
    }

    private void applyMenuItemsTheme(JMenu menu, boolean dark) {
        menu.getPopupMenu().setBackground(dark ? DARK_MENU_BG : UIManager.getColor("PopupMenu.background"));
        for (Component c : menu.getMenuComponents()) {
            if (c instanceof JMenuItem item) {
                styleMenuComponent(item, dark);
                if (item instanceof JMenu subMenu) applyMenuItemsTheme(subMenu, dark);
            }
        }
    }

    private void styleMenuComponent(JMenuItem item, boolean dark) {
        item.setBackground(dark ? DARK_MENU_BG : UIManager.getColor("MenuItem.background"));
        item.setForeground(dark ? DARK_MENU_FG : UIManager.getColor("MenuItem.foreground"));
        item.setOpaque(true);
    }

    // ---------- File operations ----------

    private boolean confirmSaveChangesForTab(EditorTab tab) {
        if (!tab.isModified) return true;
        tabbedPane.setSelectedComponent(tab);
        int choice = JOptionPane.showConfirmDialog(this, "Do you want to save changes to " + tabLabel(tab).replace("*", "") + "?", "WaqomPad", JOptionPane.YES_NO_CANCEL_OPTION);
        if (choice == JOptionPane.CANCEL_OPTION || choice == JOptionPane.CLOSED_OPTION) return false;
        return choice != JOptionPane.YES_OPTION || saveTab(tab);
    }

    private void newTab() { addTab("Untitled"); updateTitle(); updateStatusBar(); updateWordCount(); }

    private void openFile() {
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            loadFileInTab(chooser.getSelectedFile());
        }
    }

    private void openRecentFile(File f) {
        if (!f.exists()) { JOptionPane.showMessageDialog(this, "This file no longer exists:\n" + f.getAbsolutePath()); recentFiles.remove(f.getAbsolutePath()); saveRecentFiles(); rebuildRecentMenu(); return; }
        loadFileInTab(f);
    }

    private void loadFileInTab(File f) {
        // If already open, just switch to it.
        for (EditorTab t : allTabs()) {
            if (t.currentFile != null && t.currentFile.getAbsoluteFile().equals(f.getAbsoluteFile())) {
                tabbedPane.setSelectedComponent(t);
                addToRecent(f);
                return;
            }
        }
        // Reuse a blank, untouched tab if the current one is empty; otherwise open a new tab.
        EditorTab cur = currentTab();
        EditorTab target = (cur != null && cur.currentFile == null && !cur.isModified && cur.textArea.getText().isEmpty())
                ? cur : addTab("Untitled");
        try {
            target.currentFile = f;
            target.textArea.setText(Files.readString(f.toPath(), StandardCharsets.UTF_8));
            target.textArea.setCaretPosition(0);
            target.isModified = false;
            target.undoManager.discardAllEdits();
            tabbedPane.setSelectedComponent(target);
            updateTabTitle(target);
            updateStatusBar(); updateWordCount();
            addToRecent(f);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Could not open file:\n" + ex.getMessage());
        }
    }

    private boolean saveTab(EditorTab tab) {
        if (tab.currentFile == null) return saveTabAs(tab);
        try {
            Files.writeString(tab.currentFile.toPath(), tab.textArea.getText(), StandardCharsets.UTF_8);
            tab.isModified = false;
            updateTabTitle(tab);
            addToRecent(tab.currentFile);
            deleteAutosave(tab);
            return true;
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Could not save file:\n" + ex.getMessage());
            return false;
        }
    }

    private boolean saveTabAs(EditorTab tab) {
        JFileChooser chooser = new JFileChooser();
        if (tab.currentFile != null) chooser.setSelectedFile(tab.currentFile);
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            tab.currentFile = chooser.getSelectedFile();
            return saveTab(tab);
        }
        return false;
    }

    private void closeTab(EditorTab tab) {
        if (!confirmSaveChangesForTab(tab)) return;
        deleteAutosave(tab);
        if (tabbedPane.getTabCount() == 1) { exitApp(); return; }
        tabbedPane.remove(tab);
    }

    private void printFile() {
        EditorTab t = currentTab();
        if (t == null) return;
        try { t.textArea.print(); } catch (PrinterException ex) { JOptionPane.showMessageDialog(this, "Print failed:\n" + ex.getMessage()); }
    }

    private void exitApp() {
        for (EditorTab t : allTabs()) {
            if (!confirmSaveChangesForTab(t)) return;
        }
        savePreferences();
        if (autoSaveTimer != null) autoSaveTimer.stop();
        for (EditorTab t : allTabs()) deleteAutosave(t);
        dispose();
    }

    // ---------- Recent files ----------

    private void addToRecent(File f) {
        String path = f.getAbsolutePath();
        recentFiles.remove(path);
        recentFiles.add(0, path);
        while (recentFiles.size() > MAX_RECENT) recentFiles.remove(recentFiles.size() - 1);
        saveRecentFiles();
        rebuildRecentMenu();
    }

    private void rebuildRecentMenu() {
        recentMenu.removeAll();
        if (recentFiles.isEmpty()) {
            JMenuItem none = new JMenuItem("No recent files");
            none.setEnabled(false);
            recentMenu.add(none);
        } else {
            for (String path : recentFiles) {
                File f = new File(path);
                JMenuItem item = new JMenuItem(f.getName());
                item.setToolTipText(path);
                item.addActionListener(e -> openRecentFile(f));
                recentMenu.add(item);
            }
            recentMenu.addSeparator();
            JMenuItem clear = new JMenuItem("Clear Recent Files");
            clear.addActionListener(e -> { recentFiles.clear(); saveRecentFiles(); rebuildRecentMenu(); });
            recentMenu.add(clear);
        }
    }

    private void saveRecentFiles() { PREFS.put("recentFiles", String.join("|", recentFiles)); }

    // ---------- Find / Replace / Go To ----------

    private void showFindDialog() {
        EditorTab t = currentTab(); if (t == null) return;
        String value = JOptionPane.showInputDialog(this, "Find:", t.lastFindText);
        if (value != null && !value.isEmpty()) { t.lastFindText = value; findText(false); }
    }

    private void findText(boolean previous) {
        EditorTab t = currentTab(); if (t == null) return;
        if (t.lastFindText == null || t.lastFindText.isEmpty()) { showFindDialog(); return; }
        String fullText = t.textArea.getText().toLowerCase();
        String query = t.lastFindText.toLowerCase();
        int caret = t.textArea.getCaretPosition();
        int index;
        if (previous) { index = fullText.lastIndexOf(query, Math.max(0, caret - query.length() - 1)); if (index < 0) index = fullText.lastIndexOf(query); }
        else { index = fullText.indexOf(query, caret); if (index < 0) index = fullText.indexOf(query); }
        if (index >= 0) { t.textArea.requestFocusInWindow(); t.textArea.select(index, index + t.lastFindText.length()); }
        else JOptionPane.showMessageDialog(this, "Cannot find \"" + t.lastFindText + "\"");
    }

    private void showReplaceDialog() {
        EditorTab t = currentTab(); if (t == null) return;
        JTextField findField = new JTextField(t.lastFindText, 20);
        JTextField replaceField = new JTextField(20);
        JCheckBox regexBox = new JCheckBox("Use regex (applies to Replace All)");
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        JPanel row1 = new JPanel(new GridLayout(2, 2, 8, 8));
        row1.add(new JLabel("Find:")); row1.add(findField);
        row1.add(new JLabel("Replace with:")); row1.add(replaceField);
        panel.add(row1);
        panel.add(Box.createVerticalStrut(8));
        panel.add(regexBox);

        Object[] options = {"Replace All", "Replace Next", "Cancel"};
        int choice = JOptionPane.showOptionDialog(this, panel, "Replace", JOptionPane.DEFAULT_OPTION,
                JOptionPane.PLAIN_MESSAGE, null, options, options[0]);

        String find = findField.getText();
        String replacement = replaceField.getText();
        if (find.isEmpty()) return;
        t.lastFindText = find;

        if (choice == 0) {
            String text = t.textArea.getText();
            try {
                String result = regexBox.isSelected() ? text.replaceAll(find, replacement) : text.replace(find, replacement);
                t.textArea.setText(result);
            } catch (PatternSyntaxException ex) {
                JOptionPane.showMessageDialog(this, "Invalid regular expression:\n" + ex.getMessage());
            }
        } else if (choice == 1) {
            if (regexBox.isSelected()) {
                JOptionPane.showMessageDialog(this, "Regex is only supported with Replace All.");
                return;
            }
            String selected = t.textArea.getSelectedText();
            if (selected != null && selected.equalsIgnoreCase(t.lastFindText)) t.textArea.replaceSelection(replacement);
            else { findText(false); selected = t.textArea.getSelectedText(); if (selected != null && selected.equalsIgnoreCase(t.lastFindText)) t.textArea.replaceSelection(replacement); }
        }
    }

    private void goToLine() {
        EditorTab t = currentTab(); if (t == null) return;
        String input = JOptionPane.showInputDialog(this, "Line number:"); if (input == null) return;
        try {
            int line = Integer.parseInt(input.trim()); int max = t.textArea.getLineCount();
            if (line < 1 || line > max) { JOptionPane.showMessageDialog(this, "Line number must be between 1 and " + max + "."); return; }
            t.textArea.setCaretPosition(t.textArea.getLineStartOffset(line - 1)); t.textArea.requestFocusInWindow();
        } catch (Exception ex) { JOptionPane.showMessageDialog(this, "Invalid line number."); }
    }

    private void insertTimeDate() {
        EditorTab t = currentTab(); if (t == null) return;
        t.textArea.replaceSelection(new SimpleDateFormat("hh:mm a dd/MM/yyyy").format(new Date()));
    }

    // ---------- Case conversion ----------

    private void convertSelectionCase(int mode) {
        EditorTab t = currentTab(); if (t == null) return;
        String selected = t.textArea.getSelectedText();
        if (selected == null || selected.isEmpty()) { JOptionPane.showMessageDialog(this, "Select some text first."); return; }
        String converted;
        switch (mode) {
            case 0 -> converted = selected.toUpperCase();
            case 1 -> converted = selected.toLowerCase();
            default -> converted = toTitleCase(selected);
        }
        t.textArea.replaceSelection(converted);
    }

    private String toTitleCase(String s) {
        StringBuilder sb = new StringBuilder();
        boolean capitalizeNext = true;
        for (char c : s.toCharArray()) {
            if (Character.isWhitespace(c)) { capitalizeNext = true; sb.append(c); }
            else if (capitalizeNext) { sb.append(Character.toUpperCase(c)); capitalizeNext = false; }
            else sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    // ---------- Line operations ----------

    private void duplicateLine() {
        EditorTab t = currentTab(); if (t == null) return;
        try {
            int caret = t.textArea.getCaretPosition();
            int line = t.textArea.getLineOfOffset(caret);
            int start = t.textArea.getLineStartOffset(line);
            int end = t.textArea.getLineEndOffset(line);
            String lineText = t.textArea.getText(start, end - start);
            t.textArea.insert(lineText, end);
            t.textArea.setCaretPosition(Math.min(end + lineText.length(), t.textArea.getDocument().getLength()));
        } catch (Exception ignored) {}
    }

    /** Swaps the current line with the line above (direction -1) or below (direction +1), caret following the moved line. */
    private void moveLine(int direction) {
        EditorTab t = currentTab(); if (t == null) return;
        try {
            JTextArea ta = t.textArea;
            int caret = ta.getCaretPosition();
            int line = ta.getLineOfOffset(caret);
            int other = line + direction;
            if (other < 0 || other >= ta.getLineCount()) return;

            int curStart = ta.getLineStartOffset(line), curEnd = ta.getLineEndOffset(line);
            int otherStart = ta.getLineStartOffset(other), otherEnd = ta.getLineEndOffset(other);
            String curText = ta.getText(curStart, curEnd - curStart);
            String otherText = ta.getText(otherStart, otherEnd - otherStart);
            int offset = caret - curStart;

            int rangeStart = Math.min(curStart, otherStart);
            int rangeEnd = Math.max(curEnd, otherEnd);
            boolean up = direction < 0;
            ta.replaceRange(up ? curText + otherText : otherText + curText, rangeStart, rangeEnd);
            ta.setCaretPosition(rangeStart + (up ? 0 : otherText.length()) + offset);
        } catch (Exception ignored) {}
    }

    // ---------- Font / zoom ----------

    private void chooseFont() {
        EditorTab cur = currentTab();
        Font base = cur != null ? cur.textArea.getFont() : new Font(Font.MONOSPACED, Font.PLAIN, fontSize);
        String[] fonts = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        JComboBox<String> fontBox = new JComboBox<>(fonts); fontBox.setSelectedItem(base.getFamily());
        JSpinner sizeSpinner = new JSpinner(new SpinnerNumberModel(base.getSize(), 8, 72, 1));
        JPanel panel = new JPanel(new GridLayout(2, 2, 8, 8));
        panel.add(new JLabel("Font:")); panel.add(fontBox); panel.add(new JLabel("Size:")); panel.add(sizeSpinner);
        if (JOptionPane.showConfirmDialog(this, panel, "Font", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
            Font newFont = new Font((String) fontBox.getSelectedItem(), Font.PLAIN, (int) sizeSpinner.getValue());
            fontSize = newFont.getSize();
            for (EditorTab t : allTabs()) {
                t.textArea.setFont(newFont);
                t.lineNumberArea.setFont(newFont);
                t.lineNumberArea.updateWidth();
            }
            updateZoomLabel();
        }
    }

    private void setEditorFontSize(int size) {
        if (size < 8 || size > 72) return;
        fontSize = size;
        for (EditorTab t : allTabs()) {
            Font newFont = t.textArea.getFont().deriveFont((float) fontSize);
            t.textArea.setFont(newFont);
            t.lineNumberArea.setFont(newFont);
            t.lineNumberArea.updateWidth();
        }
        updateZoomLabel();
    }

    // ---------- Misc ----------

    private void searchWithBing() {
        EditorTab t = currentTab(); if (t == null) return;
        String query = t.textArea.getSelectedText();
        if (query == null || query.isBlank()) query = JOptionPane.showInputDialog(this, "Search with Bing:");
        if (query == null || query.isBlank()) return;
        try { Desktop.getDesktop().browse(new URI("https://www.bing.com/search?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8))); }
        catch (Exception ex) { JOptionPane.showMessageDialog(this, "Could not open browser."); }
    }

    private Image loadIcon() {
        try { File iconFile = new File("assets/waqompad_icon.png"); if (iconFile.exists()) return new ImageIcon(iconFile.getAbsolutePath()).getImage(); }
        catch (Exception ignored) {}
        return createAppIcon();
    }

    private Image createAppIcon() {
        int size = 128;
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(245, 248, 255)); g.fillRoundRect(24, 14, 80, 100, 14, 14);
        g.setColor(new Color(30, 105, 210)); g.setStroke(new BasicStroke(5)); g.drawRoundRect(24, 14, 80, 100, 14, 14);
        g.setStroke(new BasicStroke(4)); for (int y = 42; y <= 78; y += 14) g.drawLine(38, y, 90, y);
        g.setFont(new Font("SansSerif", Font.BOLD, 38)); g.drawString("W", 47, 108);
        g.dispose(); return image;
    }

    // ---------- Preferences ----------

    private void loadPreferences() {
        fontSize = PREFS.getInt("fontSize", 18);
        darkMode = PREFS.getBoolean("darkMode", false);
        String stored = PREFS.get("recentFiles", "");
        recentFiles.clear();
        if (!stored.isEmpty()) {
            for (String p : stored.split("\\|")) if (!p.isBlank()) recentFiles.add(p);
        }
    }

    private void applySavedWindowBounds() {
        int w = PREFS.getInt("winW", -1);
        int h = PREFS.getInt("winH", -1);
        int x = PREFS.getInt("winX", -1);
        int y = PREFS.getInt("winY", -1);
        if (w > 100 && h > 100 && x >= 0 && y >= 0) {
            setBounds(x, y, w, h);
        } else {
            setLocationRelativeTo(null);
        }
    }

    private void savePreferences() {
        PREFS.putInt("fontSize", fontSize);
        PREFS.putBoolean("darkMode", darkMode);
        PREFS.putBoolean("wordWrap", wordWrapItem.isSelected());
        PREFS.putBoolean("lineNumbers", lineNumbersItem.isSelected());
        PREFS.putBoolean("alwaysOnTop", alwaysOnTopItem.isSelected());
        Rectangle b = getBounds();
        PREFS.putInt("winX", b.x);
        PREFS.putInt("winY", b.y);
        PREFS.putInt("winW", b.width);
        PREFS.putInt("winH", b.height);
        saveRecentFiles();
    }

    // ---------- Autosave / crash recovery ----------

    private void setupAutoSave() {
        autoSaveTimer = new javax.swing.Timer(30_000, e -> performAutoSave());
        autoSaveTimer.setRepeats(true);
        autoSaveTimer.start();
    }

    private File autosaveFile(EditorTab tab) { return new File(AUTOSAVE_DIR, tab.autosaveId + ".tmp"); }

    private void deleteAutosave(EditorTab tab) {
        File f = autosaveFile(tab);
        if (f.exists()) f.delete();
    }

    private void performAutoSave() {
        for (EditorTab t : allTabs()) {
            File f = autosaveFile(t);
            if (t.isModified) {
                try {
                    String marker = t.currentFile == null ? "UNTITLED" : t.currentFile.getAbsolutePath();
                    Files.writeString(f.toPath(), marker + "\n" + t.textArea.getText(), StandardCharsets.UTF_8);
                } catch (IOException ignored) {}
            } else if (f.exists()) {
                f.delete();
            }
        }
    }

    private void checkForRecovery() {
        File[] files = AUTOSAVE_DIR.listFiles((dir, name) -> name.endsWith(".tmp"));
        if (files == null || files.length == 0) return;
        int choice = JOptionPane.showConfirmDialog(this,
                "WaqomPad found " + files.length + " unsaved tab(s) from a previous session. Recover them?",
                "Recover unsaved changes", JOptionPane.YES_NO_OPTION);
        if (choice == JOptionPane.YES_OPTION) {
            boolean firstUsed = false;
            for (File f : files) {
                try {
                    String content = Files.readString(f.toPath(), StandardCharsets.UTF_8);
                    int newline = content.indexOf('\n');
                    if (newline < 0) { f.delete(); continue; }
                    String marker = content.substring(0, newline);
                    String body = content.substring(newline + 1);

                    EditorTab target;
                    if (!firstUsed) {
                        target = currentTab();
                        firstUsed = true;
                    } else {
                        target = addTab("Untitled");
                    }
                    target.textArea.setText(body);
                    if (!"UNTITLED".equals(marker)) {
                        File orig = new File(marker);
                        if (orig.exists()) target.currentFile = orig;
                    }
                    target.isModified = true;
                    target.undoManager.discardAllEdits();
                    updateTabTitle(target);
                } catch (IOException ignored) {}
                f.delete();
            }
            updateStatusBar(); updateWordCount();
        } else {
            for (File f : files) f.delete();
        }
    }

    // ---------- Editor tab ----------

    private static class EditorTab extends JPanel {
        final JTextArea textArea = new JTextArea();
        final UndoManager undoManager = new UndoManager();
        final JScrollPane scrollPane;
        final LineNumberArea lineNumberArea;
        final String autosaveId = UUID.randomUUID().toString();
        File currentFile = null;
        boolean isModified = false;
        String lastFindText = "";
        JLabel titleLabel;
        JPanel tabComponent;
        JButton closeButton;

        EditorTab() {
            super(new BorderLayout());
            lineNumberArea = new LineNumberArea(textArea);
            scrollPane = new JScrollPane(textArea);
            scrollPane.setBorder(BorderFactory.createEmptyBorder());
            add(scrollPane, BorderLayout.CENTER);
        }
    }

    // ---------- Line number gutter ----------

    private static class LineNumberArea extends JComponent {
        private final JTextArea textArea;

        LineNumberArea(JTextArea textArea) {
            this.textArea = textArea;
            setFont(textArea.getFont());
            setOpaque(true);
            updateWidth();
        }

        void updateWidth() {
            FontMetrics fm = getFontMetrics(getFont());
            int lines = Math.max(1, textArea.getLineCount());
            int digits = Math.max(2, String.valueOf(lines).length());
            int width = fm.charWidth('0') * digits + 16;
            Dimension current = getPreferredSize();
            if (current == null || current.width != width) {
                setPreferredSize(new Dimension(width, textArea.getHeight()));
                revalidate();
            }
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            g.setColor(getBackground());
            g.fillRect(0, 0, getWidth(), getHeight());
            FontMetrics fm = g.getFontMetrics(getFont());
            int lineHeight = fm.getHeight();
            Rectangle clip = g.getClipBounds();
            int startLine = Math.max(0, clip.y / lineHeight);
            int endLine = (clip.y + clip.height) / lineHeight + 1;
            int total = textArea.getLineCount();
            g.setColor(getForeground());
            g.setFont(getFont());
            for (int line = startLine; line <= endLine && line < total; line++) {
                int y = (line + 1) * lineHeight - fm.getDescent();
                String num = String.valueOf(line + 1);
                int x = getWidth() - fm.stringWidth(num) - 6;
                g.drawString(num, x, y);
            }
        }
    }
}