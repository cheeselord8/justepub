# JustEpub

A small, offline EPUB reader for Android. JustEpub keeps a local library of the EPUB files you select, opens their chapters in a quiet dark reading view, and remembers your place and preferred text size.

## Features

- Import one or more `.epub` files using Android's document picker.
- Keep a library of selected files without requesting broad storage access.
- Read EPUB 2 and EPUB 3 text with a black interface and adjustable text size.
- Resume each book at the same text position, including after relaunching the app or changing text size.
- Display embedded PNG, JPEG, GIF, WebP, and BMP illustrations inline while reading.
- Remove a book from the library by long-pressing it.
- No account, network permission, analytics, ads, or animations.

## Requirements

- Android 6.0 (API 23) or later.
- Android 16 is supported (compile and target API 36).

## Build

Install JDK 17, Gradle 8.11.1, Android SDK Platform 36, and Android Build Tools 36.0.0, then run:

```shell
gradle assembleDebug
```

The installable debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Android Studio can also open this project directly.

The debug APK is signed with the local Android debug key. Use a separately managed signing key for a Play Store or production release.

## Local file access

JustEpub only reads EPUB files selected in the system document picker. The app stores the selected document references and reader position on the device; removing an item from the library does not delete the original file.

## License

MIT. See [LICENSE](LICENSE).
