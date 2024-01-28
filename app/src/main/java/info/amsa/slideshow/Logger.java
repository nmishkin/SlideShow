package info.amsa.slideshow;

import static android.content.Context.MODE_APPEND;
import static info.amsa.slideshow.MainApplication.TAG;

import android.content.Context;
import android.util.Log;

import com.google.firebase.Timestamp;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.FirebaseFirestore;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.Date;
import java.util.Map;

public class Logger {
    private final CollectionReference coll;
    private final PrintStream logStream;

    public static class LogRecord {
        private Timestamp timestamp;
        private int priority;
        private String message;

        public LogRecord(final int priority, String message) {
            this.setTimestamp(Timestamp.now());
            this.setPriority(priority);
            this.setMessage(message);
        }

        public Timestamp getTimestamp() {
            return timestamp;
        }

        public String getMessage() {
            return message;
        }

        public int getPriority() {
            return priority;
        }

        public void setPriority(int priority) {
            this.priority = priority;
        }

        public void setTimestamp(Timestamp timestamp) {
            this.timestamp = timestamp;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }
    
    Logger(Context context) {
        logStream = makeLogFileStream(context);
        final FirebaseFirestore db = FirebaseFirestore.getInstance();
        coll = db.collection("log");
    }

    public String log(final int priority, final String format, final Object... args) {
        final String message = String.format(format, args);
        Log.println(priority, TAG, message);
        logStream.format("%Tc: [%s] %s\n", new Date(), priorityToString(priority), message);
        coll.add(new LogRecord(priority, message));
        return message;
    }

    private static final Map<Integer, String> PRIORITY_TO_STRING = Map.of(
            Log.VERBOSE, "V",
            Log.DEBUG, "D",
            Log.INFO, "I",
            Log.WARN, "W",
            Log.ERROR, "E",
            Log.ASSERT, "A"
    );

    private String priorityToString(int priority) {
        String s = PRIORITY_TO_STRING.get(priority);
        return s == null ? "?" : s;
    }

    public Logger fatal(final String format, final Object... args) {
        throw new RuntimeException(log(Log.ERROR, format, args));
    }

    public void warn(final String format, final Object... args) {
        log(Log.WARN, format, args);
    }

    public void info(final String format, final Object... args) {
        log(Log.INFO, format, args);
    }

    public void debug(final String format, final Object... args) {
        log(Log.DEBUG, format, args);
    }

    private static PrintStream makeLogFileStream(Context context) {
        try {
            final FileOutputStream logFile = context.openFileOutput(TAG + ".log", MODE_APPEND);
            return new PrintStream(logFile);
        } catch (final FileNotFoundException e) {
            throw new RuntimeException("Can't open log file", e);
        }
    }
}
