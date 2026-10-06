# 🚗 DrowzeFree – Driver Drowsiness Detection & Safety System

DrowzeFree is an Android-based driver safety application designed to detect driver drowsiness in real time and take safety actions when signs of fatigue are detected.

The application uses the device camera and face-landmark-based detection to monitor the driver's condition. When drowsiness is detected, the system can provide warnings and, if the driver does not respond, send an emergency SMS to a saved emergency contact.

---

## 📱 Project Overview

Driver drowsiness is one of the major causes of road accidents, especially during long-distance and night-time driving.

DrowzeFree aims to provide an affordable software-based safety solution using a smartphone camera.

The application continuously monitors the driver's face and detects signs associated with drowsiness. It also provides emergency communication and safety statistics.

---

## ✨ Features

### 👁️ Real-Time Drowsiness Detection

- Uses the smartphone camera for driver monitoring.
- Uses facial landmark detection to analyze the driver's face.
- Detects signs of driver fatigue/drowsiness.
- Provides an alert when drowsiness is detected.

### 🚨 Drowsiness Warning System

When drowsiness is detected:

1. The system detects the driver's drowsy state.
2. A warning/alert is triggered.
3. The driver gets an opportunity to respond.
4. If the driver does not respond within the configured period, the emergency procedure is triggered.

### 📩 Emergency SMS

DrowzeFree includes an emergency contact system.

Users can:

- Add emergency contacts.
- View saved contacts.
- Delete emergency contacts.
- Use the saved contact during an emergency situation.

If the driver remains unresponsive after a drowsiness warning, the application can send an emergency SMS to the configured emergency contact.

### 👥 Emergency Contact Management

The application provides a dedicated contact management screen.

Users can:

- Add contacts
- View contacts
- Delete contacts
- Manage emergency contacts used by the safety system

### 📊 Safety Report

DrowzeFree includes a Safety Report screen to provide information about the driver's safety activity.

The application maintains safety-related information that can be displayed through the report interface.

### 📈 Safety Statistics

The project also includes safety statistics functionality to help the user understand their driving/drowsiness activity.

### 🔔 Notifications

The application uses Android notifications to provide important safety-related alerts to the user.

### 📱 Background Detection Service

The application uses an Android detection service to handle the drowsiness monitoring process.

This allows the monitoring functionality to operate through a dedicated service rather than being limited only to the main screen.

---

## 🛠️ Technologies Used

### Android Development

- Kotlin
- Android Studio
- Android SDK
- Gradle

### Computer Vision / Machine Learning

- MediaPipe Face Landmarker
- Face landmark detection
- Real-time camera processing

### Android Components

- Activities
- Services
- Broadcast Receivers
- Notifications
- SMS functionality
- Runtime permissions
- Android UI layouts

---

## 🏗️ Project Architecture

```text
DrowzeFree
│
├── app/
│   └── src/
│       └── main/
│           ├── java/
│           │   └── com/example/drowsinessdetection/
│           │       ├── MainActivity.kt
│           │       ├── DetectionService.kt
│           │       ├── ContactsActivity.kt
│           │       ├── ContactsAdapter.kt
│           │       ├── Contact.kt
│           │       ├── SafetyReportActivity.kt
│           │       ├── SafetyStats.kt
│           │       ├── SmsSentReceiver.kt
│           │       └── Utils.kt
│           │
│           ├── res/
│           │   ├── drawable/
│           │   ├── layout/
│           │   ├── mipmap/
│           │   └── values/
│           │
│           └── AndroidManifest.xml
│
├── face_landmarker.task
├── gradle/
├── build.gradle
├── settings.gradle
├── gradle.properties
└── README.md
   
