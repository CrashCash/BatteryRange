package org.genecash.batteryrange;

import java.text.Format;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;

public class CustomLogFormatter extends Formatter {
    static long last_time = 0;

    @Override
    public String format(LogRecord record) {

        // just the date and the message
        Date date = new Date(record.getMillis());
        Format formatter = new SimpleDateFormat("dd-MMM-yyyy HH:mm:ss.SSS");
        String s = null;
        s = formatter.format(date);
        if (last_time > 0) {
            // awk '{print $2 ;}' service-log0.txt | sort -gr | more
            s += " +" + String.format("%.3f", (record.getMillis() - last_time) / 1000.0);
        }
        last_time = record.getMillis();
        return s + " " + formatMessage(record) + "\n";
    }
}
