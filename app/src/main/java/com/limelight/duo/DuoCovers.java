package com.limelight.duo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Box art for the new screens, straight from Artemide's disk cache (boxart/<pc uuid>/<app id>.png,
 * filled by the app grid in the background). While a cover is missing the placeholder shows;
 * it is checked again a few times in case the download is still running.
 */
public final class DuoCovers {
    private DuoCovers() {}

    private static final ExecutorService EXEC = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    /** Shows the cover in iv (placeholder stays visible until it is there). widthPx: target width. */
    public static void into(Context c, String pcUuid, int appId, ImageView iv, View placeholder, int widthPx) {
        if (pcUuid == null) {
            return;
        }
        final String key = pcUuid + "/" + appId + "@" + (widthPx > 200 ? "L" : "S");
        iv.setTag(key);
        Bitmap hit = CACHE.get(key);
        if (hit != null) {
            show(iv, placeholder, hit, false);
            return;
        }
        iv.setImageDrawable(null);
        iv.setVisibility(View.INVISIBLE);
        placeholder.setVisibility(View.VISIBLE);
        final File f = new File(new File(new File(c.getApplicationContext().getCacheDir(), "boxart"), pcUuid), appId + ".png");
        load(f, key, iv, placeholder, widthPx, 0);
    }

    private static void load(File f, String key, ImageView iv, View placeholder, int widthPx, int attempt) {
        EXEC.execute(() -> {
            Bitmap b = null;
            if (f.exists()) {
                try {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(f.getAbsolutePath(), o);
                    int sample = 1;
                    while (o.outWidth / (sample * 2) >= widthPx && widthPx > 0) {
                        sample *= 2;
                    }
                    o = new BitmapFactory.Options();
                    o.inSampleSize = sample;
                    b = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
                } catch (Throwable ignored) {
                }
            }
            final Bitmap bmp = b;
            MAIN.post(() -> {
                if (!key.equals(iv.getTag())) {
                    return; // the view shows another app by now
                }
                if (bmp != null) {
                    CACHE.put(key, bmp);
                    show(iv, placeholder, bmp, true);
                } else if (attempt < 5) {
                    MAIN.postDelayed(() -> {
                        if (key.equals(iv.getTag())) {
                            load(f, key, iv, placeholder, widthPx, attempt + 1);
                        }
                    }, 2000);
                }
            });
        });
    }

    private static void show(ImageView iv, View placeholder, Bitmap b, boolean fade) {
        iv.setImageBitmap(b);
        iv.setVisibility(View.VISIBLE);
        placeholder.setVisibility(View.GONE);
        if (fade) {
            iv.setAlpha(0f);
            iv.animate().alpha(1f).setDuration(180).start();
        } else {
            iv.setAlpha(1f);
        }
    }

    /** Placeholder background: a dark card washed with the accent from the top. */
    public static GradientDrawable placeholderBackground(int accent, float radiusPx) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{DuoTheme.mix(accent, DuoTheme.CARD_HIGH, 0.22f), DuoTheme.CARD});
        g.setCornerRadius(radiusPx);
        return g;
    }
}
