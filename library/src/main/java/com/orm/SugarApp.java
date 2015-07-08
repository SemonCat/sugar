package com.orm;

import android.content.Context;
import android.text.TextUtils;

public class SugarApp{

    private Database database;
    private static SugarApp sugarContext;

    public static SugarApp getSugarContext(){
        return sugarContext;
    }

    protected Database getDatabase() {
        return database;
    }
    
    private SugarApp(){
    	
    }

    public static void init(Context context){
        init(context,null);
    }

    /**
     * Initializes Sugar ORM. It should be initialized in Application class.
     * @param context applicatinContext
     */
    public static void init(Context context,String customDatabasePath){
    	sugarContext = new SugarApp();

        Context appContext = context.getApplicationContext();

        if (!TextUtils.isEmpty(customDatabasePath)){
            appContext = new DatabaseContext(context,customDatabasePath);
        }

    	sugarContext.database = new Database(appContext);
    }
    /**
     * Closes sugar DB connection.
     */
    public static void closeDB(){
    	if(sugarContext.database != null){
    		sugarContext.database.getDB().close();
    	}
    }
}
