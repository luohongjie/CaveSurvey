package com.astoev.cave.survey.service.reports;

import android.util.Log;

import com.astoev.cave.survey.Constants;
import com.astoev.cave.survey.R;
import com.astoev.cave.survey.activity.UIUtilities;
import com.astoev.cave.survey.openstopo.FileUtils;
import com.astoev.cave.survey.util.NetworkUnil;

import org.json.JSONObject;

/**
 * Utility class to report application errors.
 *
 * Created by astoev on 10/22/15.
 */
public class ErrorReporter {

    // where to report the errors
    private static final String REPORTS_SERVER_URL = "TODO";

    private static LogCatDumpThread logsDumpThread;

    public static void startDebugSession() {
        // start dump thread, it will clean existing logs
        logsDumpThread = new LogCatDumpThread();
        logsDumpThread.start();
    }

    public static void reportAndCloseDebugSession() {
        // stop dump thread
        String logFile = logsDumpThread.stopCollection();
        logsDumpThread = null;

        // report
        reportToServer(logFile);
    }

    private static void reportToServer(String aLogFile) {

        // ensure can send the report
        if (!NetworkUnil.isNetworkAvailable()) {
            Log.e(Constants.LOG_TAG_SERVICE, "No network service available");
            UIUtilities.showNotification(R.string.network_unavailable);
            return;
        }

        // collect data
        String content = prepareReportBody(aLogFile);

        // post
        try {
            NetworkUnil.postJSON(REPORTS_SERVER_URL, content);
        } catch (Exception e) {
            Log.e(Constants.LOG_TAG_SERVICE, "Failed to talk to server", e);
            UIUtilities.showNotification(R.string.network_error);
        }

    }

    private static String prepareReportBody(String aLogFile) {
        JSONObject report = new JSONObject();

        // TODO body

        return report.toString();
    }
}
