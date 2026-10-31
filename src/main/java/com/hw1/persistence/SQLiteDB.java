package com.hw1.persistence;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import javassist.util.proxy.MethodHandler;
import javassist.util.proxy.ProxyFactory;

public class SQLiteDB {
    private Connection connection;
    private SQLiteDBHelper dbHelper;

    public SQLiteDB(String dbPath) throws SQLException {
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        this.dbHelper = new SQLiteDBHelper(this.connection);
    }

    public void createTable(Class<?> clazz) throws SQLException {

        StringBuilder sql = new StringBuilder("CREATE TABLE IF NOT EXISTS ");
        sql.append(clazz.getSimpleName()).append(" (");

        Field[] fields = clazz.getDeclaredFields();
        List<Field> persistableFields = new ArrayList<>();

        // Only include fields marked with @Persistable annotation
        for (Field field : fields) {
            if (field.isAnnotationPresent(Persistable.class)) {
                persistableFields.add(field);
            }
        }

        for (int i = 0; i < persistableFields.size(); i++) {
            Field field = persistableFields.get(i);
            String fieldName = dbHelper.camelToSnakeCase(field.getName());
            String sqlType = dbHelper.getSQLType(field.getType());

            sql.append(fieldName).append(" ").append(sqlType);

            // Check if field is marked as PrimaryKey
            if (field.isAnnotationPresent(PrimaryKey.class)) {
                sql.append(" PRIMARY KEY");
            }

            if (i < persistableFields.size() - 1) {
                sql.append(", ");
            }
        }

        sql.append(")");

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql.toString());
        }
    }

    public void droptTable(Class<?> clazz) throws SQLException {
        String sql = "DROP TABLE IF EXISTS " + clazz.getSimpleName();

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        }
    }

    /**
     * Insert the given object into the database using reflection.
     * Only fields annotated with @Persistable are stored.
     */
    public void insertRow(Object obj) throws SQLException, IllegalAccessException {
        Class<?> cls = obj.getClass();
        List<String> columnNames = new ArrayList<>();
        List<Object> values = new ArrayList<>();

        for (Field field : cls.getDeclaredFields()) {
            if (field.isAnnotationPresent(Persistable.class)) {
                field.setAccessible(true);
                columnNames.add(dbHelper.camelToSnakeCase(field.getName()));
                values.add(field.get(obj));
            }
        }

        StringBuilder sql = new StringBuilder("INSERT INTO ");
        sql.append(cls.getSimpleName()).append(" (");
        sql.append(String.join(", ", columnNames));
        sql.append(") VALUES (");
        sql.append("?,".repeat(values.size()));
        sql.setLength(sql.length() - 1); // remove the trailing comma
        sql.append(")");

        try (PreparedStatement pstmt = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < values.size(); i++) {
                pstmt.setObject(i + 1, values.get(i));
            }
            pstmt.executeUpdate();
        }
    }

    /**
     * Load a row using the primary key already set on obj. If the class has
     * any @RemoteLazyLoad fields, a Javassist proxy is returned instead of obj.
     */
    @SuppressWarnings("unchecked")
    public <T> T loadRow(T obj) throws SQLException, IllegalAccessException {
        Class<?> cls = obj.getClass();
        Field primaryKeyField = null;
        List<Field> persistableFields = new ArrayList<>();
        List<Field> lazyFields = new ArrayList<>();

        for (Field field : cls.getDeclaredFields()) {
            if (!field.isAnnotationPresent(Persistable.class)) continue;
            field.setAccessible(true);
            persistableFields.add(field);
            if (field.isAnnotationPresent(PrimaryKey.class)) primaryKeyField = field;
            if (field.isAnnotationPresent(RemoteLazyLoad.class)) lazyFields.add(field);
        }

        if (primaryKeyField == null) {
            throw new IllegalArgumentException("No primary key field found in class " + cls.getName());
        }

        String sql = "SELECT * FROM " + cls.getSimpleName() + " WHERE "
                + dbHelper.camelToSnakeCase(primaryKeyField.getName()) + " = ?";

        // The object we fill in: obj itself, or a proxy subclass if there are lazy fields
        T target = obj;
        if (!lazyFields.isEmpty()) {
            ProxyFactory factory = new ProxyFactory();
            factory.setSuperclass(cls);

            MethodHandler handler = (self, method, proceed, args) -> {
                for (Field lazyField : lazyFields) {
                    String name = lazyField.getName();
                    String getter = "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
                    if (method.getName().equals(getter)) {
                        byte[] value = (byte[]) lazyField.get(self);
                        if (value == null) return null;
                        String text = new String(value, StandardCharsets.UTF_8);
                        if (text.startsWith("http://") || text.startsWith("https://")) {
    value = fetchRemote(text);
    lazyField.set(self, value); // cache so we fetch only once
}
                            lazyField.set(self, value); // cache so we fetch only once
                        }
                        return value;
                    }
                }
                return proceed.invoke(self, args);
            };

            try {
                target = (T) factory.create(new Class<?>[0], new Object[0], handler);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException("Could not create proxy for " + cls.getName(), e);
            }
        }

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setObject(1, primaryKeyField.get(obj));
            try (ResultSet rs = pstmt.executeQuery()) {
                if (!rs.next()) {
                    System.err.println("No row found for primary key " + primaryKeyField.get(obj));
                    System.exit(1);
                }
                for (Field field : persistableFields) {
                    String col = dbHelper.camelToSnakeCase(field.getName());
                    Class<?> type = field.getType();
                    if (type == String.class) {
                        field.set(target, rs.getString(col));
                    } else if (type == int.class) {
                        field.setInt(target, rs.getInt(col));
                    } else if (type == byte[].class) {
                        field.set(target, rs.getBytes(col));
                    }
                }
            }
        }
        return target;
    }

private byte[] fetchRemote(String url) throws Exception {
    // Wikipedia "#/media/File:Name.jpg" links point at an article page, not the image.
    // Rewrite them to the direct file address, which redirects to the image.
    int frag = url.indexOf("#/media/File:");
    int wiki = url.indexOf("/wiki/");
    if (frag >= 0 && wiki >= 0) {
        String fileName = url.substring(frag + "#/media/File:".length());
        url = url.substring(0, wiki) + "/wiki/Special:FilePath/" + fileName;
    }
    java.net.HttpURLConnection conn =
            (java.net.HttpURLConnection) new URL(url).openConnection();
    conn.setRequestProperty("User-Agent", "ECS160-HW1/1.0 (student project)");
    conn.setInstanceFollowRedirects(true);
    try (InputStream in = conn.getInputStream()) {
        return in.readAllBytes();
    }
}

    public Connection getConnection() {
        return connection;
    }

    public void close() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }
}