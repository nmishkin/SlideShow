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

public class Logger extends PrintStream {


    private final CollectionReference coll;

    public static class LogRecord {
        private Timestamp timestamp;
        private String message;

        public LogRecord(String message) {
            this.setTimestamp(Timestamp.now());
            this.setMessage(message);
        }

        public Timestamp getTimestamp() {
            return timestamp;
        }

        public String getMessage() {
            return message;
        }

        public void setTimestamp(Timestamp timestamp) {
            this.timestamp = timestamp;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }
    
    Logger(Context context) {
        super(makeLogFileStream(context));
        final FirebaseFirestore db = FirebaseFirestore.getInstance();
        coll = db.collection("log");
    }

    @Override
    public PrintStream format(final String format, final Object... args) {
        final String message = String.format(format, args);
        Log.d(TAG, message);
        super.format("%Tc: %s", new Date(), message);
        coll.add(new LogRecord(message));
        return this;
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
