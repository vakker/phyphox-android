package de.rwth_aachen.phyphox;

import android.app.Application;

import java.util.ArrayList;
import java.util.List;

import de.rwth_aachen.phyphox.helper.DataExportUtility;

public class App extends Application {
    public PhyphoxExperiment experiment = null;

    //Titles of the measurements recovered at this start, for the experiment list to tell the user
    public List<String> recoveredRuns = new ArrayList<>();

    @Override
    public void onCreate() {
        super.onCreate();
        //A new process has no measurement yet, so every run folder on disk was left by one that died
        //during a measurement: its data up to the last checkpoint becomes a saved state (RunFolder)
        recoveredRuns = RunFolder.recover(this, getString(R.string.runRecoveredSuffix));
        //Exports left in the cache by earlier starts, off the main thread as they can be large
        new Thread(() -> DataExportUtility.deleteOldExports(this), "deleteOldExports").start();
    }
}
