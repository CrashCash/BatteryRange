package org.genecash.batteryrange;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.support.v4.content.LocalBroadcastManager;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import static org.genecash.batteryrange.BatteryRange.PREFS_BULLSHIT_FACTOR;

public class BatterySettings extends Activity {
    SharedPreferences prefs;
    EditText edBS;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.settings);

        prefs = PreferenceManager.getDefaultSharedPreferences(this);

        // find fields
        edBS = (EditText) findViewById(R.id.bullshit_factor);

        // populate fields from current settings
        edBS.setText("" + prefs.getInt(PREFS_BULLSHIT_FACTOR, 35));

        // "save" button
        findViewById(R.id.save).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                SharedPreferences.Editor editor = prefs.edit();

                try {
                    editor.putInt(PREFS_BULLSHIT_FACTOR, Integer.parseInt(edBS.getText().toString()));
                } catch (NumberFormatException e) {
                    Toast.makeText(getApplicationContext(), "Bullshit factor must be an integer", Toast.LENGTH_LONG).show();
                    return;
                }

                if (editor.commit()) {
                    Toast.makeText(getApplicationContext(), "Settings saved", Toast.LENGTH_LONG).show();
                    // tell running app about new setting
                    LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(new Intent(BatteryRange.ACTION_UPDATE));
                    finish();
                } else {
                    Toast.makeText(getApplicationContext(), "Settings error", Toast.LENGTH_LONG).show();
                }
            }
        });
    }
}
