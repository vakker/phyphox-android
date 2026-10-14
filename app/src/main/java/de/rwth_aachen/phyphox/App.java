package de.rwth_aachen.phyphox;

import android.app.Application;

import de.rwth_aachen.phyphox.helper.DataExportUtility;

public class App extends Application {
    public PhyphoxExperiment experiment = null;

    @Override
    public void onCreate() {
        super.onCreate();
        //Exports left in the cache by earlier starts, off the main thread as they can be large
        new Thread(() -> DataExportUtility.deleteOldExports(this), "deleteOldExports").start();
    }
}
