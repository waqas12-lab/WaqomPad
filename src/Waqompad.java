import javax.swing.*;
import javax.swing.event.*;
import javax.swing.undo.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.print.PrinterException;
import java.io.File;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;

public class Waqompad extends JFrame {
    private final JTextArea textArea = new JTextArea();
    private final JLabel statusBar = new JLabel("Ln 1, Col 1");
    private final UndoManager undoManager = new UndoManager();
    private File currentFile = null;
    private boolean isModified = false;
    private String lastFindText = "";
    private int fontSize = 18;

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
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setSize(1100, 720);
        setLocationRelativeTo(null);
        setIconImage(loadIcon());

        textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, fontSize));
        textArea.setBackground(Color.WHITE);
        textArea.setForeground(Color.BLACK);
        textArea.setCaretColor(Color.BLACK);
        textArea.setMargin(new Insets(4, 6, 4, 6));
        textArea.setLineWrap(false);
        textArea.setWrapStyleWord(false);

        add(new JScrollPane(textArea), BorderLayout.CENTER);
        statusBar.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        add(statusBar, BorderLayout.SOUTH);
        setJMenuBar(createMenuBar());

        textArea.getDocument().addUndoableEditListener(e -> undoManager.addEdit(e.getEdit()));
        textArea.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { markModified(); }
            public void removeUpdate(DocumentEvent e) { markModified(); }
            public void changedUpdate(DocumentEvent e) { markModified(); }
        });
        textArea.addCaretListener(e -> updateStatusBar());
        addWindowListener(new WindowAdapter() { public void windowClosing(WindowEvent e) { exitApp(); } });
        isModified = false;
        updateTitle();
        updateStatusBar();
    }

    private JMenuBar createMenuBar() {
        JMenuBar menuBar = new JMenuBar();
        JMenu file = new JMenu("File");
        file.add(createItem("New", KeyEvent.VK_N, 0, e -> newFile()));
        file.add(createItem("New Window", KeyEvent.VK_N, InputEvent.SHIFT_DOWN_MASK, e -> new Waqompad().setVisible(true)));
        file.add(createItem("Open", KeyEvent.VK_O, 0, e -> openFile()));
        file.add(createItem("Save", KeyEvent.VK_S, 0, e -> saveFile()));
        file.add(createItem("Save As", KeyEvent.VK_S, InputEvent.SHIFT_DOWN_MASK, e -> saveAsFile()));
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

        JMenu help = new JMenu("Help");
        help.add(new JMenuItem(new AbstractAction("View Help") { public void actionPerformed(ActionEvent e) { JOptionPane.showMessageDialog(Waqompad.this, "WaqomPad Help\n\nUse File, Edit, Format, View and Help menus just like classic Notepad."); }}));
        help.add(new JMenuItem(new AbstractAction("Send Feedback") { public void actionPerformed(ActionEvent e) { JOptionPane.showMessageDialog(Waqompad.this, "Feedback is offline in this version. Share feedback manually."); }}));
        help.add(new JMenuItem(new AbstractAction("About WaqomPad") { public void actionPerformed(ActionEvent e) { JOptionPane.showMessageDialog(Waqompad.this, "WaqomPad\nProfessional Java Swing Notepad\nNative cross-platform desktop text editor.", "About WaqomPad", JOptionPane.INFORMATION_MESSAGE); }}));

        menuBar.add(file); menuBar.add(edit); menuBar.add(format); menuBar.add(view); menuBar.add(help);
        return menuBar;
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
            statusBar.setText("Ln " + (line + 1) + ", Col " + (col + 1));
        } catch (Exception ignored) {}
    }

    private boolean confirmSaveChanges() {
        if (!isModified) return true;
        int choice = JOptionPane.showConfirmDialog(this, "Do you want to save changes?", "WaqomPad", JOptionPane.YES_NO_CANCEL_OPTION);
        if (choice == JOptionPane.CANCEL_OPTION || choice == JOptionPane.CLOSED_OPTION) return false;
        return choice != JOptionPane.YES_OPTION || saveFile();
    }

    private void newFile() { if (!confirmSaveChanges()) return; textArea.setText(""); currentFile = null; isModified = false; undoManager.discardAllEdits(); updateTitle(); updateStatusBar(); }

    private void openFile() {
        if (!confirmSaveChanges()) return;
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                currentFile = chooser.getSelectedFile();
                textArea.setText(Files.readString(currentFile.toPath(), StandardCharsets.UTF_8));
                textArea.setCaretPosition(0); isModified = false; undoManager.discardAllEdits(); updateTitle(); updateStatusBar();
            } catch (Exception ex) { JOptionPane.showMessageDialog(this, "Could not open file:\n" + ex.getMessage()); }
        }
    }

    private boolean saveFile() {
        if (currentFile == null) return saveAsFile();
        try { Files.writeString(currentFile.toPath(), textArea.getText(), StandardCharsets.UTF_8); isModified = false; updateTitle(); return true; }
        catch (Exception ex) { JOptionPane.showMessageDialog(this, "Could not save file:\n" + ex.getMessage()); return false; }
    }

    private boolean saveAsFile() {
        JFileChooser chooser = new JFileChooser();
        if (currentFile != null) chooser.setSelectedFile(currentFile);
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) { currentFile = chooser.getSelectedFile(); return saveFile(); }
        return false;
    }

    private void printFile() { try { textArea.print(); } catch (PrinterException ex) { JOptionPane.showMessageDialog(this, "Print failed:\n" + ex.getMessage()); } }
    private void exitApp() { if (confirmSaveChanges()) dispose(); }

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

    private void chooseFont() {
        String[] fonts = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        JComboBox<String> fontBox = new JComboBox<>(fonts); fontBox.setSelectedItem(textArea.getFont().getFamily());
        JSpinner sizeSpinner = new JSpinner(new SpinnerNumberModel(textArea.getFont().getSize(), 8, 72, 1));
        JPanel panel = new JPanel(new GridLayout(2, 2, 8, 8));
        panel.add(new JLabel("Font:")); panel.add(fontBox); panel.add(new JLabel("Size:")); panel.add(sizeSpinner);
        if (JOptionPane.showConfirmDialog(this, panel, "Font", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
            Font newFont = new Font((String) fontBox.getSelectedItem(), Font.PLAIN, (int) sizeSpinner.getValue());
            textArea.setFont(newFont); fontSize = newFont.getSize();
        }
    }

    private void setEditorFontSize(int size) { if (size < 8 || size > 72) return; fontSize = size; Font current = textArea.getFont(); textArea.setFont(new Font(current.getFamily(), current.getStyle(), fontSize)); }

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
}
