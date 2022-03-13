Bluetooth GNSS
--------------

Connect your Android phone to external Bluetooth GPS, GLONASS, Galileo and BeiDou receivers and use the received location in your android phone via the mock location provider.

Official app is available on Google Play as [Bluetooth GNSS](https://play.google.com/store/apps/details?id=com.clearevo.bluetooth_gnss&hl=en&gl=US)

Build instructions
-----------

These instructions are for Ubuntu GNU/Linux, it is similar in general for other operating systems, please see example flutter command usage for each OS.

* Make sure you have the Android SDK installed and the env $ANDROID_HOME set:
`echo $ANDROID_HOME`

* Install the the [Flutter SDK](https://flutter.dev/docs/get-started/install) as per official instructions including the 'Android setup' part.

* In this folder run:
`flutter sdk-path`

* Test that it works:
`flutter doctor`

* In this folder run:
`flutter pub get`

* If you don't already have an android signing key, create one as per:
<https://docs.flutter.dev/deployment/android#create-an-upload-keystore>

* Create the file `key.properties` and add your keystore information:
```
storeFile=/path/to/keystore.jks
storePassword=*********
keyAlias=bluetooth_gnss
keyPassword=**********
``` 
* Connect an android phone with adb working
`adb devices`

* Try build and run the app in debug mode:
`flutter run`
If all went well, you would see the app now run in your connected phone.

* Build release android installer (apk) file:
`flutter build apk`
If all went well, it would create the apk file in the folder:
`build/app/outputs/flutter-apk/app-release.apk`

* Finally, open android studio, and choose to 'Open' this folder and you can edit/run from there and use the previous command when you want to build an apk file.


Initiate connection using external intent
-----------------------------------------
I'm using [Tasker](https://play.google.com/store/apps/details?id=net.dinglisch.android.taskerm) to send the intent, but other methods are possible

### Configure the task
On the `TASKS` tab, create a new task (e.g. _Connect GPS_) and add the action _Send Intent_. It is configured as follows:
* Action: `bluetooth.CONNECT`
* Cat: `Default`
* Mime Type: `text/plain`
* Data: _&lt;empty&gt;_
* Extra: _&lt;see json string below&gt;_
* Package: `com.clearevo.bluetooth_gnss`
* Class: _&lt;empty&gt;_
* Target: `Broadcast Receiver`

**json string in Extra field**

Add this string without newlines. All values are optional. Note that `config` is without double quotes

```
config: {
    "bdaddr": "%bt_address",
    "secure": false,
    "reconnect": true,
    "log_bt_rx": true,
    "disable_ntrip": true,
    "extra": {
        "ntrip_user": "taskeruser",
        "ntrip_mountpoint": "taskermount",
        "ntrip_host": "taskerhost",
        "ntrip_port": "2000",
        "ntrip_pass": "taskerpass"
    }
}
```

**Example**

If you only want to override the bluetooth address, you can send this as extra:

```
config: {bdaddr:"%bt_address"}
```

If you don't use the BT Connection Event (see below) but the BT Connection state, you have to use the real address of your device:

```
config: {bdaddr: "00:11:22:33:44:55:66"}
```

If you want to disable ntrip as well:

```
config: {bdaddr: "00:11:22:33:44:55:66", disable_ntrip: true}
```

### Configure the event
On the `PROFILES` tab, you can use a state change or a event. In this example, I'm using the [BT Connection event](https://tasker.joaoapps.com/userguide/en/help/eh_bt_connect_disconnect.html) with the following conditions:

* `%bt_connected EQ true`
* `$bt_address EQ 00:11:22:33:44:55:66`, the bluetooth address of the GPS receiver

This will trigger the event only when my GPS receiver is connected. You can add multiple devices here, which is why I choose this method: in the action, the variable `%bt_address` will be available as well and I'll use that to pass to Bluetooth GNSS.

Special thanks
--------------

- Thanks to Geoffrey Peck from Australia for his tests, observations and suggestions.
- Thanks to Peter Mousley from Australia for his expert advice, tests, code review, guidance and code contribution.
- Thanks to [Auric Goldfinger](https://github.com/auricgoldfinger) for his great contribution in developing the auto connect on boot feature and the detailed readme merged into above.
- Thanks to everyone who provided comments/suggestions on the [bluetooth_gnss project issues page on github](https://github.com/ykasidit/bluetooth_gnss/issues).

Authors
--------

- [Kasidit Yusuf](https://github.com/ykasidit)
- [Auric Goldfinger](https://github.com/auricgoldfinger)
- For the most up-to-date list, run `git shortlog -sne` in this git repo.

Copyright and License
---------------------

Copyright (C) 2019 Kasidit Yusuf and all respective project source code contributors.

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.
