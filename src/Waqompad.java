import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.Utilities;
import javax.swing.undo.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.event.*;
import java.awt.geom.Rectangle2D;
import java.awt.print.PrinterException;
import java.io.File;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.prefs.Preferences;

public class Waqompad extends JFrame {
    private final JTextArea textArea = new JTextArea();
    private final JLabel positionLabel = new JLabel("Ln 1, Col 1");
    private final JLabel wordCountLabel = new JLabel("0 words");
    private final JPanel statusBar = new JPanel(new BorderLayout());
    private final UndoManager undoManager = new UndoManager();
    private File currentFile = null;
    private boolean isModified = false;
    private String lastFindText = "";
    private int fontSize = 18;
    private boolean darkMode = false;
    private boolean lineNumbersEnabled = false;
    private boolean readOnly = false;
    private Charset currentEncoding = StandardCharsets.UTF_8;
    private long lastKnownModified = 0L;

    private final List<String> recentFiles = new ArrayList<>();
    private final Preferences prefs = Preferences.userNodeForPackage(Waqompad.class);
    private final File recoveryFile = new File(System.getProperty("user.home"), ".waqompad_recovery.tmp");
    private Timer autosaveTimer;

    private JMenuBar menuBar;
    private JMenu recentMenu;
    private JScrollPane scrollPane;
    private LineNumberGutter lineNumberGutter;
    private JCheckBoxMenuItem darkModeItem;

    // Theme colors
    private static final Color LIGHT_BG = Color.WHITE;
    private static final Color LIGHT_FG = Color.BLACK;
    private static final Color LIGHT_SELECTION = new Color(180, 213, 255);
    private static final Color LIGHT_STATUSBAR_BG = new Color(240, 240, 240);

    private static final Color DARK_BG = new Color(30, 30, 30);
    private static final Color DARK_FG = new Color(220, 220, 220);
    private static final Color DARK_SELECTION = new Color(70, 90, 140);
    private static final Color DARK_STATUSBAR_BG = new Color(45, 45, 45);
    private static final Color DARK_MENU_BG = new Color(40, 40, 40);
    private static final Color DARK_MENU_FG = new Color(220, 220, 220);

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                System.setProperty("apple.awt.application.name", "WaqomPad");
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {}
            new Waqompad().setVisible(true);
        });
    }

    public Waqompad() {
        super("Untitled - WaqomPad");

        // ---- Load persisted preferences ----
        String fontFamily = Font.MONOSPACED;
        int winX = -1, winY = -1, winW = 1100, winH = 720;
        try {
            darkMode = prefs.getBoolean("darkMode", false);
            fontSize = prefs.getInt("fontSize", 18);
            fontFamily = prefs.get("fontFamily", Font.MONOSPACED);
            lineNumbersEnabled = prefs.getBoolean("lineNumbers", false);
            currentEncoding = Charset.forName(prefs.get("encoding", "UTF-8"));
            winX = prefs.getInt("winX", -1);
            winY = prefs.getInt("winY", -1);
            winW = prefs.getInt("winW", 1100);
            winH = prefs.getInt("winH", 720);
            String saved = prefs.get("recentFiles", "");
            if (!saved.isEmpty()) {
                for (String p : saved.split("\n")) if (!p.isBlank()) recentFiles.add(p);
            }
        } catch (Exception ignored) {}

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        if (winX >= 0 && winY >= 0) {
            setBounds(winX, winY, winW, winH);
        } else {
            setSize(winW, winH);
            setLocationRelativeTo(null);
        }
        setIconImage(loadIcon());

        textArea.setFont(new Font(fontFamily, Font.PLAIN, fontSize));
        textArea.setBackground(Color.WHITE);
        textArea.setForeground(Color.BLACK);
        textArea.setCaretColor(Color.BLACK);
        textArea.setMargin(new Insets(4, 6, 4, 6));
        textArea.setLineWrap(false);
        textArea.setWrapStyleWord(false);
        textArea.setDropTarget(new DropTarget(textArea, new DropTargetAdapter() {
            @Override
            public void drop(DropTargetDropEvent event) {
                try {
                    event.acceptDrop(DnDConstants.ACTION_COPY);
                    @SuppressWarnings("unchecked")
                    List<File> files = (List<File>) event.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    if (!files.isEmpty()) {
                        if (!confirmSaveChanges()) return;
                        loadFileIntoEditor(files.get(0));
                    }
                } catch (Exception ignored) {}
            }
        }));

        lineNumberGutter = new LineNumberGutter();
        scrollPane = new JScrollPane(textArea);
        if (lineNumbersEnabled) scrollPane.setRowHeaderView(lineNumberGutter);
        scrollPane.addMouseWheelListener(e -> {
            if (e.isControlDown()) {
                setEditorFontSize(fontSize + (e.getWheelRotation() < 0 ? 2 : -2));
                e.consume();
            }
        });
        add(scrollPane, BorderLayout.CENTER);

        statusBar.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        statusBar.setOpaque(true);
        statusBar.add(positionLabel, BorderLayout.WEST);
        statusBar.add(wordCountLabel, BorderLayout.EAST);
        add(statusBar, BorderLayout.SOUTH);

        menuBar = createMenuBar();
        setJMenuBar(menuBar);
        rebuildRecentMenu();

        textArea.getDocument().addUndoableEditListener(e -> undoManager.addEdit(e.getEdit()));
        textArea.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { markModified(); }
            public void removeUpdate(DocumentEvent e) { markModified(); }
            public void changedUpdate(DocumentEvent e) { markModified(); }
        });
        textArea.addCaretListener(e -> updateStatusBar());
        addWindowListener(new WindowAdapter() { public void windowClosing(WindowEvent e) { exitApp(); } });
        addWindowFocusListener(new WindowAdapter() {
            @Override public void windowGainedFocus(WindowEvent e) { checkExternalModification(); }
        });

        isModified = false;
        updateTitle();
        updateStatusBar();
        applyTheme();

        autosaveTimer = new Timer(30_000, e -> autoSaveRecovery());
        autosaveTimer.start();
        SwingUtilities.invokeLater(this::checkForRecovery);
    }

    private JMenuBar createMenuBar() {
        JMenuBar menuBar = new JMenuBar();
        JMenu file = new JMenu("File");
        file.add(createItem("New", KeyEvent.VK_N, 0, e -> newFile()));
        file.add(createItem("New Window", KeyEvent.VK_N, InputEvent.SHIFT_DOWN_MASK, e -> new Waqompad().setVisible(true)));
        file.add(createItem("Open", KeyEvent.VK_O, 0, e -> openFile()));
        recentMenu = new JMenu("Recent Files");
        file.add(recentMenu);
        file.add(createItem("Save", KeyEvent.VK_S, 0, e -> saveFile()));
        file.add(createItem("Save As", KeyEvent.VK_S, InputEvent.SHIFT_DOWN_MASK, e -> saveAsFile()));
        file.addSeparator();
        JMenu encodingMenu = new JMenu("Encoding");
        ButtonGroup encodingGroup = new ButtonGroup();
        addEncodingItem(encodingMenu, encodingGroup, "UTF-8", StandardCharsets.UTF_8);
        addEncodingItem(encodingMenu, encodingGroup, "UTF-16", StandardCharsets.UTF_16);
        addEncodingItem(encodingMenu, encodingGroup, "ANSI (ISO-8859-1)", StandardCharsets.ISO_8859_1);
        file.add(encodingMenu);
        file.addSeparator();
        file.add(new JMenuItem(new AbstractAction("Page Setup") { public void actionPerformed(ActionEvent e) { JOptionPane.showMessageDialog(Waqompad.this, "Page setup is handled by the native print dialog."); }}));
        file.add(createItem("Print", KeyEvent.VK_P, 0, e -> printFile()));
        file.addSeparator();
        file.add(new JMenuItem(new AbstractAction("Exit") { public void actionPerformed(ActionEvent e) { exitApp(); }}));

        JMenu edit = new JMenu("Edit");
        edit.add(createItem("Undo", KeyEvent.VK_Z, 0, e -> { if (undoManager.canUndo()) undoManager.undo(); }));
        edit.add(createItem("Redo", KeyEvent.VK_Y, 0, e -> { if (undoManager.canRedo()) undoManager.redo(); }));
        edit.addSeparator();
        edit.add(createItem("Cut", KeyEvent.VK_X, 0, e -> textArea.cut()));
        edit.add(createItem("Copy", KeyEvent.VK_C, 0, e -> textArea.copy()));
        edit.add(createItem("Paste", KeyEvent.VK_V, 0, e -> textArea.paste()));
        edit.add(new JMenuItem(new AbstractAction("Delete") { public void actionPerformed(ActionEvent e) { textArea.replaceSelection(""); }}));
        edit.addSeparator();
        edit.add(new JMenuItem(new AbstractAction("Search with Bing") { public void actionPerformed(ActionEvent e) { searchWithBing(); }}));
        edit.add(createItem("Find", KeyEvent.VK_F, 0, e -> showFindDialog()));
        edit.add(new JMenuItem(new AbstractAction("Find Next") { public void actionPerformed(ActionEvent e) { findText(false); }}));
        edit.add(new JMenuItem(new AbstractAction("Find Previous") { public void actionPerformed(ActionEvent e) { findText(true); }}));
        edit.add(createItem("Replace", KeyEvent.VK_H, 0, e -> showReplaceDialog()));
        edit.add(createItem("Go To", KeyEvent.VK_G, 0, e -> goToLine()));
        edit.addSeparator();
        edit.add(createItem("Select All", KeyEvent.VK_A, 0, e -> textArea.selectAll()));
        JMenuItem timeDate = new JMenuItem(new AbstractAction("Time/Date") { public void actionPerformed(ActionEvent e) { insertTimeDate(); }});
        timeDate.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0));
        edit.add(timeDate);
        edit.addSeparator();

        JMenuItem duplicateLineItem = new JMenuItem(new AbstractAction("Duplicate Line") { public void actionPerformed(ActionEvent e) { duplicateLine(); }});
        duplicateLineItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_D, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        edit.add(duplicateLineItem);
        JMenuItem moveUpItem = new JMenuItem(new AbstractAction("Move Line Up") { public void actionPerformed(ActionEvent e) { moveLine(true); }});
        moveUpItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_UP, InputEvent.ALT_DOWN_MASK));
        edit.add(moveUpItem);
        JMenuItem moveDownItem = new JMenuItem(new AbstractAction("Move Line Down") { public void actionPerformed(ActionEvent e) { moveLine(false); }});
        moveDownItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, InputEvent.ALT_DOWN_MASK));
        edit.add(moveDownItem);
        edit.addSeparator();

        JMenu caseMenu = new JMenu("Convert Case");
        caseMenu.add(new JMenuItem(new AbstractAction("UPPERCASE") { public void actionPerformed(ActionEvent e) { convertCase(0); }}));
        caseMenu.add(new JMenuItem(new AbstractAction("lowercase") { public void actionPerformed(ActionEvent e) { convertCase(1); }}));
        caseMenu.add(new JMenuItem(new AbstractAction("Title Case") { public void actionPerformed(ActionEvent e) { convertCase(2); }}));
        edit.add(caseMenu);
        edit.add(new JMenuItem(new AbstractAction("Sort Lines") { public void actionPerformed(ActionEvent e) { sortLines(); }}));
        edit.addSeparator();
        JCheckBoxMenuItem readOnlyItem = new JCheckBoxMenuItem("Read-Only Mode", readOnly);
        readOnlyItem.addActionListener(e -> { readOnly = readOnlyItem.isSelected(); textArea.setEditable(!readOnly); });
        edit.add(readOnlyItem);
        edit.add(new JMenuItem(new AbstractAction("Word Count...") { public void actionPerformed(ActionEvent e) { showWordCount(); }}));

        JMenu format = new JMenu("Format");
        JCheckBoxMenuItem wordWrap = new JCheckBoxMenuItem("Word Wrap");
        wordWrap.addActionListener(e -> { textArea.setLineWrap(wordWrap.isSelected()); textArea.setWrapStyleWord(wordWrap.isSelected()); });
        format.add(wordWrap);
        format.add(new JMenuItem(new AbstractAction("Font") { public void actionPerformed(ActionEvent e) { chooseFont(); }}));

        JMenu view = new JMenu("View");
        view.add(new JMenuItem(new AbstractAction("Zoom In") { public void actionPerformed(ActionEvent e) { setEditorFontSize(fontSize + 2); }}));
        view.add(new JMenuItem(new AbstractAction("Zoom Out") { public void actionPerformed(ActionEvent e) { setEditorFontSize(fontSize - 2); }}));
        view.add(new JMenuItem(new AbstractAction("Restore Default Zoom") { public void actionPerformed(ActionEvent e) { setEditorFontSize(18); }}));
        view.addSeparator();
        JCheckBoxMenuItem status = new JCheckBoxMenuItem("Status Bar", true);
        status.addActionListener(e -> statusBar.setVisible(status.isSelected()));
        view.add(status);
        JCheckBoxMenuItem lineNumbersItem = new JCheckBoxMenuItem("Line Numbers", lineNumbersEnabled);
        lineNumbersItem.addActionListener(e -> {
            lineNumbersEnabled = lineNumbersItem.isSelected();
            scrollPane.setRowHeaderView(lineNumbersEnabled ? lineNumberGutter : null);
        });
        view.add(lineNumbersItem);
        view.addSeparator();
        darkModeItem = new JCheckBoxMenuItem("Dark Mode", darkMode);
        darkModeItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_D, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() | InputEvent.SHIFT_DOWN_MASK));
        darkModeItem.addActionListener(e -> { darkMode = darkModeItem.isSelected(); applyTheme(); });
        view.add(darkModeItem);
        JCheckBoxMenuItem alwaysOnTopItem = new JCheckBoxMenuItem("Always on Top");
        alwaysOnTopItem.addActionListener(e -> {
            try { setAlwaysOnTop(alwaysOnTopItem.isSelected()); }
            catch (Exception ex) { JOptionPane.showMessageDialog(Waqompad.this, "Always on top is not supported on this platform."); alwaysOnTopItem.setSelected(false); }
        });
        view.add(alwaysOnTopItem);

        JMenu help = new JMenu("Help");
        help.add(new JMenuItem(new AbstractAction("View Help") { public void actionPerformed(ActionEvent e) { JOptionPane.showMessageDialog(Waqompad.this, "WaqomPad Help\n\nUse File, Edit, Format, View and Help menus just like classic Notepad.\nToggle View > Dark Mode to switch themes.\nDrag and drop a file onto the editor to open it."); }}));
        help.add(new JMenuItem(new AbstractAction("Send Feedback") { public void actionPerformed(ActionEvent e) { JOptionPane.showMessageDialog(Waqompad.this, "Feedback is offline in this version. Share feedback manually."); }}));
        help.add(new JMenuItem(new AbstractAction("About WaqomPad") { public void actionPerformed(ActionEvent e) { JOptionPane.showMessageDialog(Waqompad.this, "WaqomPad\nProfessional Java Swing Notepad\nNative cross-platform desktop text editor.", "About WaqomPad", JOptionPane.INFORMATION_MESSAGE); }}));

        menuBar.add(file); menuBar.add(edit); menuBar.add(format); menuBar.add(view); menuBar.add(help);
        return menuBar;
    }

    private void addEncodingItem(JMenu menu, ButtonGroup group, String label, Charset charset) {
        JRadioButtonMenuItem item = new JRadioButtonMenuItem(label, currentEncoding.equals(charset));
        item.addActionListener(e -> currentEncoding = charset);
        group.add(item);
        menu.add(item);
    }

    private JMenuItem createItem(String title, int key, int extraMask, ActionListener action) {
        JMenuItem item = new JMenuItem(title);
        int mask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        item.setAccelerator(KeyStroke.getKeyStroke(key, mask | extraMask));
        item.addActionListener(action);
        return item;
    }

    private void markModified() { if (!isModified) { isModified = true; updateTitle(); } updateStatusBar(); }
    private void updateTitle() { String name = currentFile == null ? "Untitled" : currentFile.getName(); setTitle((isModified ? "*" : "") + name + " - WaqomPad"); }

    private void updateStatusBar() {
        try {
            int pos = textArea.getCaretPosition();
            int line = textArea.getLineOfOffset(pos);
            int col = pos - textArea.getLineStartOffset(line);
            positionLabel.setText("Ln " + (line + 1) + ", Col " + (col + 1));
        } catch (Exception ignored) {}
        updateWordCount();
        if (lineNumbersEnabled) { lineNumberGutter.revalidate(); lineNumberGutter.repaint(); }
    }

    private void updateWordCount() {
        String text = textArea.getText();
        String trimmed = text.trim();
        int words = trimmed.isEmpty() ? 0 : trimmed.split("\\s+").length;
        wordCountLabel.setText(words + " words, " + text.length() + " chars");
    }

    private void showWordCount() {
        String text = textArea.getText();
        String trimmed = text.trim();
        int words = trimmed.isEmpty() ? 0 : trimmed.split("\\s+").length;
        int chars = text.length();
        int charsNoSpaces = text.replaceAll("\\s", "").length();
        int lines = textArea.getLineCount();
        JOptionPane.showMessageDialog(this,
                "Lines: " + lines + "\nWords: " + words + "\nCharacters: " + chars + "\nCharacters (no spaces): " + charsNoSpaces,
                "Word Count", JOptionPane.INFORMATION_MESSAGE);
    }

    /**
     * Applies the current theme (dark or light) to all UI components:
     * the text area, status bar, menu bar, and content pane.
     */
    private void applyTheme() {
        Color bg = darkMode ? DARK_BG : LIGHT_BG;
        Color fg = darkMode ? DARK_FG : LIGHT_FG;
        Color selection = darkMode ? DARK_SELECTION : LIGHT_SELECTION;
        Color statusBg = darkMode ? DARK_STATUSBAR_BG : LIGHT_STATUSBAR_BG;
        Color secondaryFg = darkMode ? new Color(160, 160, 160) : new Color(90, 90, 90);

        textArea.setBackground(bg);
        textArea.setForeground(fg);
        textArea.setCaretColor(fg);
        textArea.setSelectionColor(selection);
        textArea.setSelectedTextColor(darkMode ? Color.WHITE : Color.BLACK);

        statusBar.setBackground(statusBg);
        positionLabel.setForeground(fg);
        wordCountLabel.setForeground(secondaryFg);

        getContentPane().setBackground(bg);
        if (scrollPane != null) {
            scrollPane.getViewport().setBackground(bg);
            scrollPane.setBackground(bg);
        }
        if (lineNumberGutter != null) lineNumberGutter.repaint();

        if (menuBar != null) applyMenuTheme(menuBar, darkMode);
        repaint();
    }

    private void applyMenuTheme(JMenuBar bar, boolean dark) {
        Color menuBg = dark ? DARK_MENU_BG : UIManager.getColor("MenuBar.background");
        Color menuFg = dark ? DARK_MENU_FG : UIManager.getColor("MenuBar.foreground");
        bar.setBackground(menuBg);
        bar.setForeground(menuFg);
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

    private boolean confirmSaveChanges() {
        if (!isModified) return true;
        int choice = JOptionPane.showConfirmDialog(this, "Do you want to save changes?", "WaqomPad", JOptionPane.YES_NO_CANCEL_OPTION);
        if (choice == JOptionPane.CANCEL_OPTION || choice == JOptionPane.CLOSED_OPTION) return false;
        return choice != JOptionPane.YES_OPTION || saveFile();
    }

    private void newFile() { if (!confirmSaveChanges()) return; textArea.setText(""); currentFile = null; isModified = false; lastKnownModified = 0L; undoManager.discardAllEdits(); updateTitle(); updateStatusBar(); }

    private void openFile() {
        if (!confirmSaveChanges()) return;
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            try { loadFileIntoEditor(chooser.getSelectedFile()); }
            catch (Exception ex) { JOptionPane.showMessageDialog(this, "Could not open file:\n" + ex.getMessage()); }
        }
    }

    private void loadFileIntoEditor(File f) throws Exception {
        String content = Files.readString(f.toPath(), currentEncoding);
        textArea.setText(content);
        textArea.setCaretPosition(0);
        currentFile = f;
        isModified = false;
        undoManager.discardAllEdits();
        lastKnownModified = f.lastModified();
        updateTitle();
        updateStatusBar();
        addRecentFile(f.getAbsolutePath());
    }

    private boolean saveFile() {
        if (currentFile == null) return saveAsFile();
        try {
            Files.writeString(currentFile.toPath(), textArea.getText(), currentEncoding);
            isModified = false;
            lastKnownModified = currentFile.lastModified();
            updateTitle();
            addRecentFile(currentFile.getAbsolutePath());
            recoveryFile.delete();
            return true;
        }
        catch (Exception ex) { JOptionPane.showMessageDialog(this, "Could not save file:\n" + ex.getMessage()); return false; }
    }

    private boolean saveAsFile() {
        JFileChooser chooser = new JFileChooser();
        if (currentFile != null) chooser.setSelectedFile(currentFile);
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) { currentFile = chooser.getSelectedFile(); return saveFile(); }
        return false;
    }

    private void printFile() { try { textArea.print(); } catch (PrinterException ex) { JOptionPane.showMessageDialog(this, "Print failed:\n" + ex.getMessage()); } }

    private void exitApp() {
        if (confirmSaveChanges()) {
            savePreferencesAndCleanup();
            dispose();
        }
    }

    private void savePreferencesAndCleanup() {
        try {
            prefs.putBoolean("darkMode", darkMode);
            prefs.putInt("fontSize", fontSize);
            prefs.put("fontFamily", textArea.getFont().getFamily());
            prefs.putBoolean("lineNumbers", lineNumbersEnabled);
            prefs.put("encoding", currentEncoding.name());
            Rectangle b = getBounds();
            prefs.putInt("winX", b.x);
            prefs.putInt("winY", b.y);
            prefs.putInt("winW", b.width);
            prefs.putInt("winH", b.height);
            prefs.put("recentFiles", String.join("\n", recentFiles));
        } catch (Exception ignored) {}
        if (autosaveTimer != null) autosaveTimer.stop();
        recoveryFile.delete();
    }

    // ---- Recent files ----
    private void addRecentFile(String path) {
        recentFiles.remove(path);
        recentFiles.add(0, path);
        while (recentFiles.size() > 8) recentFiles.remove(recentFiles.size() - 1);
        rebuildRecentMenu();
    }

    private void rebuildRecentMenu() {
        if (recentMenu == null) return;
        recentMenu.removeAll();
        if (recentFiles.isEmpty()) {
            JMenuItem none = new JMenuItem("No recent files");
            none.setEnabled(false);
            recentMenu.add(none);
        } else {
            for (String path : recentFiles) {
                JMenuItem item = new JMenuItem(path);
                item.addActionListener(e -> openRecentFile(path));
                recentMenu.add(item);
            }
            recentMenu.addSeparator();
            JMenuItem clear = new JMenuItem("Clear Recent Files");
            clear.addActionListener(e -> { recentFiles.clear(); rebuildRecentMenu(); });
            recentMenu.add(clear);
        }
    }

    private void openRecentFile(String path) {
        if (!confirmSaveChanges()) return;
        File f = new File(path);
        if (!f.exists()) {
            JOptionPane.showMessageDialog(this, "File no longer exists:\n" + path);
            recentFiles.remove(path);
            rebuildRecentMenu();
            return;
        }
        try { loadFileIntoEditor(f); }
        catch (Exception ex) { JOptionPane.showMessageDialog(this, "Could not open file:\n" + ex.getMessage()); }
    }

    // ---- External modification detection ----
    private void checkExternalModification() {
        if (currentFile == null || !currentFile.exists()) return;
        long modified = currentFile.lastModified();
        if (modified != lastKnownModified) {
            int choice = JOptionPane.showConfirmDialog(this,
                    currentFile.getName() + " was modified outside WaqomPad.\nReload it from disk? (Unsaved changes will be lost.)",
                    "File Changed", JOptionPane.YES_NO_OPTION);
            if (choice == JOptionPane.YES_OPTION) {
                try { loadFileIntoEditor(currentFile); }
                catch (Exception ex) { JOptionPane.showMessageDialog(this, "Could not reload file:\n" + ex.getMessage()); }
            } else {
                lastKnownModified = modified;
            }
        }
    }

    // ---- Autosave / crash recovery ----
    private void autoSaveRecovery() {
        if (!isModified) return;
        try { Files.writeString(recoveryFile.toPath(), textArea.getText(), StandardCharsets.UTF_8); } catch (Exception ignored) {}
    }

    private void checkForRecovery() {
        try {
            if (recoveryFile.exists() && recoveryFile.length() > 0) {
                int choice = JOptionPane.showConfirmDialog(this,
                        "WaqomPad found unsaved changes from a previous session.\nRecover them now?",
                        "Recover Unsaved Changes", JOptionPane.YES_NO_OPTION);
                if (choice == JOptionPane.YES_OPTION) {
                    textArea.setText(Files.readString(recoveryFile.toPath(), StandardCharsets.UTF_8));
                    isModified = true;
                    updateTitle();
                    updateStatusBar();
                }
                recoveryFile.delete();
            }
        } catch (Exception ignored) {}
    }

    private void showFindDialog() { String value = JOptionPane.showInputDialog(this, "Find:", lastFindText); if (value != null && !value.isEmpty()) { lastFindText = value; findText(false); } }

    private void findText(boolean previous) {
        if (lastFindText == null || lastFindText.isEmpty()) { showFindDialog(); return; }
        String fullText = textArea.getText().toLowerCase();
        String query = lastFindText.toLowerCase();
        int caret = textArea.getCaretPosition();
        int index;
        if (previous) { index = fullText.lastIndexOf(query, Math.max(0, caret - query.length() - 1)); if (index < 0) index = fullText.lastIndexOf(query); }
        else { index = fullText.indexOf(query, caret); if (index < 0) index = fullText.indexOf(query); }
        if (index >= 0) { textArea.requestFocusInWindow(); textArea.select(index, index + lastFindText.length()); }
        else JOptionPane.showMessageDialog(this, "Cannot find \"" + lastFindText + "\"");
    }

    private void showReplaceDialog() {
        JTextField findField = new JTextField(lastFindText, 20);
        JTextField replaceField = new JTextField(20);
        JPanel panel = new JPanel(new GridLayout(2, 2, 8, 8));
        panel.add(new JLabel("Find:")); panel.add(findField); panel.add(new JLabel("Replace with:")); panel.add(replaceField);
        if (JOptionPane.showConfirmDialog(this, panel, "Replace", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
            lastFindText = findField.getText(); if (lastFindText.isEmpty()) return;
            String selected = textArea.getSelectedText();
            if (selected != null && selected.equalsIgnoreCase(lastFindText)) textArea.replaceSelection(replaceField.getText());
            else { findText(false); selected = textArea.getSelectedText(); if (selected != null && selected.equalsIgnoreCase(lastFindText)) textArea.replaceSelection(replaceField.getText()); }
        }
    }

    private void goToLine() {
        String input = JOptionPane.showInputDialog(this, "Line number:"); if (input == null) return;
        try { int line = Integer.parseInt(input.trim()); int max = textArea.getLineCount();
            if (line < 1 || line > max) { JOptionPane.showMessageDialog(this, "Line number must be between 1 and " + max + "."); return; }
            textArea.setCaretPosition(textArea.getLineStartOffset(line - 1)); textArea.requestFocusInWindow();
        } catch (Exception ex) { JOptionPane.showMessageDialog(this, "Invalid line number."); }
    }

    private void insertTimeDate() { textArea.replaceSelection(new SimpleDateFormat("hh:mm a dd/MM/yyyy").format(new Date())); }

    // ---- Case conversion ----
    private void convertCase(int mode) {
        String sel = textArea.getSelectedText();
        if (sel == null || sel.isEmpty()) { JOptionPane.showMessageDialog(this, "Select some text first."); return; }
        String result;
        switch (mode) {
            case 0: result = sel.toUpperCase(); break;
            case 1: result = sel.toLowerCase(); break;
            default: result = toTitleCase(sel);
        }
        textArea.replaceSelection(result);
    }

    private String toTitleCase(String s) {
        StringBuilder sb = new StringBuilder();
        boolean newWord = true;
        for (char c : s.toCharArray()) {
            if (Character.isWhitespace(c)) { newWord = true; sb.append(c); }
            else { sb.append(newWord ? Character.toUpperCase(c) : Character.toLowerCase(c)); newWord = false; }
        }
        return sb.toString();
    }

    // ---- Line operations ----
    private void sortLines() {
        String[] lines = textArea.getText().split("\n", -1);
        List<String> list = new ArrayList<>(Arrays.asList(lines));
        Collections.sort(list);
        textArea.setText(String.join("\n", list));
    }

    private void duplicateLine() {
        try {
            int pos = textArea.getCaretPosition();
            int line = textArea.getLineOfOffset(pos);
            int start = textArea.getLineStartOffset(line);
            int end = textArea.getLineEndOffset(line);
            String lineText = textArea.getText(start, end - start);
            textArea.insert(lineText, end);
        } catch (BadLocationException ignored) {}
    }

    private void moveLine(boolean up) {
        try {
            String[] lines = textArea.getText().split("\n", -1);
            int pos = textArea.getCaretPosition();
            int line = textArea.getLineOfOffset(pos);
            int col = pos - textArea.getLineStartOffset(line);
            int target = up ? line - 1 : line + 1;
            if (target < 0 || target >= lines.length) return;
            String tmp = lines[line]; lines[line] = lines[target]; lines[target] = tmp;
            textArea.setText(String.join("\n", lines));
            int newPos = textArea.getLineStartOffset(target) + Math.min(col, lines[target].length());
            textArea.setCaretPosition(newPos);
        } catch (BadLocationException ignored) {}
    }

    private void chooseFont() {
        String[] fonts = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        JComboBox<String> fontBox = new JComboBox<>(fonts); fontBox.setSelectedItem(textArea.getFont().getFamily());
        JSpinner sizeSpinner = new JSpinner(new SpinnerNumberModel(textArea.getFont().getSize(), 8, 72, 1));
        JPanel panel = new JPanel(new GridLayout(2, 2, 8, 8));
        panel.add(new JLabel("Font:")); panel.add(fontBox); panel.add(new JLabel("Size:")); panel.add(sizeSpinner);
        if (JOptionPane.showConfirmDialog(this, panel, "Font", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
            Font newFont = new Font((String) fontBox.getSelectedItem(), Font.PLAIN, (int) sizeSpinner.getValue());
            textArea.setFont(newFont); fontSize = newFont.getSize();
            lineNumberGutter.setFont(newFont); lineNumberGutter.revalidate(); lineNumberGutter.repaint();
        }
    }

    private void setEditorFontSize(int size) {
        if (size < 8 || size > 72) return;
        fontSize = size;
        Font current = textArea.getFont();
        Font newFont = new Font(current.getFamily(), current.getStyle(), fontSize);
        textArea.setFont(newFont);
        lineNumberGutter.setFont(newFont);
        lineNumberGutter.revalidate();
        lineNumberGutter.repaint();
    }

    private void searchWithBing() {
        String query = textArea.getSelectedText();
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

    /** Row-header gutter that paints line numbers next to the text area. */
    private class LineNumberGutter extends JComponent {
        LineNumberGutter() { setFont(textArea.getFont()); }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(textArea.getFont());
            int digits = Math.max(2, String.valueOf(Math.max(1, textArea.getLineCount())).length());
            int width = fm.stringWidth("0") * digits + 16;
            return new Dimension(width, textArea.getHeight());
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Color bg = darkMode ? DARK_STATUSBAR_BG : LIGHT_STATUSBAR_BG;
            Color fg = darkMode ? new Color(150, 150, 150) : new Color(120, 120, 120);
            g.setColor(bg);
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setColor(fg);
            g.setFont(textArea.getFont());
            FontMetrics fm = g.getFontMetrics();
            Rectangle clip = g.getClipBounds();
            try {
                int startOffset = textArea.viewToModel2D(new Point(0, Math.max(0, clip.y)));
                int endOffset = textArea.viewToModel2D(new Point(0, clip.y + clip.height));
                while (startOffset <= endOffset) {
                    int lineNumber = textArea.getLineOfOffset(startOffset);
                    Rectangle2D r = textArea.modelToView2D(startOffset);
                    if (r == null) break;
                    int y = (int) r.getY() + fm.getAscent();
                    String text = String.valueOf(lineNumber + 1);
                    int x = getWidth() - fm.stringWidth(text) - 8;
                    g.drawString(text, x, y);
                    int nextOffset = Utilities.getRowEnd(textArea, startOffset) + 1;
                    if (nextOffset <= startOffset) break;
                    startOffset = nextOffset;
                }
            } catch (BadLocationException ignored) {}
        }
    }
}
