# BatteryRange
Snoops CANBUS on Zero electric motorcycle via a Bluetooth OBD-II dongle to provide a range map display.

This is an Android Studio project.

You will need to get a Google Maps API key as per https://developers.google.com/maps/documentation/android-api/signup and put it in app/src/release/res/values/google_maps_api.xml and app/src/debug/res/values/google_maps_api.xml

The google_maps_api.xml files should look like:

```
<resources>
<string name="google_maps_key" translatable="false" templateMergeStrategy="preserve">ADD_API_KEY_HERE</string>
</resources>
```
