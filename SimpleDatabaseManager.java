import javax.swing.*;
import javax.swing.event.ListSelectionEvent;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.io.File;
import java.io.FileInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.List;

public class SimpleDatabaseManager extends JFrame {
    private static final Path LOG_PATH = resolveLogPath();

    private static Path resolveLogPath() {
        // A jpackage launcher starts from SimpleDatabaseManager.exe; a BAT launcher uses user.dir.
        String command = ProcessHandle.current().info().command().orElse("");
        try {
            Path executable = Path.of(command).toAbsolutePath().normalize();
            if (executable.getFileName() != null
                    && executable.getFileName().toString().equalsIgnoreCase("SimpleDatabaseManager.exe")) {
                return executable.getParent().resolve("SimpleDatabaseManager.log");
            }
        } catch (RuntimeException ignored) { }
        return Path.of(System.getProperty("user.dir"), "SimpleDatabaseManager.log").toAbsolutePath().normalize();
    }
    private static final Object LOG_LOCK = new Object();
    private Connection connection;
    private final DefaultListModel<TableRef> tablesModel = new DefaultListModel<>();
    private final JList<TableRef> tables = new JList<>(tablesModel);
    private final JTable dataTable = new JTable();
    private final JLabel status = new JLabel(" ");
    private TableRef currentTable;
    private DataModel dataModel;

    public static void main(String[] args) {
        installGlobalErrorLogging();
        logInfo("Application started. Java=" + System.getProperty("java.version") + ", OS=" + System.getProperty("os.name") + " " + System.getProperty("os.version"));
        SwingUtilities.invokeLater(() -> {
            UIManager.put("OptionPane.yesButtonText", "Ano");
            UIManager.put("OptionPane.noButtonText", "Ne");
            UIManager.put("OptionPane.cancelButtonText", "Zrušit");
            File db = chooseDatabase();
            if (db == null) { logInfo("Database selection cancelled. Application is closing."); System.exit(0); }
            logInfo("Selected database: " + db.getAbsolutePath() + ", size=" + db.length() + " bytes");
            try {
                validateSqliteFile(db);
                SimpleDatabaseManager app = new SimpleDatabaseManager(db);
                app.setVisible(true);
            } catch (Exception ex) {
                showError(null, "Databázi se nepodařilo otevřít.", ex);
                System.exit(1);
            }
        });
    }

    private static File chooseDatabase() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Vyberte databázi SQLite");
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setAcceptAllFileFilterUsed(true);
        chooser.addChoosableFileFilter(new FileNameExtensionFilter("SQLite (*.db, *.sqlite, *.sqlite3)", "db", "sqlite", "sqlite3"));
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return null;
        File selected = chooser.getSelectedFile();
        if (!selected.isFile()) {
            JOptionPane.showMessageDialog(null, "Vyberte existující databázový soubor.", "Neplatná databáze", JOptionPane.ERROR_MESSAGE);
            return null;
        }
        return selected;
    }

    private static void validateSqliteFile(File file) throws Exception {
        byte[] header = new byte[16];
        try (FileInputStream in = new FileInputStream(file)) {
            if (in.read(header) != header.length ||
                    !"SQLite format 3\0".equals(new String(header, java.nio.charset.StandardCharsets.US_ASCII))) {
                throw new SQLException("Soubor není platná SQLite 3 databáze: " + file.getName());
            }
        }
        logInfo("SQLite 3 file header verified: " + file.getAbsolutePath());
    }

    private SimpleDatabaseManager(File dbFile) throws Exception {
        logInfo("Opening SQLite database: " + dbFile.getAbsolutePath());
        Class.forName("org.sqlite.JDBC");
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA foreign_keys=ON");
            try (ResultSet result = st.executeQuery("PRAGMA quick_check")) {
                if (result.next() && !"ok".equalsIgnoreCase(result.getString(1)))
                    throw new SQLException("SQLite kontrola integrity selhala: " + result.getString(1));
            }
        }
        setTitle("Simple Database Manager - " + dbFile.getName() + " [SQLite]");
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setSize(1100, 700);
        setLocationRelativeTo(null);
        addWindowListener(new java.awt.event.WindowAdapter() {
            public void windowClosed(java.awt.event.WindowEvent e) {
                try { if (connection != null) connection.close(); } catch (Exception ex) { logError("Database close failed", ex); }
            }
        });
        buildUi();
        reloadTables(null);
    }


    private void buildUi() {
        tables.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        tables.addListSelectionListener(this::tableSelected);

        JButton addTable = new JButton("Přidat tabulku");
        JButton renameTable = new JButton("Přejmenovat");
        JButton dropTable = new JButton("Odstranit");
        addTable.addActionListener(e -> addTable());
        renameTable.addActionListener(e -> renameTable());
        dropTable.addActionListener(e -> dropTable());
        JPanel tableButtons = new JPanel(new GridLayout(0, 1, 5, 5));
        tableButtons.add(addTable); tableButtons.add(renameTable); tableButtons.add(dropTable);

        JPanel left = new JPanel(new BorderLayout(5, 5));
        left.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 4));
        left.add(new JLabel("Tabulky"), BorderLayout.NORTH);
        left.add(new JScrollPane(tables), BorderLayout.CENTER);
        left.add(tableButtons, BorderLayout.SOUTH);

        dataTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        dataTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JButton addRow = new JButton("Přidat záznam");
        JButton editRow = new JButton("Upravit záznam");
        JButton deleteRow = new JButton("Odstranit záznam");
        JButton refresh = new JButton("Obnovit");
        addRow.addActionListener(e -> addRow());
        editRow.addActionListener(e -> editRow());
        deleteRow.addActionListener(e -> deleteRow());
        refresh.addActionListener(e -> reloadData());
        JPanel recordButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        recordButtons.add(addRow); recordButtons.add(editRow); recordButtons.add(deleteRow); recordButtons.add(refresh);

        JPanel right = new JPanel(new BorderLayout(5, 5));
        right.setBorder(BorderFactory.createEmptyBorder(8, 4, 8, 8));
        right.add(recordButtons, BorderLayout.NORTH);
        right.add(new JScrollPane(dataTable), BorderLayout.CENTER);
        status.setBorder(BorderFactory.createEmptyBorder(4, 4, 0, 4));
        right.add(status, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setDividerLocation(230);
        add(split);
    }

    private void tableSelected(ListSelectionEvent e) {
        if (!e.getValueIsAdjusting()) { currentTable = tables.getSelectedValue(); reloadData(); }
    }

    private void reloadTables(String selectName) {
        try {
            tablesModel.clear();
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")) {
                while (rs.next()) tablesModel.addElement(new TableRef(null, rs.getString(1)));
            }
            for (int i = 0; i < tablesModel.size(); i++) {
                if (selectName != null && tablesModel.get(i).name.equalsIgnoreCase(selectName)) { tables.setSelectedIndex(i); return; }
            }
            if (!tablesModel.isEmpty()) tables.setSelectedIndex(0);
            else { currentTable = null; dataModel = null; reloadData(); status.setText("V databázi nebyly nalezeny žádné uživatelské tabulky."); }
        } catch (SQLException ex) { showError(this, "Seznam tabulek nelze načíst.", ex); }
    }

    private void reloadData() {
        if (currentTable == null) { dataTable.setModel(new javax.swing.table.DefaultTableModel()); status.setText(" "); return; }
        try {
            dataModel = DataModel.load(connection, currentTable);
            dataTable.setModel(dataModel);
            for (int i = 0; i < dataTable.getColumnCount(); i++) dataTable.getColumnModel().getColumn(i).setPreferredWidth(140);
            status.setText("Tabulka: " + currentTable + " | Záznamů: " + dataModel.rows.size());
        } catch (SQLException ex) { showError(this, "Data nelze načíst.", ex); }
    }

    private void addTable() {
        JTextField name = new JTextField(24);
        JTextArea columns = new JTextArea("id INTEGER PRIMARY KEY AUTOINCREMENT,\nname TEXT", 8, 42);
        JPanel p = new JPanel(new BorderLayout(5, 5));
        JPanel top = new JPanel(new BorderLayout(5, 5)); top.add(new JLabel("Název tabulky:"), BorderLayout.WEST); top.add(name);
        p.add(top, BorderLayout.NORTH); p.add(new JScrollPane(columns), BorderLayout.CENTER);
        p.add(new JLabel("Definice sloupců v syntaxi SQLite, oddělené čárkou."), BorderLayout.SOUTH);
        if (JOptionPane.showConfirmDialog(this, p, "Přidat tabulku", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        String n = name.getText().trim(); if (n.isEmpty() || columns.getText().isBlank()) return;
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("CREATE TABLE " + q(n) + " (" + columns.getText() + ")");
            reloadTables(n);
        } catch (SQLException ex) { showError(this, "Tabulku nelze vytvořit.", ex); }
    }

    private void renameTable() {
        if (currentTable == null) return;
        String n = JOptionPane.showInputDialog(this, "Nový název tabulky:", currentTable.name);
        if (n == null || n.isBlank() || n.equals(currentTable.name)) return;
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("ALTER TABLE " + currentTable.sqlName() + " RENAME TO " + q(n.trim()));
            reloadTables(n.trim());
        } catch (SQLException ex) { showError(this, "Tabulku nelze přejmenovat.", ex); }
    }

    private void dropTable() {
        if (currentTable == null) return;
        if (JOptionPane.showConfirmDialog(this, "Opravdu odstranit tabulku " + currentTable + " včetně všech dat?", "Potvrzení", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("DROP TABLE " + currentTable.sqlName()); currentTable = null; reloadTables(null);
        } catch (SQLException ex) { showError(this, "Tabulku nelze odstranit.", ex); }
    }

    private void addRow() {
        if (dataModel == null) return;
        RowDialog dlg = new RowDialog(this, "Přidat záznam", dataModel.columns, null);
        if (!dlg.ok) return;
        List<ColumnInfo> used = new ArrayList<>(); List<Object> values = new ArrayList<>();
        for (int i = 0; i < dataModel.columns.size(); i++) {
            ColumnInfo c = dataModel.columns.get(i); String text = dlg.fields.get(i).getText();
            if (c.autoIncrement && text.isBlank()) continue;
            used.add(c); values.add(convert(text, c.type));
        }
        String cols = String.join(",", used.stream().map(c -> q(c.name)).toList());
        String qs = String.join(",", Collections.nCopies(used.size(), "?"));
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO " + currentTable.sqlName() + " (" + cols + ") VALUES (" + qs + ")")) {
            bind(ps, values); ps.executeUpdate(); reloadData();
        } catch (Exception ex) { showError(this, "Záznam nelze přidat.", ex); }
    }

    private void editRow() {
        int row = dataTable.getSelectedRow(); if (row < 0 || dataModel == null) { JOptionPane.showMessageDialog(this, "Vyberte záznam."); return; }
        List<Object> original = dataModel.rows.get(dataTable.convertRowIndexToModel(row));
        RowDialog dlg = new RowDialog(this, "Upravit záznam", dataModel.columns, original); if (!dlg.ok) return;
        List<Object> updated = new ArrayList<>(); for (int i = 0; i < dataModel.columns.size(); i++) updated.add(convert(dlg.fields.get(i).getText(), dataModel.columns.get(i).type));
        try {
            WhereClause where = dataModel.whereFor(original);
            String set = String.join(",", dataModel.columns.stream().map(c -> q(c.name) + "=?").toList());
            try (PreparedStatement ps = connection.prepareStatement("UPDATE " + currentTable.sqlName() + " SET " + set + " WHERE " + where.sql)) {
                List<Object> all = new ArrayList<>(updated); all.addAll(where.values); bind(ps, all);
                int count = ps.executeUpdate(); if (count == 0) throw new SQLException("Záznam nebyl nalezen. Obnovte data.");
            }
            reloadData();
        } catch (Exception ex) { showError(this, "Záznam nelze upravit.", ex); }
    }

    private void deleteRow() {
        int row = dataTable.getSelectedRow(); if (row < 0 || dataModel == null) { JOptionPane.showMessageDialog(this, "Vyberte záznam."); return; }
        if (JOptionPane.showConfirmDialog(this, "Opravdu odstranit vybraný záznam?", "Potvrzení", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        List<Object> original = dataModel.rows.get(dataTable.convertRowIndexToModel(row));
        try {
            WhereClause where = dataModel.whereFor(original);
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM " + currentTable.sqlName() + " WHERE " + where.sql)) { bind(ps, where.values); ps.executeUpdate(); }
            reloadData();
        } catch (Exception ex) { showError(this, "Záznam nelze odstranit.", ex); }
    }

    private static Object convert(String text, int type) {
        if (text == null || text.isBlank()) return null;
        return switch (type) {
            case Types.INTEGER, Types.SMALLINT, Types.TINYINT -> Integer.valueOf(text);
            case Types.BIGINT -> Long.valueOf(text);
            case Types.DECIMAL, Types.NUMERIC -> new BigDecimal(text.replace(',', '.'));
            case Types.DOUBLE, Types.FLOAT, Types.REAL -> Double.valueOf(text.replace(',', '.'));
            case Types.BOOLEAN, Types.BIT -> Boolean.valueOf(text);
            case Types.DATE -> java.sql.Date.valueOf(text);
            case Types.TIME, Types.TIME_WITH_TIMEZONE -> Time.valueOf(text);
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> Timestamp.valueOf(text);
            default -> text;
        };
    }

    private static void bind(PreparedStatement ps, List<Object> values) throws SQLException { for (int i = 0; i < values.size(); i++) ps.setObject(i + 1, values.get(i)); }
    private static String q(String s) { return "\"" + s.replace("\"", "\"\"") + "\""; }
    private static void showError(Component p, String message, Exception ex) {
        logError(message, ex);
        String detail = ex == null || ex.getMessage() == null ? "Neznámá chyba" : ex.getMessage();
        JOptionPane.showMessageDialog(p, message + "\n\n" + detail + "\n\nPodrobnosti byly uloženy do:\n" + LOG_PATH,
                "Chyba", JOptionPane.ERROR_MESSAGE);
    }

    private static void installGlobalErrorLogging() {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> logError("Neočekávaná chyba ve vlákně " + thread.getName(), error));
        System.setProperty("sun.awt.exception.handler", AwtExceptionHandler.class.getName());
    }

    public static class AwtExceptionHandler {
        public void handle(Throwable error) { logError("Neočekávaná chyba v uživatelském rozhraní", error); }
    }

    private static void logInfo(String message) { writeLog("INFO", message, null); }
    private static void logError(String message, Throwable error) { writeLog("ERROR", message, error); }

    private static void writeLog(String level, String message, Throwable error) {
        synchronized (LOG_LOCK) {
            try {
                Files.createDirectories(LOG_PATH.getParent());
                StringBuilder entry = new StringBuilder()
                        .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")))
                        .append(" [").append(level).append("] ").append(message).append(System.lineSeparator());
                if (error != null) {
                    StringWriter stack = new StringWriter();
                    error.printStackTrace(new PrintWriter(stack));
                    entry.append(stack).append(System.lineSeparator());
                    if (error instanceof SQLException sql) {
                        entry.append("SQLState=").append(sql.getSQLState())
                                .append(", ErrorCode=").append(sql.getErrorCode()).append(System.lineSeparator());
                    }
                }
                Files.writeString(LOG_PATH, entry, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (Exception loggingError) {
                loggingError.printStackTrace(System.err);
            }
        }
    }

    record TableRef(String schema, String name) {
        String sqlName() { return schema == null || schema.isBlank() ? q(name) : q(schema) + "." + q(name); }
        public String toString() { return schema == null || schema.isBlank() ? name : schema + "." + name; }
    }
    record ColumnInfo(String name, int type, String typeName, boolean nullable, boolean autoIncrement, boolean primaryKey) {}
    record WhereClause(String sql, List<Object> values) {}

    static class DataModel extends AbstractTableModel {
        final TableRef table; final List<ColumnInfo> columns; final List<List<Object>> rows;
        DataModel(TableRef t, List<ColumnInfo> c, List<List<Object>> r) { table=t; columns=c; rows=r; }
        static DataModel load(Connection c, TableRef t) throws SQLException {
            Set<String> pks = new HashSet<>(); DatabaseMetaData md = c.getMetaData();
            try (ResultSet rs = md.getPrimaryKeys(null, t.schema, t.name)) { while (rs.next()) pks.add(rs.getString("COLUMN_NAME")); }
            List<ColumnInfo> cols = new ArrayList<>();
            try (ResultSet rs = md.getColumns(null, t.schema, t.name, "%")) {
                while (rs.next()) cols.add(new ColumnInfo(rs.getString("COLUMN_NAME"), rs.getInt("DATA_TYPE"), rs.getString("TYPE_NAME"), rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls, "YES".equalsIgnoreCase(rs.getString("IS_AUTOINCREMENT")), pks.contains(rs.getString("COLUMN_NAME"))));
            }
            List<List<Object>> rows = new ArrayList<>();
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM " + t.sqlName())) {
                while (rs.next()) { List<Object> row = new ArrayList<>(); for (int i=1;i<=cols.size();i++) row.add(rs.getObject(i)); rows.add(row); }
            }
            return new DataModel(t, cols, rows);
        }
        WhereClause whereFor(List<Object> row) {
            List<Integer> indexes = new ArrayList<>(); for (int i=0;i<columns.size();i++) if (columns.get(i).primaryKey) indexes.add(i);
            if (indexes.isEmpty()) for (int i=0;i<columns.size();i++) indexes.add(i);
            List<String> parts = new ArrayList<>(); List<Object> vals = new ArrayList<>();
            for (int i : indexes) { Object v=row.get(i); if(v==null) parts.add(q(columns.get(i).name)+" IS NULL"); else {parts.add(q(columns.get(i).name)+"=?"); vals.add(v);} }
            return new WhereClause(String.join(" AND ", parts), vals);
        }
        public int getRowCount(){return rows.size();} public int getColumnCount(){return columns.size();}
        public String getColumnName(int i){ColumnInfo c=columns.get(i); return c.name + (c.primaryKey?" [PK]":"") + " ("+c.typeName+")";}
        public Object getValueAt(int r,int c){return rows.get(r).get(c);} public boolean isCellEditable(int r,int c){return false;}
    }

    static class RowDialog extends JDialog {
        boolean ok; final List<JTextField> fields = new ArrayList<>();
        RowDialog(Frame owner, String title, List<ColumnInfo> columns, List<Object> values) {
            super(owner, title, true); JPanel form = new JPanel(new GridBagLayout()); GridBagConstraints g=new GridBagConstraints(); g.insets=new Insets(3,3,3,3); g.fill=GridBagConstraints.HORIZONTAL;
            for(int i=0;i<columns.size();i++){ColumnInfo c=columns.get(i); g.gridx=0;g.gridy=i;g.weightx=0;form.add(new JLabel(c.name+" ("+c.typeName+")"+(c.autoIncrement?" [AUTO]":"")),g); JTextField f=new JTextField(28); if(values!=null&&values.get(i)!=null)f.setText(String.valueOf(values.get(i))); fields.add(f);g.gridx=1;g.weightx=1;form.add(f,g);}
            JButton save=new JButton("Uložit"), cancel=new JButton("Zrušit"); save.addActionListener(e->{ok=true;dispose();}); cancel.addActionListener(e->dispose()); JPanel buttons=new JPanel();buttons.add(save);buttons.add(cancel);
            add(new JScrollPane(form),BorderLayout.CENTER);add(buttons,BorderLayout.SOUTH);setSize(650,Math.min(600,140+columns.size()*42));setLocationRelativeTo(owner);setVisible(true);
        }
    }
}
