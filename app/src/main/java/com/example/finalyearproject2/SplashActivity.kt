package com.example.finalyearproject2

import android.content.Intent
import android.os.Bundle
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/*
 * SplashActivity
 * ─────────────────────────────────────────────────────────────────────────────
 * Shown for 2.2 seconds on app launch.
 *   1. Logo scales in with an overshoot bounce
 *   2. App name fades + slides up
 *   3. Tagline fades in slightly after
 *   4. Whole screen fades out → MainActivity opens
 */
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        val logo    = findViewById<ImageView>(R.id.splash_logo)
        val title   = findViewById<TextView>(R.id.splash_title)
        val tagline = findViewById<TextView>(R.id.splash_tagline)
        val root    = findViewById<android.view.View>(R.id.splash_root)

        // ── Start all views invisible ─────────────────────────────────────────
        logo.scaleX    = 0f
        logo.scaleY    = 0f
        logo.alpha     = 0f
        title.alpha    = 0f
        title.translationY = 40f
        tagline.alpha  = 0f
        tagline.translationY = 20f

        // ── Step 1: Logo bounce in (0ms → 600ms) ──────────────────────────────
        logo.animate()
            .scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(600)
            .setStartDelay(100)
            .setInterpolator(OvershootInterpolator(1.4f))
            .start()

        // ── Step 2: Title slides up + fades in (300ms → 900ms) ───────────────
        title.animate()
            .alpha(1f).translationY(0f)
            .setDuration(500)
            .setStartDelay(350)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()

        // ── Step 3: Tagline fades in (600ms → 1000ms) ────────────────────────
        tagline.animate()
            .alpha(1f).translationY(0f)
            .setDuration(450)
            .setStartDelay(550)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()

        // ── Step 4: Fade out whole screen → launch MainActivity (1800ms) ──────
        root.postDelayed({
            root.animate()
                .alpha(0f)
                .setDuration(350)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withEndAction {
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                    // Slide transition: new activity slides in from right
                    overridePendingTransition(
                        android.R.anim.fade_in,
                        android.R.anim.fade_out
                    )
                }
                .start()
        }, 1800)
    }
}
