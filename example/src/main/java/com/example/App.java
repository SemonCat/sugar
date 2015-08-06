package com.example;

import android.app.Application;
import android.os.Environment;

import com.orm.SugarApp;

import java.io.File;

/**
 * Created by SemonCat on 15/8/6.
 */
public class App extends Application{

    @Override
    public void onCreate() {
        super.onCreate();
        SugarApp.init(this,customDatabaseParentPath());
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        SugarApp.closeDB();
    }

    public String customDatabaseParentPath() {
        String SDCardPath = Environment.getExternalStorageDirectory().getPath();

        return SDCardPath+ File.separator+"Asus Push";
    }
}
