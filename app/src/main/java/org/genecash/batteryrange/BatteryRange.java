package org.genecash.batteryrange;

import android.Manifest;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.places.Places;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.Circle;
import com.google.android.gms.maps.model.CircleOptions;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.maps.android.SphericalUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.FileHandler;
import java.util.logging.Logger;

@SuppressWarnings("MissingPermission")
public class BatteryRange extends AppCompatActivity
        implements OnMapReadyCallback, GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener, LocationListener {

    // housekeeping
    static final int PERMISSIONS_REQUEST_ACCESS_FINE_LOCATION = 1;
    static final String TAG = BatteryRange.class.getSimpleName();
    static final Logger loggerSet = Logger.getLogger("logger");

    // popup menu
    static final int MENU_ZOOM = 1;
    static final int MENU_CENTER = 2;
    static final int MENU_DEVICE = 3;

    // user preferences
    SharedPreferences prefs;
    static final String PREFS_ZOOM = "zoom";
    static final String PREFS_CENTER = "center";
    static final String PREFS_DEVICE = "device";
    // zoom to fit range circle
    boolean flagZoom;
    // center on location
    boolean flagCenter;

    // Google Map
    GoogleMap map;
    GoogleApiClient googleApiClient;
    boolean locationPermissionGranted;
    Circle rangeCircle = null;
    Circle rangeCircle2 = null;
    LocationRequest locationRequest;
    Location currLocation = null;
    // range is in meters
    double range = -1;

    // Bluetooth
    Handler handler;
    String btName;
    BluetoothDevice btDevice;
    BluetoothSocket btSocket = null;
    InputStream btInputStream;
    OutputStream btOutputStream;
    BufferedReader btBuffRdr;
    BtRange rangeTask;
    boolean running;
    boolean connected = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.range);
        handler = new Handler();

        // set up logging
        try {
            java.util.logging.Handler h = new FileHandler(this.getExternalFilesDir(null) + "/BatteryRange%g.txt", 256 * 1024, 100, true);
            h.setFormatter(new CustomLogFormatter());
            loggerSet.addHandler(h);
            loggerSet.setUseParentHandlers(false);
        } catch (Exception e) {
            String msg = "Unable to initialize logging\n" + Log.getStackTraceString(e);
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            Log.e("batteryrange", msg);
        }
        log("\n----------------------------------------------------------------");
        log("onCreate");

        if (ContextCompat.checkSelfPermission(this.getApplicationContext(), Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED) {
            locationPermissionGranted = true;
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                                              PERMISSIONS_REQUEST_ACCESS_FINE_LOCATION);
        }

        // check to see if the GPS is enabled
        LocationManager manager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Toast.makeText(this, "GPS is not enabled", Toast.LENGTH_LONG).show();
            log("GPS is not enabled");
            finish();
            return;
        }

        // read preferences
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        flagCenter = prefs.getBoolean(PREFS_CENTER, true);
        flagZoom = prefs.getBoolean(PREFS_ZOOM, true);
        btName = prefs.getString(PREFS_DEVICE, null);
        if (btName == null || btName.length() == 0) {
            selectDevice();
        }

        // build the Play services client for use by the Fused Location Provider and the Places API
        if (locationPermissionGranted) {
            googleApiClient = new GoogleApiClient.Builder(this)
                    .enableAutoManage(this, this)
                    .addConnectionCallbacks(this)
                    .addApi(LocationServices.API)
                    .addApi(Places.GEO_DATA_API)
                    .addApi(Places.PLACE_DETECTION_API)
                    .build();
            googleApiClient.connect();
        }
    }

    // Manipulates the map when it's available
    @Override
    public void onMapReady(GoogleMap googleMap) {
        log("onMapReady");
        map = googleMap;
        updateLocationUI();
        btConnect();
    }

    // builds the map when the Google Play services client is successfully connected
    @Override
    public void onConnected(@Nullable Bundle bundle) {
        log("onConnected");
        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager().findFragmentById(R.id.map);
        mapFragment.getMapAsync(this);
        locationRequest = new LocationRequest()
                .setInterval(10000) // milliseconds
                .setFastestInterval(5000) // milliseconds
                .setSmallestDisplacement(10) // meters
                .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
        LocationServices.FusedLocationApi.requestLocationUpdates(googleApiClient, locationRequest, this);
    }

    // handles suspension of the connection to the Google Play services client
    @Override
    public void onConnectionSuspended(int i) {
        log("Play services connection suspended");
    }

    // handles failure to connect to the Google Play services client
    @Override
    public void onConnectionFailed(@NonNull ConnectionResult result) {
        log("Play services connection failed: " + result.getErrorCode());
    }

    // handle GPS movement
    @Override
    public void onLocationChanged(Location location) {
        log("onLocationChanged");
        currLocation = location;
        LatLng position = new LatLng(location.getLatitude(), location.getLongitude());

        createCircles();

        if (rangeCircle != null) {
            log("reposition circles");
            rangeCircle.setCenter(position);
            rangeCircle2.setCenter(position);
        }

        resize(position);
    }

    // save battery when we're not focused
    @Override
    protected void onPause() {
        log("onPause");

        // disconnect from Bluetooth
        running = false;

        // turn off location updates
        if (googleApiClient != null && googleApiClient.isConnected()) {
            LocationServices.FusedLocationApi.removeLocationUpdates(googleApiClient, this);
        }
        super.onPause();
    }

    // resume updates when we're selected
    @Override
    protected void onResume() {
        super.onResume();
        log("onResume");

        // turn on location updates
        if (googleApiClient != null && googleApiClient.isConnected()) {
            LocationServices.FusedLocationApi.requestLocationUpdates(googleApiClient, locationRequest, this);
        }
    }

    @Override
    protected void onDestroy() {
        log("onDestroy");

        // disconnect from Bluetooth and stop connection retries
        running = false;
        if (rangeTask != null) {
            rangeTask.cancel(false);
        }
        handler = null;

        // close logging file handlers to get rid of "lck" turdlets
        for (java.util.logging.Handler h : loggerSet.getHandlers()) {
            h.close();
        }
        super.onDestroy();
    }

    // handles Marshmallow permission dialog result
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String permissions[], @NonNull int[] grantResults) {
        locationPermissionGranted = false;
        switch (requestCode) {
            case PERMISSIONS_REQUEST_ACCESS_FINE_LOCATION: {
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    locationPermissionGranted = true;
                    log("permission granted");
                }
            }
        }
        updateLocationUI();
    }

    // populate the options menu when clicked
    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.clear();
        if (connected) {
            if (flagZoom) {
                menu.add(Menu.NONE, MENU_ZOOM, Menu.NONE, "Do Not Zoom To Fit Range");
            } else {
                menu.add(Menu.NONE, MENU_ZOOM, Menu.NONE, "Zoom To Fit Range");
            }
            if (flagCenter) {
                menu.add(Menu.NONE, MENU_CENTER, Menu.NONE, "Do Not Center Map On Location");
            } else {
                menu.add(Menu.NONE, MENU_CENTER, Menu.NONE, "Center Map On Location");
            }
        }
        menu.add(Menu.NONE, MENU_DEVICE, Menu.NONE, "Select Bluetooth Device");
        super.onPrepareOptionsMenu(menu);
        return true;
    }

    // handle options menu selection
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        SharedPreferences.Editor editor;
        switch (item.getItemId()) {
            case MENU_ZOOM:
                flagZoom = !flagZoom;
                resize();
                editor = prefs.edit();
                editor.putBoolean(PREFS_ZOOM, flagZoom);
                editor.apply();
                return true;
            case MENU_CENTER:
                flagCenter = !flagCenter;
                resize();
                editor = prefs.edit();
                editor.putBoolean(PREFS_CENTER, flagCenter);
                editor.apply();
                return true;
            case MENU_DEVICE:
                selectDevice();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // updates the map's UI settings based on whether the user has granted location permission
    void updateLocationUI() {
        log("updateLocationUI");
        if (map == null) {
            return;
        }

        if (locationPermissionGranted) {
            map.setMyLocationEnabled(true);
            map.getUiSettings().setMyLocationButtonEnabled(true);
        } else {
            map.setMyLocationEnabled(false);
            map.getUiSettings().setMyLocationButtonEnabled(false);
        }
    }

    // log to our own file so that messages don't get lost
    void log(String msg) {
        Log.i(TAG, msg);
        loggerSet.info(msg);
    }

    // log exceptions so everyone sees them
    static void logExcept(Exception e) {
        String msg = Log.getStackTraceString(e);
        String fcn = e.getStackTrace()[0].getMethodName();
        Log.i(TAG, fcn, e);
        loggerSet.info(fcn + " exception:" + msg);
    }

    // handle map positioning preference
    void resize(LatLng position) {
        log("resize");
        if (flagZoom && range > 0) {
            // zoom/pan map to exactly show circle at center
            double r = range * 1.2;
            LatLngBounds bounds = new LatLngBounds.Builder()
                    .include(SphericalUtil.computeOffset(position, r, 0))
                    .include(SphericalUtil.computeOffset(position, r, 90))
                    .include(SphericalUtil.computeOffset(position, r, 180))
                    .include(SphericalUtil.computeOffset(position, r, 270))
                    .build();
            map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 0));
        } else if (flagCenter) {
            // center on current location
            map.moveCamera(CameraUpdateFactory.newLatLng(position));
        }
    }

    void resize() {
        resize(new LatLng(currLocation.getLatitude(), currLocation.getLongitude()));
    }

    // create range circles
    void createCircles() {
        if (rangeCircle == null && range > 0 && currLocation != null) {
            log("create circles");
            LatLng position = new LatLng(currLocation.getLatitude(), currLocation.getLongitude());

            // outer circle
            // note that radius is in meters)
            CircleOptions circleOptions = new CircleOptions()
                    .center(position)
                    .radius(range)
                    .fillColor(Color.argb(32, 0, 0, 0))
                    .clickable(true);
            rangeCircle = map.addCircle(circleOptions);
            map.setOnCircleClickListener(new GoogleMap.OnCircleClickListener() {
                @Override
                public void onCircleClick(Circle circle) {
                    log("onCircleClick");
                    resize();
                }
            });

            // inner circle
            CircleOptions circleOptions2 = new CircleOptions()
                    .center(position)
                    .radius(range / 2)
                    .fillColor(Color.argb(0, 0, 0, 0));
            rangeCircle2 = map.addCircle(circleOptions2);
        }
    }

    // pop up dialog to pick device
    void selectDevice() {
        // get paired devices
        List<String> devices = new ArrayList<>();
        BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
        for (BluetoothDevice device : btAdapter.getBondedDevices()) {
            if (device.getType() == BluetoothDevice.DEVICE_TYPE_CLASSIC) {
                devices.add(device.getName() + " (" + device.getAddress() + ")");
            }
        }

        // convert to array needed by dialog & sort
        final String[] devicesArray = devices.toArray(new String[0]);
        Arrays.sort(devicesArray);

        // build list dialog
        final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Select from paired Bluetooth devices");
        builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
                dialog.cancel();
            }
        });

        builder.setItems(devicesArray, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                btName = devicesArray[which].substring(0, devicesArray[which].indexOf(" ("));

                // save picked device
                SharedPreferences.Editor editor = prefs.edit();
                editor.putString(PREFS_DEVICE, btName);
                editor.apply();

                // disconnect from current device
                running = false;
                while (connected) {
                    try {
                        Thread.sleep(250);
                    } catch (InterruptedException e) {
                    }
                }

                // connect to new device
                btConnect();
            }
        });

        builder.create();
        builder.show();
    }

    // connect to our device and initialize it
    void btConnect() {
        log("btConnect");
        // punt if we've exited the app
        if (handler == null) {
            return;
        }
        // basic checks
        if (btName == null) {
            return;
        }
        BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
        if (btAdapter == null) {
            // phone does not support Bluetooth
            Toast.makeText(this, "Phone does not support Bluetooth", Toast.LENGTH_LONG).show();
            return;
        }
        if (!btAdapter.isEnabled()) {
            // Bluetooth not enabled
            Toast.makeText(this, "Bluetooth not enabled", Toast.LENGTH_LONG).show();
            return;
        }

        // look for our device in the paired devices
        Set<BluetoothDevice> pairedDevices = btAdapter.getBondedDevices();
        btDevice = null;
        if (pairedDevices.size() > 0) {
            for (BluetoothDevice device : pairedDevices) {
                String name = device.getName();
                if (btName.equals(name)) {
                    btDevice = device;
                    break;
                }
            }
        }
        if (btDevice == null) {
            // our device is not currently paired
            Toast.makeText(this, "Device is not currently paired", Toast.LENGTH_LONG).show();
            return;
        }

        rangeTask = new BtRange();
        rangeTask.execute();
    }

    // handle data from the dongle and decode range information
    class BtRange extends AsyncTask<Void, String, Void> {
        @Override
        protected Void doInBackground(Void... params) {
            String data;
            String oldData = null;
            String hex;
            double oldRange = range;
            running = true;
            connected = false;

            // punt if we've exited the app
            if (handler == null) {
                return null;
            }

            try {
                // connect to dongle as a serial port I/O device
                log("connect to dongle");
                btSocket = btDevice.createRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"));
                try {
                    btSocket.connect();
                } catch (IOException e) {
                    // we were not able to connect
                    log("unable to connect");
                    // try again soon
                    btRetry();
                    return null;
                }
                log("connected");
                publishProgress("Device connected");
                connected = true;
                btInputStream = btSocket.getInputStream();
                btBuffRdr = new BufferedReader(new InputStreamReader(btInputStream, "ASCII"));
                btOutputStream = btSocket.getOutputStream();

                // initialize
                // SP6    - use CANBUS protocol
                // S0     - suppress spaces
                // CAF0   - automatic formatting off
                // JHF0   - header formatting off
                // CRA440 - filter to only 0x440
                String[] btInit = new String[]{"S0", "SP6", "S0", "CAF0", "JHF0", "CRA440"};
                String response;
                // stop any output stream
                btOutputStream.write(("X\r").getBytes());
                for (String s : btInit) {
                    btOutputStream.write(("AT" + s + "\r").getBytes());
                    do {
                        response = btBuffRdr.readLine();
                        log(response);
                    } while (!response.equals("OK") && !response.contains("?"));
                }
                log("init done");

                // ask for stream of updates
                btOutputStream.write(("ATMA\r").getBytes());
                while (running) {
                    try {
                        data = btBuffRdr.readLine();
                    } catch (IOException e) {
                        // the device disconnected
                        // note that it takes a little while (about 20 seconds) to notice
                        log("disconnected (read)");
                        publishProgress("Device disconnected");
                        connected = false;
                        btOutputStream.close();
                        btInputStream.close();
                        btSocket.close();

                        // try again soon
                        btRetry();
                        return null;
                    }
                    if (data.equals(oldData)) {
                        continue;
                    }
                    oldData = data;
                    if (data.length() < 16) {
                        continue;
                    }

                    // data line looks like 7E110200144D0410
                    // range (miles) = 0x4D14/160.9
                    // range (km) = 0x4D14/100
                    // range (meters) = 0x4D14*10
                    hex = data.substring(10, 12) + data.substring(8, 10);
                    range = Integer.parseInt(hex, 16) * 10;
                    if (range != oldRange) {
                        oldRange = range;
                        log("Range (meters):" + range);
                        log("Range (miles):" + Integer.parseInt(hex, 16) / 160.9);
                        publishProgress();
                        if (range == 0) {
                            // device disconnected because the key was turned off
                            // data: 0000000000000000
                            // note that we find out instantly here
                            // (this is kind of weird that this happens)
                            log("disconnected (range): " + data);
                            publishProgress("Device disconnected");
                            connected = false;
                            btOutputStream.close();
                            btInputStream.close();
                            btSocket.close();

                            // try again soon
                            btRetry();
                            return null;
                        }
                    }
                }

                log("disconnecting");
                publishProgress("Disconnecting from device");

                // stop output stream
                btOutputStream.write(("X\r").getBytes());

                // close nicely
                btOutputStream.close();
                btInputStream.close();
                btSocket.close();
                connected = false;
                log("disconnect done");
            } catch (IOException e) {
                logExcept(e);
                publishProgress("BtRange: " + e.getMessage());
            }
            return null;
        }

        @Override
        protected void onProgressUpdate(String... values) {
            if (values.length > 0) {
                // we were passed a message to display
                Toast.makeText(getApplicationContext(), values[0], Toast.LENGTH_LONG).show();
                return;
            }

            // update range circles
            createCircles();
            if (rangeCircle != null) {
                log("update circles");
                rangeCircle.setRadius(range);
                rangeCircle2.setRadius(range / 2);
                resize();
            }
        }
    }

    // try to connect again after a delay
    void btRetry() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                log("btRetry");
                btConnect();
            }
        }, 5000);
    }
}
