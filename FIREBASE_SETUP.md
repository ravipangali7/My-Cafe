# Firebase Setup for Flutter Native FCM

This guide explains how to set up Firebase Cloud Messaging (FCM) for the Flutter app to receive push notifications.

## Prerequisites

- Firebase account (create one at https://console.firebase.google.com/)
- Flutter SDK installed
- Android Studio or VS Code with Flutter extensions

## Step 1: Create Firebase Project

1. Go to [Firebase Console](https://console.firebase.google.com/)
2. Click "Add project" or select an existing project
3. Follow the setup wizard to create your project
4. Enable Google Analytics (optional but recommended)

## Step 2: Add Android App to Firebase

1. In Firebase Console, go to **Project Settings** (gear icon)
2. Navigate to **General** tab
3. Scroll down to **Your apps** section
4. Click the Android icon to add an Android app
5. Enter the following:
   - **Package name**: `com.example.my_cafe` (must match your app's package name)
   - **App nickname**: My Cafe (optional)
   - **Debug signing certificate SHA-1**: (optional, for now)
6. Click **Register app**

## Step 3: Download google-services.json

1. After registering the app, download the `google-services.json` file
2. Place it in: `My-Cafe/android/app/google-services.json`
3. **Important**: Make sure the file is in the `app` directory, not the root `android` directory

## Step 4: Enable Cloud Messaging API

1. In Firebase Console, go to **Project Settings** > **Cloud Messaging** tab
2. Enable **Cloud Messaging API (Legacy)** if not already enabled
3. Note the **Server key** (you'll need this for Django backend if not already configured)

## Step 5: Install Dependencies

Run the following command in the `My-Cafe` directory:

```bash
flutter pub get
```

This will install:
- `firebase_core`
- `firebase_messaging`
- `http`

## Step 6: Build and Run

1. Connect an Android device or start an emulator
2. Run the app:
   ```bash
   flutter run
   ```

## Step 7: Verify Setup

1. Open the app and navigate to the dashboard
2. Check the console logs for:
   - "FCM Service initialized successfully"
   - "FCM token retrieved: [token]"
   - "Received user phone from React: [phone]"
   - "FCM token successfully sent to Django"

## How It Works

1. **App Startup**: Firebase is initialized when the app starts
2. **Token Retrieval**: FCM token is obtained from Firebase
3. **Dashboard Detection**: When user navigates to `/dashboard`, Flutter detects it
4. **Phone Retrieval**: Flutter requests user phone from React webview
5. **Token Registration**: Flutter sends FCM token + phone to Django API
6. **Database Storage**: Django saves the token associated with the user's phone

## Troubleshooting

### Issue: FCM token is null

**Solution:**
- Check that `google-services.json` is in the correct location
- Verify package name matches in Firebase Console and `build.gradle.kts`
- Check Android logs for Firebase initialization errors
- Ensure device has Google Play Services installed

### Issue: Token not sent to Django

**Solution:**
- Check network connectivity
- Verify Django server is running and accessible
- Check Flutter console for API errors
- Verify user is logged in on the dashboard page

### Issue: User phone not received

**Solution:**
- Ensure user is logged in on the dashboard
- Check React console for errors
- Verify JavaScript channel communication is working
- Check Flutter console for phone retrieval errors

## Testing Notifications

Once setup is complete, you can test notifications by:

1. Using Django admin or API to send a test notification
2. Using Firebase Console > Cloud Messaging > Send test message
3. Using the existing FCM service in Django backend

## Notes

- The FCM token is automatically refreshed by Firebase when needed
- Multiple devices per user are supported (each device gets its own token)
- Tokens are stored in the database and can be used to send notifications
- The endpoint `/api/fcm-token-by-phone/` is public but validates phone exists
