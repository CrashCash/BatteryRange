# BatteryRange
This application snoops the CANBUS on Zero electric motorcycle via a Bluetooth OBD-II dongle to provide a range map display.

See https://crashcash.github.io/zero_canbus/index.html for information on the CANBUS messages.

See https://crashcash.github.io/obd-II_dongle/index.html on installing a dongle.

This is an Android Studio project.

If you're compiling this and not just installing the APK, you'll need to get a Google Maps API key as per https://developers.google.com/maps/documentation/android-api/signup and put it in app/src/release/res/values/google_maps_api.xml and app/src/debug/res/values/google_maps_api.xml

The google_maps_api.xml files should look like:

```
<resources>
<string name="google_maps_key" translatable="false" templateMergeStrategy="preserve">ADD_API_KEY_HERE</string>
</resources>
```
You also need to enable the "Google Maps Geocoding API" for the home/destination markers to work.

## User Interface
The UI is rather spartan, but it includes the ability to set a "home" and a "destination" marker on the map.

There are several ways to set a marker:
1. Use "Set Home/Destination Marker" to set a marker at the current position.
2. Long-press the map where you want the marker.
3. Search and select the location in Google Maps, then share it to the Battery Range application.
4. If #3 doesn't work, and there are latitude/longitude coordinates in the Maps search box, then select those with copy/paste and share directly from the copy/paste UI.
5. Type/paste the full address or latitude/longitude coordinates directly into "Set Marker By Address/Coordinates"

![Screenshot 1](https://raw.githubusercontent.com/CrashCash/BatteryRange/master/Screenshot_20170426-031044_small.png)
![Screenshot 2](https://raw.githubusercontent.com/CrashCash/BatteryRange/master/Screenshot_20170426-031111_small.png)
