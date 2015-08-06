package com.orm;

import android.text.TextUtils;

public abstract class SugarApp extends android.app.Application{

    private Database database;
    private static SugarApp sugarContext;

    public void onCreate(){
        super.onCreate();
        SugarApp.sugarContext = this;
        if (TextUtils.isEmpty(customDatabaseParentPath())){
            this.database = new Database(this);
        }else{
            this.database = new Database(new DatabaseContext(this,customDatabaseParentPath()));
        }
    }

    public void onTerminate(){
        if (this.database != null) {
            this.database.getDB().close();
        }
        super.onTerminate();
    }

    public static SugarApp getSugarContext(){
        return sugarContext;
    }

    protected Database getDatabase() {
        return database;
    }

    public String customDatabaseParentPath(){
        return null;
    }
}
