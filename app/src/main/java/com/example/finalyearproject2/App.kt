package com.example.finalyearproject2

import android.app.Application
import com.google.firebase.database.FirebaseDatabase

/*
 * App — Custom Application class
 * ─────────────────────────────────────────────────────────────────────────────
 * Called by Android before ANY Activity, Service, or WorkManager task starts.
 *
 * WHY THIS IS NEEDED:
 *   FirebaseDatabase.setPersistenceEnabled(true) must be called exactly once,
 *   before any FirebaseDatabase.getInstance() call anywhere in the app.
 *   If it is called inside an Activity (like MainActivity.onCreate), there is
 *   a race condition — WorkManager or a background task may call getInstance()
 *   first, causing an IllegalStateException and crashing on the splash screen.
 *
 *   Putting it here guarantees it runs first, always, with no race condition.
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()

        // Enable Firebase offline disk cache — called once here so it is always
        // set before any other code in the app touches FirebaseDatabase.
        FirebaseDatabase.getInstance(
            "https://final-year-project-a75d4-default-rtdb.firebaseio.com/"
        ).setPersistenceEnabled(true)
    }
}
