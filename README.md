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

You can set these from your current location using the options menu, or you can share a location from Google Maps. That is, you can search and select the location in Google Maps, then share it to the Battery Range application. This will pop up a dialog allowing you to select which marker you want to set.
