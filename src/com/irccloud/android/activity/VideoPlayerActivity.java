/*
 * Copyright (c) 2015 IRCCloud, Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.irccloud.android.activity;

import android.Manifest;
import android.app.ActivityManager;
import android.app.DownloadManager;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PorterDuff;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.text.format.DateUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.ShareActionProvider;
import androidx.appcompat.widget.Toolbar;
import androidx.browser.customtabs.CustomTabsClient;
import androidx.browser.customtabs.CustomTabsServiceConnection;
import androidx.browser.customtabs.CustomTabsSession;
import androidx.core.app.ActivityCompat;
import androidx.core.app.ShareCompat;
import androidx.core.view.MenuItemCompat;

import com.irccloud.android.NetworkConnection;
import com.irccloud.android.R;
import com.irccloud.android.ShareActionProviderHax;

import org.chromium.customtabsclient.shared.CustomTabsHelper;

public class VideoPlayerActivity extends BaseActivity implements ShareActionProviderHax.OnShareActionProviderSubVisibilityChangedListener {
    private View controls;
    private VideoView video;
    private ProgressBar mProgress;
    private TextView time_current, time;
    private SeekBar seekBar;
    private ImageButton rew, pause, play, ffwd;
    private Toolbar toolbar;
    private Handler handler = new Handler();
    private ShareActionProviderHax share;
    private String mVideoURL = null;

    private Runnable mHideRunnable = new Runnable() {
        @Override
        public void run() {
            if(video != null && video.isPlaying()) {
                getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
            }
        }
    };
    private Runnable mUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            handler.removeCallbacks(this);
            if(video.isPlaying()) {
                play.setVisibility(View.GONE);
                pause.setVisibility(View.VISIBLE);
            } else {
                play.setVisibility(View.VISIBLE);
                pause.setVisibility(View.GONE);
            }

            if(video.getDuration() > 0) {
                time_current.setText(DateUtils.formatElapsedTime(video.getCurrentPosition() / 1000));
                time.setText(DateUtils.formatElapsedTime(video.getDuration() / 1000));
                seekBar.setIndeterminate(false);
                seekBar.setProgress(video.getCurrentPosition());
                seekBar.setMax(video.getDuration());
                seekBar.setSecondaryProgress(video.getBufferPercentage());
            } else {
                seekBar.setIndeterminate(true);
                time.setText("--:--");
                time_current.setText("--:--");
            }
            handler.postDelayed(this, 250);
        }
    };

    private Runnable rewindRunnable = new Runnable() {
        @Override
        public void run() {
            int position = video.getCurrentPosition() - 500;
            if(position < 0)
                position = 0;
            video.seekTo(position);
            handler.postDelayed(this, 250);
            handler.post(mUpdateRunnable);
        }
    };

    private Runnable ffwdRunnable = new Runnable() {
        @Override
        public void run() {
            int position = video.getCurrentPosition() + 1000;
            if(position > video.getDuration())
                position = video.getDuration();
            video.seekTo(position);
            handler.postDelayed(this, 250);
            handler.post(mUpdateRunnable);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTheme(R.style.ImageViewerTheme);
        if (savedInstanceState == null)
            overridePendingTransition(R.anim.slide_in_right, R.anim.fade_out);
        setContentView(R.layout.activity_video_player);
        toolbar = findViewById(R.id.toolbar);
        try {
            setSupportActionBar(toolbar);
        } catch (Throwable t) {
        }
        Bitmap cloud = BitmapFactory.decodeResource(getResources(), R.drawable.splash_logo);
        if(cloud != null) {
            setTaskDescription(new ActivityManager.TaskDescription(getResources().getString(R.string.app_name), cloud, getResources().getColor(android.R.color.black)));
        }
        getWindow().setStatusBarColor(getResources().getColor(android.R.color.black));
        getWindow().setNavigationBarColor(getResources().getColor(android.R.color.black));
        getWindow().getDecorView().setSystemUiVisibility(getWindow().getDecorView().getSystemUiVisibility() &~ View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(new View.OnSystemUiVisibilityChangeListener() {
            @Override
            public void onSystemUiVisibilityChange(int visibility) {
                if ((visibility & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0) {
                    toolbar.setAlpha(0);
                    toolbar.animate().alpha(1);
                    controls.setAlpha(0);
                    controls.animate().alpha(1);
                    hide_actionbar();
                } else {
                    toolbar.setAlpha(1);
                    toolbar.animate().alpha(0);
                    controls.setAlpha(1);
                    controls.animate().alpha(0);
                }
            }
        });

        controls = findViewById(R.id.controls);
        mProgress = findViewById(R.id.progress);

        rew = findViewById(R.id.rew);
        rew.setEnabled(false);
        rew.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
                if(motionEvent.getAction() == MotionEvent.ACTION_DOWN) {
                    handler.removeCallbacks(mHideRunnable);
                    handler.post(rewindRunnable);
                }
                if(motionEvent.getAction() == MotionEvent.ACTION_UP) {
                    handler.removeCallbacks(rewindRunnable);
                    hide_actionbar();
                }
                return false;
            }
        });
        play = findViewById(R.id.play);
        play.setEnabled(false);
        play.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                video.start();
                handler.post(mUpdateRunnable);
                hide_actionbar();
            }
        });
        pause = findViewById(R.id.pause);
        pause.setEnabled(false);
        pause.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                video.pause();
                handler.post(mUpdateRunnable);
                handler.removeCallbacks(mHideRunnable);
            }
        });
        ffwd = findViewById(R.id.ffwd);
        ffwd.setEnabled(false);
        ffwd.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
                if(motionEvent.getAction() == MotionEvent.ACTION_DOWN) {
                    handler.removeCallbacks(mHideRunnable);
                    handler.post(ffwdRunnable);
                }
                if(motionEvent.getAction() == MotionEvent.ACTION_UP) {
                    handler.removeCallbacks(ffwdRunnable);
                    hide_actionbar();
                }
                return false;
            }
        });
        time_current = findViewById(R.id.time_current);
        time = findViewById(R.id.time);
        seekBar = findViewById(R.id.mediacontroller_progress);
        seekBar.setEnabled(false);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if(fromUser) {
                    video.seekTo(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                if(video.isPlaying())
                    video.pause();
                handler.removeCallbacks(mHideRunnable);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                hide_actionbar();
            }
        });

        video = findViewById(R.id.video);
        video.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
                if((getWindow().getDecorView().getSystemUiVisibility() & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0) {
                    getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
                } else {
                    getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
                    hide_actionbar();
                }
                return false;
            }
        });
        video.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mediaPlayer) {
                mediaPlayer.start();
                mProgress.setVisibility(View.GONE);
                rew.setEnabled(true);
                pause.setEnabled(true);
                play.setEnabled(true);
                ffwd.setEnabled(true);
                seekBar.setEnabled(true);
                handler.post(mUpdateRunnable);
                hide_actionbar();
            }
        });
        video.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mediaPlayer) {
                video.pause();
                video.seekTo(video.getDuration());
                handler.removeCallbacks(mHideRunnable);
                getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            }
        });
        video.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mediaPlayer, int i, int i1) {
                AlertDialog d = new AlertDialog.Builder(VideoPlayerActivity.this)
                        .setTitle("Playback Failed")
                        .setMessage("An error occured while trying to play this video")
                        .setPositiveButton("Download", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialogInterface, int i) {
                                if(Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && ActivityCompat.checkSelfPermission(VideoPlayerActivity.this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                                    ActivityCompat.requestPermissions(VideoPlayerActivity.this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,Manifest.permission.WRITE_EXTERNAL_STORAGE},0);
                                } else {
                                    DownloadManager d = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                                    if (d != null) {
                                        String uri = getIntent().getDataString().replace(getResources().getString(R.string.VIDEO_SCHEME), "http");
                                        uri = "http://" + uri.substring(8);
                                        DownloadManager.Request r = new DownloadManager.Request(Uri.parse(uri));
                                        r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                                        r.allowScanningByMediaScanner();
                                        d.enqueue(r);
                                    }
                                }
                            }
                        })
                        .setNegativeButton("Close", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialogInterface, int i) {
                                finish();
                            }
                        })
                        .create();
                if(!isFinishing())
                    d.show();
                return true;
            }
        });

        if (getIntent() != null && getIntent().getDataString() != null) {
            Uri url = Uri.parse(getIntent().getDataString().replace(getResources().getString(R.string.VIDEO_SCHEME), "http"));
            video.setVideoURI(url);
        } else {
            finish();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        handler.post(mUpdateRunnable);
    }

    @Override
    protected void onStop() {
        super.onStop();
        try {
            unbindService(mCustomTabsConnection);
        } catch (Exception e) {
        }
        handler.removeCallbacks(mUpdateRunnable);
    }

    CustomTabsSession mCustomTabsSession = null;
    CustomTabsServiceConnection mCustomTabsConnection = new CustomTabsServiceConnection() {
        @Override
        public void onCustomTabsServiceConnected(ComponentName name, CustomTabsClient client) {
            try {
                if (client != null) {
                    client.warmup(0);
                    mCustomTabsSession = client.newSession(null);
                    if (mCustomTabsSession != null)
                        mCustomTabsSession.mayLaunchUrl(Uri.parse(getIntent().getDataString().replace(getResources().getString(R.string.VIDEO_SCHEME), "http")), null, null);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {

        }
    };

    @Override
    protected void onStart() {
        super.onStart();

        try {
            String packageName = CustomTabsHelper.getPackageNameToUse(this);
            if (packageName != null && packageName.length() > 0)
                CustomTabsClient.bindCustomTabsService(this, packageName, mCustomTabsConnection);
        } catch (Exception e) {
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(mHideRunnable);
        try {
            video.stopPlayback();
        } catch (Exception e) {
        }
        if(share != null) {
            share.setOnShareTargetSelectedListener(null);
            share.onShareActionProviderSubVisibilityChangedListener = null;
            share.setSubUiVisibilityListener(null);
            share.setVisibilityListener(null);
        }
    }

    private void hide_actionbar() {
        handler.removeCallbacks(mHideRunnable);
        handler.postDelayed(mHideRunnable, 3000);
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        overridePendingTransition(R.anim.fade_in, R.anim.slide_out_right);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.activity_videoplayer, menu);

        MenuItem shareItem = menu.findItem(R.id.action_share);
        if(shareItem != null && shareItem.getIcon() != null)
            shareItem.getIcon().mutate().setColorFilter(0xFFCCCCCC, PorterDuff.Mode.SRC_ATOP);

        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        handler.removeCallbacks(mHideRunnable);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            overridePendingTransition(R.anim.fade_in, R.anim.slide_out_right);
            return true;
        } else if (item.getItemId() == R.id.action_download) {
            if(Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,Manifest.permission.WRITE_EXTERNAL_STORAGE},0);
            } else {
                AsyncTask.execute(new Runnable() {
                    @Override
                    public void run() {
                        DownloadManager d = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                        if (d != null) {
                            String uri = getIntent().getDataString().replace(getResources().getString(R.string.VIDEO_SCHEME), "http");
                            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(uri));
                            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_MOVIES, getIntent().getData().getLastPathSegment());
                            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                            r.allowScanningByMediaScanner();
                            d.enqueue(r);
                        }
                    }
                });
            }
            return true;
        } else if (item.getItemId() == R.id.action_copy) {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            android.content.ClipData clip = android.content.ClipData.newRawUri("IRCCloud Video URL", Uri.parse(getIntent().getDataString().replace(getResources().getString(R.string.VIDEO_SCHEME), "http")));
            clipboard.setPrimaryClip(clip);
            Toast.makeText(VideoPlayerActivity.this, "Link copied to clipboard", Toast.LENGTH_SHORT).show();
        } else if(item.getItemId() == R.id.action_share) {
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TEXT, getIntent().getDataString().replace(getResources().getString(R.string.VIDEO_SCHEME), "http"));
            intent.putExtra(ShareCompat.EXTRA_CALLING_PACKAGE, getPackageName());
            intent.putExtra(ShareCompat.EXTRA_CALLING_ACTIVITY, getPackageManager().getLaunchIntentForPackage(getPackageName()).getComponent());
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(Intent.createChooser(intent, "Share Video"));
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onShareActionProviderSubVisibilityChanged(boolean visible) {
        if (visible) {
            handler.removeCallbacks(mHideRunnable);
        } else {
            hide_actionbar();
        }
    }

    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (grantResults.length == 2 && grantResults[0] == PackageManager.PERMISSION_GRANTED && grantResults[1] == PackageManager.PERMISSION_GRANTED) {
            DownloadManager d = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (d != null) {
                DownloadManager.Request r = new DownloadManager.Request(Uri.parse(getIntent().getDataString().replace(getResources().getString(R.string.VIDEO_SCHEME), "http")));
                r.setDestinationInExternalPublicDir(Environment.DIRECTORY_MOVIES, getIntent().getData().getLastPathSegment());
                r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                r.allowScanningByMediaScanner();
                d.enqueue(r);
            }
        } else {
            Toast.makeText(this, "Unable to download: permission denied", Toast.LENGTH_SHORT).show();
        }
    }
}
