# EnableCielo
Enable Android Auto's new UI in testing, Cielo, on a rooted device.
And yes, this is an AI-generated tool. On the one hand, I do have significant concerns about AI and do not use it for cognitive offloading. On the other hand, no one has made this tool yet. This was only tested on my primary phone, a Google Pixel 8a. 

Android Auto 18.1 already contains Google's redesigned Material 3 Expressive home screen (code name Cielo) and its home-screen widget system (code name Earth). Google keeps both switched off with server-side flags. On a rooted phone you can switch them on yourself by adding flag overrides to Google Play Services, with one command.

## What changes

Android Auto switches to its new Material 3 Expressive system UI. The map runs edge to edge, and the app rail and map buttons float over it instead of sitting in a black bar. The widget system switches on too: Android Auto starts its widget manager and loads Google's default widgets (Pixel Weather and the analog Clock).

What has been confirmed so far, on Google's Desktop Head Unit at 1280 x 720:

- The new layout appears on screen, and turning it off restores the classic layout.
- The widget manager starts and loads its default widgets (visible in Android Auto's debug dump).
- Not yet confirmed: where the widgets appear on screen. The all preset's extra dashboard flags are also untested.

## Before you start

You need a rooted phone and Android Auto 18.1. Widgets also need Android 17. Everything here was tested on a Pixel 8a running Android 17 (build CP3A.261005.005) with KernelSU and Android Auto 18.1.664444.

| Requirement | Why |
|---|---|
| Root (Magisk, KernelSU or APatch) with a working `su` | The flags live in Google Play Services' private database, which only root can write |
| Android Auto 18.1 or newer | Older builds don't contain the new layout code. Newer builds may rename or remove these flags |
| Android 17 (API 37) for widgets | Android Auto checks for API 37 before it turns widgets on. The new layout has no version check, but it was only tested on Android 17 |
| A wide car screen for widgets | Android Auto skips the widget area on narrow screens. The code checks for roughly 750 to 850 dp of width |
| A computer with ADB, or a root terminal on the phone | To run one command as root. Termux with `su` works |

### Know the risks before you start

- These are unreleased Google features. They can be unfinished or buggy, and any Android Auto or Play Services update can change or remove them.
- The tool edits Google Play Services' flag database. It saves the original values first, and disable puts them back.
- Set this up while parked. Don't test it while driving.

## Step 1: Get the aa-tweak tool

`aa-tweak` is one small Java program (about 20 KB once built). It runs as root through Android's built-in `app_process`, so nothing gets installed. Its full source is in the Source code tab, so you can read every line before you run it.

1. On a computer, install a JDK (17 or newer) and the Android SDK command-line tools with build-tools and one recent platform, such as `platforms;android-36`.

2. Save the code from the Source code tab as `AaTweak.java` and build it:

   ```bash
   javac --release 11 -cp $ANDROID_HOME/platforms/android-36/android.jar -d build AaTweak.java
   $ANDROID_HOME/build-tools/36.0.0/d8 --release --min-api 29 \
     --lib $ANDROID_HOME/platforms/android-36/android.jar --output build build/*.class
   mv build/classes.dex aa-tweak.dex
