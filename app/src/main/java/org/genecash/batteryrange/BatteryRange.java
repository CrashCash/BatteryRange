package org.genecash.batteryrange;

import android.Manifest;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.v4.app.FragmentActivity;
import android.text.format.DateUtils;
import android.util.Base64;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.places.Places;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.Circle;
import com.google.android.gms.maps.model.CircleOptions;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.maps.android.SphericalUtil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.FileHandler;
import java.util.logging.Logger;

@SuppressWarnings("MissingPermission")
public class BatteryRange extends FragmentActivity
        implements OnMapReadyCallback, GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener, LocationListener,
                   GoogleMap.OnMapLongClickListener, GoogleMap.OnMapClickListener {

    // testing the GUI w/o actually connecting
    boolean testing = false;

    // housekeeping
    static final int PERMISSIONS_REQUEST_ACCESS_FINE_LOCATION = 1;
    static final String TAG = BatteryRange.class.getSimpleName();
    static final Logger loggerSet = Logger.getLogger("logger");

    // user preferences
    SharedPreferences prefs;
    static final String PREFS_ZOOM = "zoom";
    static final String PREFS_CENTER = "center";
    static final String PREFS_DEVICE = "device";
    static final String PREFS_HOME_LAT = "home-lat";
    static final String PREFS_HOME_LNG = "home-lng";
    static final String PREFS_HOME_SNIP = "home-snip";
    static final String PREFS_DEST_LAT = "dest-lat";
    static final String PREFS_DEST_LNG = "dest-lng";
    static final String PREFS_DEST_SNIP = "dest-snip";
    static final String PREFS_SAVE_LAT = "save-lat";
    static final String PREFS_SAVE_LNG = "save-lng";
    static final String PREFS_SAVE_ZOOM = "save-zoom";
    static final String PREFS_BULLSHIT_FACTOR = "bullshit-factor";

    // zoom to fit range circle
    boolean flagZoom;

    // center on location
    boolean flagCenter;

    // Google Map
    GoogleMap map;
    GoogleApiClient googleApiClient;
    LocationManager locationManager;
    boolean locationPermissionGranted;

    // range as given by the bike minus bullshit factor
    Circle rangeCircleFull = null;

    // half of full range
    Circle rangeCircleHalf = null;

    Location currLocation = null;

    // range (meters)
    double range = -1;

    // this is the range (meters) at which power drops to zero
    // this was discovered to be necessary after getting stranded
    public static double bullshit_factor;

    Marker markHome = null;
    Marker markDest = null;
    static final int MARK_UNKNOWN = 0;
    static final int MARK_HOME = 1;
    static final int MARK_DEST = 2;

    // Bluetooth
    BluetoothAdapter btAdapter;
    String btName;
    BluetoothDevice btDevice;
    BtRange rangeTask;

    static final int STATUS_RUNNING = 0;
    static final int STATUS_STOP = 1;
    static final int STATUS_FINISHED = 2;
    volatile int status = STATUS_FINISHED;

    // well known serial device SPP
    UUID uuid_spp = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    // DSt2 packet
    // Python: import base64; base64.b64encode(x)
    byte[] cmd = Base64.decode("8fL0+AAAAABEU3Qy+PTy8RB5r/g=", Base64.DEFAULT);

    // response trailer
    byte[] trailer = new byte[]{(byte) 0xF8, (byte) 0xF4, (byte) 0xF2, (byte) 0xF1};

    // deal with Bluetooth turning on and off
    private final BroadcastReceiver btReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);

                switch (state) {
                    case BluetoothAdapter.STATE_OFF:
                        status = STATUS_FINISHED;
                        break;
                    case BluetoothAdapter.STATE_ON:
                        btConnect();
                        break;
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.range);

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

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED) {
            locationPermissionGranted = true;
        } else {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, PERMISSIONS_REQUEST_ACCESS_FINE_LOCATION);
        }

        // get intent, action and MIME type
        Intent intent = getIntent();
        String action = intent.getAction();
        String type = intent.getType();

        // handle location sent from Google Maps
        if (Intent.ACTION_SEND.equals(action) && type != null && "text/plain".equals(type)) {
            String[] address = intent.getStringExtra(Intent.EXTRA_TEXT).split("\n");
            if (address.length > 1) {
                setMarker(address[1], null);
            } else {
                setMarker(address[0], null);
            }
        }

        // read preferences
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        bullshit_factor = prefs.getInt(PREFS_BULLSHIT_FACTOR, 35) * 1000;
        flagCenter = prefs.getBoolean(PREFS_CENTER, true);
        flagZoom = prefs.getBoolean(PREFS_ZOOM, true);
        btName = prefs.getString(PREFS_DEVICE, null);

        btAdapter = BluetoothAdapter.getDefaultAdapter();
        if (btAdapter == null) {
            Toast.makeText(this, "Phone does not support Bluetooth", Toast.LENGTH_LONG).show();
            // this is fatal
            return;
        }

        registerReceiver(btReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));

        // check to see if the GPS is enabled
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Toast.makeText(this, "GPS is not enabled", Toast.LENGTH_LONG).show();
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
        map = googleMap;

        // load markers from preferences
        double lat = prefs.getFloat(PREFS_HOME_LAT, 999);
        double lng = prefs.getFloat(PREFS_HOME_LNG, 999);
        String snippet = prefs.getString(PREFS_HOME_SNIP, "");
        if (lat != 999 && lng != 999) {
            markHome = map.addMarker(new MarkerOptions().title("Home").position(new LatLng(lat, lng)).snippet(snippet));
        }

        lat = prefs.getFloat(PREFS_DEST_LAT, 999);
        lng = prefs.getFloat(PREFS_DEST_LNG, 999);
        snippet = prefs.getString(PREFS_DEST_SNIP, "");
        if (lat != 999 && lng != 999) {
            markDest = map.addMarker(new MarkerOptions().title("Destination").position(new LatLng(lat, lng)).snippet(snippet));
        }

        map.setOnMapClickListener(this);
        map.setOnMapLongClickListener(this);

        // the circle click overrides the map click
        map.setOnCircleClickListener(circle -> showCircles());

        // restore last position
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(Double.longBitsToDouble(prefs.getLong(PREFS_SAVE_LAT, 0)),
                                                                    Double.longBitsToDouble(prefs.getLong(PREFS_SAVE_LNG, 0))),
                                                         prefs.getFloat(PREFS_SAVE_ZOOM, 0)));

        updateLocationUI();
        btConnect();
    }

    // builds the map when the Google Play services client is successfully connected
    @Override
    public void onConnected(Bundle bundle) {
        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager().findFragmentById(R.id.map);
        mapFragment.getMapAsync(this);
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5 * DateUtils.SECOND_IN_MILLIS, 50, this);
    }

    // handles suspension of the connection to the Google Play services client
    @Override
    public void onConnectionSuspended(int i) {
        log("onConnectionSuspended: " + i);
    }

    // handles failure to connect to the Google Play services client
    @Override
    public void onConnectionFailed(ConnectionResult result) {
        log("Play services connection failed: " + result.getErrorCode());
    }

    // handle GPS movement
    @Override
    public void onLocationChanged(Location location) {
        currLocation = location;
        updateCircles();
        btConnect();
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
        log("onStatusChanged: " + provider + " (" + status + ")");
    }

    @Override
    public void onProviderEnabled(String provider) {
        log("onProviderEnabled: " + provider);
    }

    @Override
    public void onProviderDisabled(String provider) {
        // turn off Bluetooth connection when GPS turned off
        status = STATUS_FINISHED;
        log("onProviderDisabled: " + provider);
    }

    // handle map tap by showing circles
    @Override
    public void onMapClick(LatLng latLng) {
        if (currLocation == null) {
            Toast.makeText(this, "Location not acquired", Toast.LENGTH_LONG).show();
            return;
        }
        showCircles();
    }

    // handle map long-press to place marker
    @Override
    public void onMapLongClick(LatLng latLng) {
        setMarker(null, latLng);
    }

    // save battery when we're not focused
    @Override
    protected void onPause() {
        // disconnect from Bluetooth and stop connection retries
        status = STATUS_FINISHED;

        // remove circles
        range = 0;
        updateCircles();

        // turn off location updates
        if (googleApiClient != null && googleApiClient.isConnected()) {
            locationManager.removeUpdates(this);
        }

        // remember map position/zoom
        if (map != null) {
            CameraPosition pos = map.getCameraPosition();
            SharedPreferences.Editor editor = prefs.edit();
            editor.putLong(PREFS_SAVE_LAT, Double.doubleToRawLongBits(pos.target.latitude));
            editor.putLong(PREFS_SAVE_LNG, Double.doubleToRawLongBits(pos.target.longitude));
            editor.putFloat(PREFS_SAVE_ZOOM, pos.zoom);
            editor.apply();
        }

        super.onPause();
    }

    // resume updates when we're selected
    @Override
    protected void onResume() {
        super.onResume();

        // start talking to bike again
        btConnect();

        // turn on location updates
        if (googleApiClient != null && googleApiClient.isConnected()) {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5 * DateUtils.SECOND_IN_MILLIS, 50, this);
        }
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(btReceiver);
        } catch (Exception e) {
            // there's no way to tell if a receiver has been registered...
            // you have to just unregister it anyway and deal
        }

        // close logging file handlers to get rid of "lck" turdlets
        for (java.util.logging.Handler h : loggerSet.getHandlers()) {
            h.close();
        }

        super.onDestroy();
    }

    // handles Marshmallow permission dialog result
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        locationPermissionGranted = false;
        if (requestCode == PERMISSIONS_REQUEST_ACCESS_FINE_LOCATION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                locationPermissionGranted = true;
            }
        }
        updateLocationUI();
    }

    // populate the options menu
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.dropdown_menu, menu);
        MenuItem check = menu.findItem(R.id.action_fit_range);
        check.setChecked(flagZoom);
        check = menu.findItem(R.id.action_center);
        check.setChecked(flagCenter);
        super.onCreateOptionsMenu(menu);
        return true;
    }

    public void menuToggleFitRange(MenuItem item) {
        SharedPreferences.Editor editor;

        flagZoom = !flagZoom;
        item.setChecked(flagZoom);
        resize();
        editor = prefs.edit();
        editor.putBoolean(PREFS_ZOOM, flagZoom);
        editor.apply();
    }

    public void menuToggleCenter(MenuItem item) {
        SharedPreferences.Editor editor;

        flagCenter = !flagCenter;
        item.setChecked(flagCenter);
        resize();
        editor = prefs.edit();
        editor.putBoolean(PREFS_CENTER, flagCenter);
        editor.apply();
    }

    public void menuSetHome(MenuItem item) {
        if (currLocation == null) {
            Toast.makeText(getApplicationContext(), "No GPS location", Toast.LENGTH_LONG).show();
        } else {
            setMarker(MARK_HOME, currLocation.getLatitude(), currLocation.getLongitude(), "Home");
        }
    }

    public void menuSetDest(MenuItem item) {
        if (currLocation == null) {
            Toast.makeText(getApplicationContext(), "No GPS location", Toast.LENGTH_LONG).show();
        } else {
            setMarker(MARK_DEST, currLocation.getLatitude(), currLocation.getLongitude(), "Destination");
        }
    }

    public void menuSetMarker(MenuItem item) {
        setMarker("", null);
    }

    public void menuSelectDevice(MenuItem item) {
        selectDevice();
    }

    public void menuSetup(MenuItem item) {
        Intent i = new Intent(this, BatterySettings.class);
        startActivity(i);
    }

    // updates the map's UI settings based on whether the user has granted location permission
    void updateLocationUI() {
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
        map.getUiSettings().setZoomControlsEnabled(true);
        map.getUiSettings().setRotateGesturesEnabled(false);
    }

    // log to our own file so that messages don't get lost
    void log(String msg) {
        Log.i(TAG, msg);
        //loggerSet.info(msg);
    }

    // log exceptions so everyone sees them
    static void logExcept(Exception e) {
        // String msg = Log.getStackTraceString(e);
        String fcn = e.getStackTrace()[0].getMethodName();
        Log.i(TAG, fcn, e);
        //loggerSet.info(fcn + " exception:" + msg);
    }

    // handle map repositioning
    void resize() {
        if (currLocation != null) {
            LatLng position = new LatLng(currLocation.getLatitude(), currLocation.getLongitude());
            if (flagZoom && range > 0) {
                // zoom/pan map to exactly show circle at center
                double r = (range - bullshit_factor) * 1.1;
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
    }

    // show the circles once, when map is tapped
    void showCircles() {
        boolean old = flagZoom;
        flagZoom = true;
        resize();
        flagZoom = old;
    }

    // update range circles
    void updateCircles() {
        double realRange = range - bullshit_factor;
        if (currLocation == null || realRange <= 0) {
            if (rangeCircleFull != null) {
                rangeCircleHalf.remove();
                rangeCircleFull.remove();
                rangeCircleFull = null;
                rangeCircleHalf = null;
            }
            return;
        }

        LatLng position = new LatLng(currLocation.getLatitude(), currLocation.getLongitude());
        if (rangeCircleFull == null) {
            CircleOptions circleOptions = new CircleOptions()
                    .center(position)
                    .radius(realRange)
                    .fillColor(Color.argb(32, 0, 0, 0))
                    .clickable(true);
            rangeCircleFull = map.addCircle(circleOptions);

            circleOptions.radius(realRange / 2).fillColor(Color.argb(64, 0, 0, 0));
            rangeCircleHalf = map.addCircle(circleOptions);
        } else {
            rangeCircleFull.setRadius(realRange);
            rangeCircleFull.setCenter(position);
            rangeCircleHalf.setRadius(realRange / 2);
            rangeCircleHalf.setCenter(position);
        }
        resize();
    }

    // pop up dialog to pick motorcycle device
    void selectDevice() {
        // get paired devices
        List<String> devices = new ArrayList<>();
        BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
        for (BluetoothDevice device : btAdapter.getBondedDevices()) {
            if (device.getType() == BluetoothDevice.DEVICE_TYPE_CLASSIC) {
                String name = device.getName();
                if (name.startsWith("ZeroMotorcycles")) {
                    devices.add(name + " (" + device.getAddress() + ")");
                }
            }
        }

        if (devices.size() == 0) {
            Toast.makeText(getApplicationContext(), "No paired motorcycles", Toast.LENGTH_LONG).show();
            return;
        }

        // convert to array needed by dialog & sort
        final String[] devicesArray = devices.toArray(new String[0]);
        Arrays.sort(devicesArray);

        // build list dialog
        final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Select from paired Bluetooth devices");
        builder.setNegativeButton("Cancel", (dialog, id) -> dialog.cancel());

        builder.setItems(devicesArray, (dialog, which) -> {
            btName = devicesArray[which].substring(0, devicesArray[which].indexOf(" ("));

            // save picked device
            SharedPreferences.Editor editor = prefs.edit();
            editor.putString(PREFS_DEVICE, btName);
            editor.apply();

            // disconnect from current device
            if (status == STATUS_RUNNING) {
                status = STATUS_STOP;
                while (status != STATUS_FINISHED) {
                    try {
                        Thread.sleep(250);
                    } catch (InterruptedException e) {
                    }
                }
            }

            // connect to new device
            btConnect();
        });

        builder.create();
        builder.show();
    }

    // dialog to set a marker
    void setMarker(String address, final LatLng latLng) {
        // I tried using Google "Place Autocomplete" from https://developers.google.com/places/android-api/autocomplete but passing the
        // information from the full-featured Google Maps app is much better
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Set Marker");

        // set up layout
        View views;
        if (address == null) {
            views = this.getLayoutInflater().inflate(R.layout.dialog_marker_short, null);
        } else {
            views = this.getLayoutInflater().inflate(R.layout.dialog_marker_full, null);
        }
        builder.setView(views);

        final EditText addressText = views.findViewById(R.id.address);
        if (address != null) {
            addressText.setText(address);
        }
        final RadioGroup radioGroup = views.findViewById(R.id.radiobuttons);

        builder.setPositiveButton("OK", (dialog, id) -> {
            double lat;
            double lng;
            String snippet = "";

            // figure out if we're setting the home or destination marker
            int flag = MARK_UNKNOWN;
            if (radioGroup != null) {
                int buttonId = radioGroup.getCheckedRadioButtonId();
                if (buttonId == R.id.home) {
                    flag = MARK_HOME;
                } else if (buttonId == R.id.dest) {
                    flag = MARK_DEST;
                }
            }
            if (flag == MARK_UNKNOWN) {
                Toast.makeText(getApplicationContext(), "You must pick a marker", Toast.LENGTH_LONG).show();
                return;
            }

            // determine the position
            if (latLng != null) {
                lat = latLng.latitude;
                lng = latLng.longitude;
            } else {
                String addressFinal = addressText.getText().toString().trim();
                if (addressFinal.length() == 0) {
                    Toast.makeText(getApplicationContext(), "You must enter an address", Toast.LENGTH_LONG).show();
                    return;
                } else {
                    // convert street address to lat/long
                    Geocoder coder = new Geocoder(getApplicationContext());
                    try {
                        List<Address> addresses = coder.getFromLocationName(addressFinal, 1);
                        if (addresses.size() == 0) {
                            Toast.makeText(getApplicationContext(), "Address not found", Toast.LENGTH_LONG).show();
                            return;
                        } else {
                            lat = addresses.get(0).getLatitude();
                            lng = addresses.get(0).getLongitude();
                            snippet = addressFinal;
                        }
                    } catch (IOException e) {
                        logExcept(e);
                        Toast.makeText(getApplicationContext(), "Exception: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        return;
                    }
                }
            }

            // finally actually set a marker
            setMarker(flag, lat, lng, snippet);
        });

        builder.setNegativeButton("Cancel", (dialog, id) -> dialog.cancel());

        builder.create();
        builder.show();
    }

    // set a marker with known type and location
    void setMarker(int flag, double lat, double lng, String snippet) {
        SharedPreferences.Editor editor = prefs.edit();
        MarkerOptions options = new MarkerOptions().position(new LatLng(lat, lng));
        if (snippet != null && snippet.length() > 0) {
            options.snippet(snippet);
        }
        switch (flag) {
            case MARK_HOME:
                if (markHome != null) {
                    markHome.remove();
                }
                options.title("Home");
                markHome = map.addMarker(options);
                editor.putFloat(PREFS_HOME_LAT, (float) lat);
                editor.putFloat(PREFS_HOME_LNG, (float) lng);
                editor.putString(PREFS_HOME_SNIP, snippet);
                break;
            case MARK_DEST:
                if (markDest != null) {
                    markDest.remove();
                }
                options.title("Destination");
                markDest = map.addMarker(options);
                editor.putFloat(PREFS_DEST_LAT, (float) lat);
                editor.putFloat(PREFS_DEST_LNG, (float) lng);
                editor.putString(PREFS_DEST_SNIP, snippet);
                break;
        }
        editor.apply();

        // pan and zoom in one smooth move
        CameraPosition.Builder camera = new CameraPosition.Builder();
        camera.target(new LatLng(lat, lng));
        camera.zoom(15);
        map.animateCamera(CameraUpdateFactory.newCameraPosition(camera.build()));
    }

    // connect to the bike
    void btConnect() {
        rangeTask = new BtRange();
        rangeTask.execute();
    }

    // I can't believe this isn't in Java - what a steaming pile
    public int find(byte[] buffer, byte[] key) {
        for (int i = 0; i <= buffer.length - key.length; i++) {
            int j = 0;
            while (j < key.length && buffer[i + j] == key[j]) {
                j++;
            }
            if (j == key.length) {
                return i;
            }
        }
        return -1;
    }

    // handle data from the bike and decode range information
    // this class should always be running except when Bluetooth or the GPS is off, or the app is paused
    class BtRange extends AsyncTask<Void, String, Void> {
        @Override
        protected Void doInBackground(Void... params) {
            double oldRange = 0;
            BluetoothSocket btSocket = null;
            InputStream btInputStream = null;
            OutputStream btOutputStream = null;

            if (status == STATUS_RUNNING) {
                // we're already running
                return null;
            }

            status = STATUS_FINISHED;
            if (!btAdapter.isEnabled()) {
                // Bluetooth not enabled
                publishProgress("Bluetooth not enabled");
                return null;
            }

            if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                publishProgress("GPS is not enabled");
                return null;
            }

            if (btDevice == null) {
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
            }

            if (btDevice == null) {
                publishProgress("Motorcycle not paired");
                return null;
            }

            status = STATUS_RUNNING;
            while (status == STATUS_RUNNING) {
                try {
                    // connect to bike
                    publishProgress("Connecting to bike");
                    btSocket = btDevice.createInsecureRfcommSocketToServiceRecord(uuid_spp);
                    if (!testing) {
                        btSocket.connect();
                    }
                    publishProgress("Bike connected");
                    btInputStream = btSocket.getInputStream();
                    btOutputStream = btSocket.getOutputStream();

                    while (status == STATUS_RUNNING) {
                        if (!testing) {
                            // write command packet
                            btOutputStream.write(cmd);

                            // read response packet
                            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                            byte[] data = new byte[1024];
                            byte[] packet = null;
                            boolean complete = false;
                            while (!complete) {
                                // really should do a select(), but Java makes that impossible
                                int ctr = btInputStream.read(data);
                                if (ctr == -1) {
                                    break;
                                }
                                buffer.write(data, 0, ctr);
                                // did we get the trailer & checksum?
                                packet = buffer.toByteArray();
                                int i = find(packet, trailer);
                                complete = (i > 0) && ((packet.length - i) == 8);
                            }

                            // extract range in meters
                            range = (packet[16] + 256 * packet[17]) * 10.0;
                        } else {
                            // fake range for testing GUI
                            if (range < 0) {
                                range = 80000;
                            }
                            range -= 2500;
                        }
                        log("RANGE: " + range);
                        if (range != oldRange) {
                            oldRange = range;
                            publishProgress();
                        }
                        Thread.sleep(5 * 1000);
                    }
                } catch (Exception e) {
                    // if anything goes wrong, remove the range circles
                    range = 0;
                    updateCircles();
                    String msg = e.getMessage();
                    if (!msg.contains("read ret: -1")) {
                        publishProgress("BtRange: " + msg);
                    }
                    logExcept(e);
                }

                try {
                    if (btOutputStream != null) {
                        btOutputStream.close();
                    }
                } catch (IOException e) {
                    publishProgress("btOutputStream.close(): " + e.getMessage());
                }
                try {
                    if (btInputStream != null) {
                        btInputStream.close();
                    }
                } catch (IOException e) {
                    publishProgress("btInputStream.close: " + e.getMessage());
                }
                try {
                    if (btSocket != null) {
                        btSocket.close();
                    }
                } catch (IOException e) {
                    publishProgress("btSocket.close: " + e.getMessage());
                }
            }
            status = STATUS_FINISHED;
            return null;
        }

        @Override
        protected void onProgressUpdate(String... values) {
            if (values.length > 0) {
                // we were passed a message to display
                Toast.makeText(getApplicationContext(), values[0], Toast.LENGTH_SHORT).show();
                log(values[0]);
                return;
            }

            // update range circles
            updateCircles();
        }
    }
}

