package com.orm;

import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import android.text.TextUtils;
import android.util.Log;

import com.orm.dsl.Column;
import com.orm.dsl.NotNull;
import com.orm.dsl.Unique;

import dalvik.system.DexFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.*;

import static com.orm.SugarConfig.getDatabaseVersion;
import static com.orm.SugarConfig.getDebugEnabled;

public class SugarDb extends SQLiteOpenHelper {
    private Context context;

    public SugarDb(Context context) {
        super(context, SugarConfig.getDatabaseName(context), new SugarCursorFactory(getDebugEnabled(context)), getDatabaseVersion(context));
        this.context = context;

    }

    private <T extends SugarRecord<?>> List<T> getDomainClasses(Context context) {
        List<T> domainClasses = new ArrayList<T>();
        try {
            Enumeration<?> allClasses = getAllClasses(context);

            while (allClasses.hasMoreElements()) {
                String className = (String) allClasses.nextElement();

                if (className.startsWith(SugarConfig.getDomainPackageName(context))) {
                    T domainClass = getDomainClass(className, context);
                    if (domainClass != null) domainClasses.add(domainClass);
                }
            }

        } catch (IOException e) {
            Log.e("Sugar", e.getMessage());
        } catch (PackageManager.NameNotFoundException e) {
            Log.e("Sugar", e.getMessage());
        }

        return domainClasses;
    }

    @SuppressWarnings("unchecked")
    private <T extends SugarRecord<?>> T getDomainClass(String className, Context context) {
        Log.i("Sugar", "domain class");
        Class<?> discoveredClass = null;
        try {
            discoveredClass = Class.forName(className, true, context.getClass().getClassLoader());
        } catch (ClassNotFoundException e) {
            Log.e("Sugar", e.getMessage());
        }

        if ((discoveredClass == null) ||
                (!SugarRecord.class.isAssignableFrom(discoveredClass)) ||
                Modifier.isAbstract(discoveredClass.getModifiers())) {
            return null;
        } else {
            try {
                return (T) discoveredClass.getDeclaredConstructor().newInstance();
            } catch (InstantiationException e) {
                Log.e("Sugar", e.getMessage());
            } catch (IllegalAccessException e) {
                Log.e("Sugar", e.getMessage());
            } catch (NoSuchMethodException e) {
                Log.e("Sugar", e.getMessage());
            } catch (InvocationTargetException e) {
                Log.e("Sugar", e.getMessage());
            }
        }

        return null;

    }

    private Enumeration<?> getAllClasses(Context context) throws PackageManager.NameNotFoundException, IOException {
        String path = getSourcePath(context);
        DexFile dexfile = new DexFile(path);
        return dexfile.entries();
    }

    private String getSourcePath(Context context) throws PackageManager.NameNotFoundException {
        return context.getPackageManager().getApplicationInfo(context.getPackageName(), 0).sourceDir;
    }

    @Override
    public void onCreate(SQLiteDatabase sqLiteDatabase) {
        Log.i("Sugar", "on create");
        createDatabase(sqLiteDatabase);
    }

    private <T extends SugarRecord<?>> void createDatabase(SQLiteDatabase sqLiteDatabase) {
        List<T> domainClasses = getDomainClasses(context);
        for (T domain : domainClasses) {
            createTable(domain, sqLiteDatabase);
        }
    }

    private <T extends SugarRecord<?>> void createTable(T table, SQLiteDatabase sqLiteDatabase) {
        Log.i("Sugar", "create table");
        List<Field> fields = table.getTableFields();
        StringBuilder sb = new StringBuilder("CREATE TABLE ").append(table.getSqlName()).append(
                " ( ID INTEGER PRIMARY KEY AUTOINCREMENT ");

        for (Field column : fields) {
            String columnName = StringUtil.toSQLName(column.getName());
            String columnType = QueryBuilder.getColumnType(column.getType());

            if (!TextUtils.isEmpty(columnType)) {

                if (columnName.equalsIgnoreCase("Id")) {
                    continue;
                }

                if (column.isAnnotationPresent(Column.class)) {
                    Column columnAnnotation = column.getAnnotation(Column.class);
                    columnName = columnAnnotation.name();

                    sb.append(", ").append(columnName).append(" ").append(columnType);

                    if (columnAnnotation.notNull()) {
                        if (columnType.endsWith(" NULL")) {
                            sb.delete(sb.length() - 5, sb.length());
                        }
                        sb.append(" NOT NULL");
                    }

                    if (columnAnnotation.unique()) {
                        sb.append(" UNIQUE");
                    }

                } else {
                    sb.append(", ").append(columnName).append(" ").append(columnType);

                    if (column.isAnnotationPresent(NotNull.class)) {
                        if (columnType.endsWith(" NULL")) {
                            sb.delete(sb.length() - 5, sb.length());
                        }
                        sb.append(" NOT NULL");
                    }

                    if (column.isAnnotationPresent(Unique.class)) {
                        sb.append(" UNIQUE");
                    }
                }
            }
        }
        sb.append(" ) ");

        Log.i("Sugar", "creating table " + table.getSqlName());

        if (!"".equals(sb.toString()))
            sqLiteDatabase.execSQL(sb.toString());
    }

    @Override
    public void onUpgrade(SQLiteDatabase sqLiteDatabase, int oldVersion, int newVersion) {
        Log.i("Sugar", "upgrading sugar");
        // check if some tables are to be created
        doUpgrade(sqLiteDatabase);

        if (!executeSugarUpgrade(sqLiteDatabase, oldVersion, newVersion)) {
            deleteTables(sqLiteDatabase);
            onCreate(sqLiteDatabase);
        }
    }

    /**
     * Create the tables that do not exist.
     */
    private <T extends SugarRecord<?>> void doUpgrade(SQLiteDatabase sqLiteDatabase) {
        List<T> domainClasses = getDomainClasses(context);

        String sql = "select count(*) from sqlite_master where type='table' and name='%s';";

        for (T domain : domainClasses) {
            String tableName = domain.tableName;
            Cursor c = sqLiteDatabase.rawQuery(String.format(sql, tableName), null);
            if (c.moveToFirst() && c.getInt(0) == 0) {
                createTable(domain, sqLiteDatabase);
            } else {
                addColumns(domain, sqLiteDatabase);
            }
            c.close();
        }
    }

    private <T extends SugarRecord<?>> void addColumns(T table, SQLiteDatabase sqLiteDatabase) {

        List<Field> fields = table.getTableFields();
        String tableName = table.getSqlName();
        ArrayList<String> presentColumns = getColumnNames(sqLiteDatabase, tableName);
        ArrayList<String> alterCommands = new ArrayList<>();

        for (Field column : fields) {
            String columnName = StringUtil.toSQLName(column.getName());
            String columnType = QueryBuilder.getColumnType(column.getType());

            if (column.isAnnotationPresent(Column.class)) {
                Column columnAnnotation = column.getAnnotation(Column.class);
                columnName = columnAnnotation.name();
            }

            if (!presentColumns.contains(columnName)) {
                StringBuilder sb = new StringBuilder("ALTER TABLE ");
                sb.append(tableName).append(" ADD COLUMN ").append(columnName).append(" ").append(columnType);
                if (column.isAnnotationPresent(NotNull.class)) {
                    if (columnType.endsWith(" NULL")) {
                        sb.delete(sb.length() - 5, sb.length());
                    }
                    sb.append(" NOT NULL");
                }

                if (column.isAnnotationPresent(Unique.class)) {
                    sb.append(" UNIQUE");
                }
                alterCommands.add(sb.toString());
            }
        }

        for (String command : alterCommands) {
            Log.i("Sugar", command);
            sqLiteDatabase.execSQL(command);
        }
    }

    private ArrayList<String> getColumnNames(SQLiteDatabase sqLiteDatabase, String tableName) {
        Cursor resultsQuery = sqLiteDatabase.query(tableName, null, null, null, null, null, null);
        //Check if columns match vs the one on the domain class
        ArrayList<String> columnNames = new ArrayList<>();
        for (int i = 0; i < resultsQuery.getColumnCount(); i++) {
            String columnName = resultsQuery.getColumnName(i);
            columnNames.add(columnName);
        }
        resultsQuery.close();
        return columnNames;
    }




    private <T extends SugarRecord<?>> void deleteTables(SQLiteDatabase sqLiteDatabase) {
        List<T> tables = getDomainClasses(this.context);
        for (T table : tables) {
            sqLiteDatabase.execSQL("DROP TABLE IF EXISTS " + table.getSqlName());
        }
    }

    private boolean executeSugarUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {

        boolean isSuccess = false;
        try {
            List<String> files = Arrays.asList(this.context.getAssets().list("sugar_upgrades"));

            if (files.size() > 0) {
                Collections.sort(files, new NumberComparator());

                for (String file : files) {
                    Log.i("Sugar", "filename : " + file);
                    try {
                        int version = Integer.valueOf(file.replace(".sql", ""));

                        if ((version > oldVersion) && (version <= newVersion)) {
                            executeScript(db, file);
                            isSuccess = true;
                        }
                    } catch (NumberFormatException e) {
                        Log.i("Sugar", "not a sugar script. ignored." + file);
                    }
                }
            } else {
                return true;
            }
        } catch (IOException e) {
            Log.e("Sugar", e.getMessage());
        }

        return isSuccess;
    }

    private void executeScript(SQLiteDatabase db, String file) {
        try {
            InputStream is = this.context.getAssets().open("sugar_upgrades/" + file);
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            String line;
            while ((line = reader.readLine()) != null) {
                Log.i("Sugar script", line);
                db.execSQL(line.toString());
            }
        } catch (IOException e) {
            Log.e("Sugar", e.getMessage());
        }

        Log.i("Sugar", "script executed");
    }
}
