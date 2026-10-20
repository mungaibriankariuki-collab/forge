package com.termux.forge;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PersistableBundle;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.util.Arrays;

/**
 * Termux:Boot built into Forge: on boot, every file in ~/.termux/boot is run in the background in
 * name order. Each script is started from a short job, as the original Termux:Boot app does.
 */
public final class BootScripts {

    private static final String LOG_TAG = "ForgeBoot";
    private static final int JOB_ID_BASE = 1000;

    private BootScripts() {}

    public static File getBootDir() {
        return new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".termux/boot");
    }

    public static void onBootCompleted(Context context) {
        File[] files = getBootDir().listFiles();
        if (files == null || files.length == 0) {
            Logger.logInfo(LOG_TAG, "No files to execute at boot");
            return;
        }
        Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));

        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return;

        int jobId = JOB_ID_BASE;
        StringBuilder names = new StringBuilder();
        for (File file : files) {
            if (!file.isFile()) continue;
            // Make the script readable and executable in case the user forgot to.
            if (!file.canRead()) file.setReadable(true);
            if (!file.canExecute()) file.setExecutable(true);

            PersistableBundle extras = new PersistableBundle();
            extras.putString(Job.SCRIPT_FILE_PATH, file.getAbsolutePath());
            JobInfo job = new JobInfo.Builder(jobId++, new ComponentName(context, Job.class))
                .setExtras(extras)
                .setOverrideDeadline(3 * 1000)
                .build();
            scheduler.schedule(job);

            if (names.length() > 0) names.append(", ");
            names.append(file.getName());
        }
        Logger.logInfo(LOG_TAG, "Executing files at boot: " + names);
    }

    /** Starts one boot script as a background command in TermuxService. */
    public static class Job extends JobService {

        static final String SCRIPT_FILE_PATH = "com.termux.forge.boot.script_path";

        @Override
        public boolean onStartJob(JobParameters params) {
            String path = params.getExtras().getString(SCRIPT_FILE_PATH);
            if (path == null) return false;
            Uri scriptUri = new Uri.Builder().scheme(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.URI_SCHEME_SERVICE_EXECUTE).path(path).build();
            Intent intent = new Intent(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.ACTION_SERVICE_EXECUTE, scriptUri);
            intent.setClassName(TermuxConstants.TERMUX_PACKAGE_NAME, TermuxConstants.TERMUX_APP.TERMUX_SERVICE_NAME);
            intent.putExtra(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_BACKGROUND, true);
            Context context = getApplicationContext();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
            else context.startService(intent);
            return false; // Handed over to TermuxService.
        }

        @Override
        public boolean onStopJob(JobParameters params) {
            return false;
        }
    }
}
