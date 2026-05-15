# HeadUnitLauncher

HeadUnitLauncher is an Android app that allows you to turn your Android tablet or phone into an Android Auto receiver. This project is a revived version of the original head unit project by the great Michael Reid. The original project can be found here:
https://github.com/mikereidis/headunit

## How to use

### Wired USB Connection
- Connect your Android device (phone) to the tablet running HeadUnitLauncher via USB cable.
- Make sure that Android Auto is installed on your phone.
- Set your phone to Host-Mode if necessary and select Android Auto
- Click the USB Button in HeadUnitLauncher, find your phone and click the right button to allow connection
- Click on your phone in the list and wait for Android Auto to start

### Wireless Helper (Recommended)
This is the most reliable way to connect wirelessly. It uses our companion app on your phone to trigger the connection.

- **Download:** [Wireless Helper on Google Play Store](https://play.google.com/store/apps/details?id=com.andrerinas.wirelesshelper)
- **Features:** Minimal configuration, supports NSD, Wi-Fi Direct Auto-Connect, and Bluetooth Auto-Start.

**Setup:**
- In HeadUnitLauncher Settings: Set **Wireless Mode** to **Helper Mode**.
- Ensure both devices are in the same network (Hotspot or WiFi).
- Open the Wireless Helper app on your phone and start the service.
- The helper will find your head unit and initiate the connection automatically.

### Legacy Wireless Options
- **Wireless Launcher:** You can still use the original [Wireless Launcher](https://play.google.com/store/apps/details?id=com.borconi.emil.wifilauncherforhur) by Emil Borconi.
- **Manual / Native:** Uses the native "Headunit Server" built into Android Auto developer settings (may fail on 10.x.x.x networks).

### Connect Wirelessly via Intent (Power Users)
You can trigger a wireless connection attempt using an Android Intent. This is useful for automation tools like **Tasker**, **MacroDroid**, or via **ADB**.

**URI Scheme:** `headunit://connect?ip=<PHONE_IP>`

**Example ADB Command:**
```bash
adb shell am start -a android.intent.action.VIEW -d "headunit://connect?ip=192.168.1.25"
```

## Planned
### v3.0.0
- Theme-Options for Colors and Images, Car-Logos
- Change settings in Projection, maybe call it "Quick-Settings"
- Remove Native-SSL Libraries to reduce filesize
- Add Permission Checker
- Settings-Reset Button

## Known Issues
- **Google Maps in Portrait Mode:** Touch interactions (searching, scrolling) within Google Maps may not work as expected when using Portrait Mode on some devices. **Fix:** Try reducing the **Pixel density (DPI)** setting to **below 200** (e.g., 190) in the app settings. This often restores full functionality.
- **Wireless Connection Drops:** If the connection drops frequently, disable **"WiFi Assistant"** or **"Switch between networks"** in your phone's WiFi settings to prevent it from killing the connection due to "no internet." Check battery saving options.
- **Self-mode on Android 10 (Q) and below:** Google has disabled the automatic wireless projection startup for Android 10 and below in Android Auto versions 16.4 and higher. While Self-mode still work on newer Android versions, it is currently impossible to trigger projection on Android 10 with recent Google app updates.

## Contributing

Creating release apk needs a keystore file. You can create your own keystore file using the following command in root folder:
`keytool -genkey -v -keystore headunit-release-key.jks -alias headunit-revived -keyalg RSA -keysize 2048 -validity 10000`

After that you need to set the env variables depending on your OS:
MAC:
open ~/.zshrc or ~/.bashrc

`sudo nano ~/.zshrc or sudo nano ~/.bashrc`   
`export HEADUNIT_KEYSTORE_PASSWORD="YOUR_KEYSTORE_PASSWORD"  
export HEADUNIT_KEY_PASSWORD="YOUR_KEY_PASSWORD"`

## Original Head unit
Headunit for Android Auto (tm)

A new car or a $600+ headunit is NOT required to enjoy the integration and distraction reduced environment of Android Auto.

This headunit app can turn a Android 4.1+ tablet supporting USB Host mode into a basic Android Auto compatible headunit.

Android, Google Play, Google Maps, Google Play Music and Android Auto are trademarks of Google Inc.
