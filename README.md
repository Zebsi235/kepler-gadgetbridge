Kepler Gadgetbridge
===================

The Android app for the **Kepler F91** watch ([kepler.watch](https://kepler.watch)).
It is a fork of [Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge) with
support for the Kepler watch added: notifications, time sync, weather, music control,
alarms, find my phone, screen order, image mode, brightness and sleep times.

## Download

**[Download the latest release](https://github.com/Zebsi235/kepler-gadgetbridge/releases/latest)**
and install the `.apk` file from it.

1. Open the link on your Android phone (Android 6.0 or newer).
2. Download the `.apk` under **Assets**.
3. Open it and allow installing apps from your browser when Android asks.
4. Open the app, tap **+**, pick your Kepler watch and allow the pairing request.

Already using Gadgetbridge from F-Droid? This app uses the same app ID, so Android
won't install it over that one. Export your data first (Settings → Data management),
then uninstall Gadgetbridge and install this app.

Releases marked *Pre-release* are test builds. Use the one marked **Latest**.

Questions or problems: the [Kepler Discord](https://discord.gg/DDH4peupMk) (#help)
or the [issues](https://github.com/Zebsi235/kepler-gadgetbridge/issues) here.

## License and credit

This app is licensed under the GNU AGPLv3, like Gadgetbridge. All credit for
Gadgetbridge itself goes to its authors listed below. For the original app with
support for many other devices, see [gadgetbridge.org](https://gadgetbridge.org).

## About Gadgetbridge

Gadgetbridge is an Android application which will allow you to use your
Bluetooth gadgets (mostly wearables like smart watches, but many more) without the vendor's closed source application
and without the need to create an account and transmit any of your data to the
vendor's servers.

### Supported Devices

Please see the [Gadgets](https://gadgetbridge.org/gadgets/) page on the website for a complete list of supported devices.

### Features

Please see the [Features](https://gadgetbridge.org/basics/features/) page on the website.

### Authors
#### Core Team (in order of first code contribution)

* Andreas Shimokawa
* Carsten Pfeiffer
* Daniele Gobbetti
* Petr Vaněk

#### Additional contributors
* João Paulo Barraca (HPlus)
* Vitaly Svyastyn (NO.1 F1)
* Sami Alaoui (Teclast H30)
* "ladbsoft" (XWatch)
* Sebastian Kranz (ZeTime)
* Vadim Kaushan (ID115)
* "maxirnilian" (Lenovo Watch 9)
* "ksiwczynski", "mkusnierz", "mamutcho" (Lenovo Watch X Plus)
* Andreas Böhler (Casio)
* Jean-François Greffier (Mi Scale 2)
* Johannes Schmitt (BFH-16)
* Lukas Schwichtenberg (Makibes HR3)
* Daniel Dakhno (Fossil Q Hybrid, Fossil Hybrid HR)
* Gordon Williams (Bangle.js)
* Pavel Elagin (JYou Y5)
* Taavi Eomäe (iTag)
* Erik Bloß (TLW64)
* Yukai Li (Lefun)
* José Rebelo (Roidmi, Sony Headphones, Miband 7)
* Arjan Schrijver (Fossil Hybrid HR watchfaces)

### Contribute

See CONTRIBUTING.md for the contribution policy of Gadgetbridge.

Contributions are welcome, be it feedback, bug reports, documentation, translation, research or code. Feel free to work
on any of the open [issues](https://codeberg.org/Freeyourgadget/Gadgetbridge/issues);
just leave a comment that you're working on one to avoid duplicated work.

[Developer documentation](https://gadgetbridge.org/internals/development/project-overview/) - [Support for a new Device](https://gadgetbridge.org/internals/topics/support/) - [New Device Tutorial](https://gadgetbridge.org/internals/development/new-gadget/)

Translations can be contributed via https://hosted.weblate.org/projects/freeyourgadget/gadgetbridge/

### Community

If you would like to get in touch with other Gadgetbridge users and developers outside of Codeberg, you can do so via:
* Matrix: [`#gadgetbridge:matrix.org`](https://matrix.to/#/#gadgetbridge:matrix.org)

### Do you have further questions or feedback?

Feel free to open an issue on our issue tracker, but please:
- do not use the issue tracker as a forum, do not ask for ETAs and read the issue conversation before posting
- use the search functionality to ensure that your question wasn't already answered. Don't forget to check the **closed** issues as well!
- remember that this is a community project, people are contributing in their free time because they like doing so: don't take the fun away! Be kind and constructive.
- Do not ask for help regarding your own projects, unless they are Gadgetbridge related

## Having problems with the Kepler app?

0. Phone crashing during device discovery? Disable Privacy Guard (or similarly named functionality) during discovery.
1. Open Gadgetbridge's settings and check the option to write log files
2. Reproduce the problem you encountered
3. Check the logfile at /sdcard/Android/data/nodomain.freeyourgadget.gadgetbridge/files/gadgetbridge.log
4. File an issue at https://github.com/Zebsi235/kepler-gadgetbridge/issues or ask in the [Kepler Discord](https://discord.gg/DDH4peupMk), and attach the logfile if you can

Please don't report problems with this fork on the upstream Gadgetbridge tracker.

Alternatively you may use the standard logcat functionality to access the log.

## Code Licenses

* Gadgetbridge is licensed under the [AGPLv3](LICENSE)
* Files in app/src/main/java/net/osmand/ and app/src/main/aidl/net/osmand/ are taken from the [OsmAnd](https://osmand.net/) project, licensed under the GPLv3 by OsmAnd BV
* Files in app/src/main/java/org/bouncycastle are taken from the [Bouncy Castle](https://www.bouncycastle.org/java.html) project, licensed under the MIT license by The Legion of the Bouncy Castle Inc.
* Files in app/src/main/java/com/android/nQuant are taken from the [nQuant.android](https://github.com/mcychan/nQuant.android/) project, licensed under the Apache license by Miller Cy Chan
* Files in app/src/main/java/lineageos/ are taken from the [LineageOS](https://lineageos.org/) platform (formerly CyanogenMod), licensed under the Apache license by The CyanogenMod Project and LineageOS contributors
* Files in app/src/main/java/org/concentus are taken from the [Concentus](https://github.com/lostromb/concentus) project, licensed under the BSD-3 license by various holding parties
* Files in GBDaoGenerator/src/de/greenrobot are taken from the [greenDAO](https://codeberg.org/Freeyourgadget/greenDAO) project (Gadgetbridge's fork), licensed under the GPLv3 by Markus Junginger, greenrobot
* File app/src/main/java/nodomain/freeyourgadget/gadgetbridge/util/SearchPreferenceHighlighter.java is taken from [SearchPreference](https://github.com/ByteHamster/SearchPreference), licensed under the MIT license by ByteHamster
