/*
 * Copyright (c) 2018, Dan Hasting
 *
 * This file is part of WeatherRadar
 *
 * WeatherRadar is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * WeatherRadar is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with WeatherRadar.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.danhasting.radar;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v7.app.ActionBar;
import android.text.InputType;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;

import com.danhasting.radar.database.AppDatabase;
import com.danhasting.radar.database.Favorite;
import com.danhasting.radar.database.Source;
import com.danhasting.radar.fragments.RadarFragment;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RadarActivity extends MainActivity {

    private Source source;
    private String type;
    private String location;
    private Boolean loop;
    private Boolean enhanced;
    private int distance;

    private String sourceName;
    private String radarName;
    private Boolean needKey = true;

    private MenuItem addFavorite;
    private MenuItem removeFavorite;

    private MenuItem contextAddFavorite;
    private MenuItem contextRemoveFavorite;
    private MenuItem contextEditFavorite;

    private Timer timer;
    private Boolean refreshed = true;
    private Boolean paused = false;
    private Date lastPause;

    private RadarFragment radarFragment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Boolean fullscreen = PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean("show_fullscreen", false);
        if (fullscreen) {
            requestWindowFeature(Window.FEATURE_NO_TITLE);
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }

        super.onCreate(savedInstanceState);
        LayoutInflater inflater = (LayoutInflater)this.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        if (inflater != null) {
            View contentView = inflater.inflate(R.layout.activity_radar, drawerLayout, false);
            drawerLayout.addView(contentView, 0);
        }

        Intent intent = getIntent();
        source = (Source) intent.getSerializableExtra("source");
        type = intent.getStringExtra("type");
        location = intent.getStringExtra("location");
        loop = intent.getBooleanExtra("loop", false);
        enhanced = intent.getBooleanExtra("enhanced", false);
        distance = intent.getIntExtra("distance", 50);

        if (source == null) source = Source.NWS;
        if (type == null) type = "";
        if (location == null) location = "";


        needKey = source == Source.WUNDERGROUND && !settings.getBoolean("api_key_activated", false);
        if (needKey) {
            Boolean limited = settings.getBoolean("is_test_limit", false);
            int used = 0;
            int limit = settings.getInt("test_limit", 5);
            String message;

            if (limited) {
                used = settings.getInt("test_used", 0) + 1;

                if (used <= limit) {
                    SharedPreferences.Editor editor = settings.edit();
                    editor.putInt("test_used", used);
                    editor.apply();
                }

                message = String.format(getString(R.string.test_text_limited), used, limit);
            } else
                message = getString(R.string.test_text);

            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle(getString(R.string.test_header));
            builder.setMessage(message);

            builder.setPositiveButton(R.string.test_get_key, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    Intent browser = new Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://www.wunderground.com/weather/api/"));
                    startActivity(browser);
                }
            });
            builder.setNegativeButton(R.string.test_dismiss, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    dialog.cancel();
                }
            });

            AlertDialog dialog = builder.create();
            dialog.show();

            if (limited && used > limit)
                return;
        }

        radarFragment = new RadarFragment();
        radarFragment.setArguments(intent.getExtras());
        getFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, radarFragment).commit();

        ActionBar actionBar = getSupportActionBar();
        if (fullscreen && actionBar != null) {
            getSupportActionBar().hide();
            findViewById(R.id.radarLayout).setPadding(0, 0, 0, 0);
        }

        int index;
        switch (source) {
            case WUNDERGROUND:
                sourceName = intent.getStringExtra("name");
                if (sourceName == null) sourceName = getString(R.string.wunderground_title);
                break;
            case MOSAIC:
                index = Arrays.asList(getResources().getStringArray(R.array.mosaic_values))
                        .indexOf(location);
                sourceName = getResources().getStringArray(R.array.mosaic_names)[index];
                break;
            case NWS:
                index = Arrays.asList(getResources().getStringArray(R.array.location_values))
                        .indexOf(location);
                sourceName = getResources().getStringArray(R.array.location_names)[index];
                sourceName = sourceName.replaceAll("[^/]+/ ", "");
                break;
        }

        if (intent.getBooleanExtra("favorite", false)) {
            radarName = intent.getStringExtra("name");
            currentFavorite = intent.getIntExtra("favoriteID", -1);
        } else
            radarName = sourceName;

        if (radarName != null)
            setTitle(radarName);

        scheduleRefresh();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        Bundle extras = intent.getExtras();

        if (extras != null) {
            Intent newIntent = new Intent(this, RadarActivity.class);
            newIntent.putExtras(extras);
            startActivity(newIntent);
            finish();
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        paused = false;

        if (lastPause != null) {
            Date now = Calendar.getInstance().getTime();
            long seconds = (now.getTime() - lastPause.getTime()) / 1000;

            if (seconds > 60 * 5)
                refreshed = false;
        }

        // Mosaic loops are large, don't auto-refresh
        if (!needKey && !refreshed && !(loop && source == Source.MOSAIC) && autoRefresh()) {
            if (radarFragment != null) radarFragment.refreshRadar();
            scheduleRefresh();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        paused = true;
        lastPause = Calendar.getInstance().getTime();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (data.getBooleanExtra("from_settings", false) && !needKey)
            recreate();
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        initializeMenu(menu, true);
        super.onCreateContextMenu(menu, v, menuInfo);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        initializeMenu(menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        itemSelected(item);
        return super.onContextItemSelected(item);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        itemSelected(item);
        return super.onOptionsItemSelected(item);
    }

    private void initializeMenu(Menu menu, final Boolean contextMenu) {
        getMenuInflater().inflate(R.menu.radar_actions, menu);

        if (contextMenu) {
            contextAddFavorite = menu.findItem(R.id.action_add_favorite);
            contextRemoveFavorite = menu.findItem(R.id.action_remove_favorite);
            contextEditFavorite = menu.findItem(R.id.action_edit_favorite);
        } else {
            addFavorite = menu.findItem(R.id.action_add_favorite);
            removeFavorite = menu.findItem(R.id.action_remove_favorite);
            MenuItem editFavorite = menu.findItem(R.id.action_edit_favorite);
            editFavorite.setVisible(false);
        }
        MenuItem refresh = menu.findItem(R.id.action_refresh);

        // Don't allow user to add a favorite or refresh if they are using the test api key
        if (needKey) {
            refresh.setVisible(false);
            return;
        }

        ExecutorService service =  Executors.newSingleThreadExecutor();
        service.submit(new Runnable() {
            @Override
            public void run() {
                AppDatabase database = AppDatabase.getAppDatabase(getApplication());
                final List<Favorite> favorites = database.favoriteDao().findByData(
                        source.getInt(), location, type, loop, enhanced, distance);

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (favorites.size() > 0) {
                            radarName = favorites.get(0).getName();
                            setTitle(radarName);

                            if (contextMenu) {
                                hideItem(contextAddFavorite);
                                showItem(contextRemoveFavorite);
                                showItem(contextEditFavorite);
                            } else {
                                hideItem(addFavorite);
                                showItem(removeFavorite);
                            }

                            //Don't set currentFavorite if NeedKeyFragment showing so user can refresh
                            if (!needKey)
                                currentFavorite = favorites.get(0).getUid();
                        } else {
                            if (contextMenu) {
                                hideItem(contextRemoveFavorite);
                                hideItem(contextEditFavorite);
                                showItem(contextAddFavorite);
                            } else {
                                hideItem(removeFavorite);
                                showItem(addFavorite);
                            }
                        }
                    }
                });
            }
        });
    }

    private void initializeMenu(Menu menu) {
        initializeMenu(menu, false);
    }

    private void hideItem(MenuItem item) {
        if (item != null) item.setVisible(false);
    }

    private void showItem(MenuItem item) {
        if (item != null) item.setVisible(true);
    }

    private void itemSelected(MenuItem item) {
        int id = item.getItemId();

        switch (id) {
            case R.id.action_add_favorite:
                addFavoriteDialog();
                break;
            case R.id.action_remove_favorite:
                removeFavoriteDialog();
                break;
            case R.id.action_edit_favorite:
                editFavoriteDialog();
                break;
            case R.id.action_refresh:
                refreshRadar();
                break;
        }
    }

    private AlertDialog favoriteDialog(String title, String button) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(title);

        EditText input = new EditText(this);
        input.setId(R.id.dialog_input);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        if (radarName != null) input.setText(radarName);
        builder.setView(input);

        builder.setPositiveButton(button, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                // Do nothing as we will override below
            }
        });
        builder.setNegativeButton(R.string.button_cancel, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.cancel();
            }
        });

        AlertDialog dialog = builder.create();
        dialog.show();

        return dialog;
    }

    private void addFavoriteDialog() {
        final AlertDialog dialog = favoriteDialog(getString(R.string.action_add_favorite),
                getString(R.string.button_add));
        final EditText input = dialog.findViewById(R.id.dialog_input);

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v)
            {
                final String name = input.getText().toString();

                ExecutorService service =  Executors.newSingleThreadExecutor();
                service.submit(new Runnable() {
                    @Override
                    public void run() {
                        AppDatabase database = AppDatabase.getAppDatabase(getApplication());
                        Boolean exists = database.favoriteDao().findByName(name) != null;

                        if (name.equals("")) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    input.setError(getString(R.string.empty_name_error));
                                }
                            });
                        } else if (exists) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    input.setError(getString(R.string.already_exists_error));
                                }
                            });
                        } else {
                            Favorite favorite = new Favorite();
                            favorite.setSource(source.getInt());
                            favorite.setName(name);
                            favorite.setLocation(location);
                            favorite.setType(type);
                            favorite.setLoop(loop);
                            favorite.setEnhanced(enhanced);
                            favorite.setDistance(distance);
                            database.favoriteDao().insertAll(favorite);

                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    hideItem(addFavorite);
                                    hideItem(contextAddFavorite);
                                    showItem(removeFavorite);
                                    showItem(contextRemoveFavorite);
                                    showItem(contextEditFavorite);

                                    radarName = name;
                                    setTitle(radarName);
                                    dialog.dismiss();
                                }
                            });
                        }
                    }
                });
            }
        });
    }

    private void editFavoriteDialog() {
        final AlertDialog dialog = favoriteDialog(getString(R.string.action_edit_favorite),
                getString(R.string.button_edit));
        final EditText input = dialog.findViewById(R.id.dialog_input);

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v)
            {
                final String name = input.getText().toString();

                ExecutorService service =  Executors.newSingleThreadExecutor();
                service.submit(new Runnable() {
                    @Override
                    public void run() {
                        AppDatabase database = AppDatabase.getAppDatabase(getApplication());
                        Boolean exists = database.favoriteDao().findByName(name) != null;

                        if (name.equals("")) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    input.setError(getString(R.string.empty_name_error));
                                }
                            });
                        } else if (exists) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    input.setError(getString(R.string.already_exists_error));
                                }
                            });
                        } else {
                            Favorite favorite = database.favoriteDao().findByName(radarName);

                            if (favorite != null) {
                                favorite.setName(name);
                                database.favoriteDao().updateFavorites(favorite);

                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        radarName = name;
                                        setTitle(radarName);
                                    }
                                });
                            }

                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    dialog.dismiss();
                                }
                            });
                        }
                    }
                });
            }
        });
    }

    private void removeFavoriteDialog() {
        DialogInterface.OnClickListener dialogListener = new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (which == DialogInterface.BUTTON_POSITIVE) {
                    ExecutorService service =  Executors.newSingleThreadExecutor();
                    service.submit(new Runnable() {
                        @Override
                        public void run() {
                            AppDatabase database = AppDatabase.getAppDatabase(getApplication());
                            List<Favorite> favorites = database.favoriteDao().findByData(
                                    source.getInt(), location, type, loop, enhanced, distance);

                            for (Favorite favorite : favorites) {
                                database.favoriteDao().delete(favorite);
                            }

                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    showItem(addFavorite);
                                    showItem(contextAddFavorite);
                                    hideItem(removeFavorite);
                                    hideItem(contextRemoveFavorite);
                                    hideItem(contextEditFavorite);

                                    radarName = sourceName;
                                    setTitle(radarName);
                                }
                            });
                        }
                    });
                }
            }
        };

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setMessage(R.string.confirm_favorite_removal)
                .setPositiveButton(getString(R.string.button_yes), dialogListener)
                .setNegativeButton(getString(R.string.button_no), dialogListener)
                .show();
    }

    private void refreshRadar() {
        if (!refreshed) {
            if (radarFragment != null) radarFragment.refreshRadar();
            scheduleRefresh();
        } else {
            DialogInterface.OnClickListener dialogListener = new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    if (which == DialogInterface.BUTTON_POSITIVE) {
                        if (radarFragment != null) radarFragment.refreshRadar();
                        scheduleRefresh();
                    }
                }
            };

            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setMessage(R.string.confirm_refresh)
                    .setPositiveButton(getString(R.string.button_yes), dialogListener)
                    .setNegativeButton(getString(R.string.button_no), dialogListener)
                    .show();
        }
    }

    private Boolean autoRefresh() {
        String refresh = settings.getString("auto_refresh",
                getString(R.string.wifi_toggle_default));

        return (refresh.equals("always") || (refresh.equals("wifi") && onWifi()));
    }

    private void scheduleRefresh() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }

        timer = new Timer();
        refreshed = true;

        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                refreshed = false;

                if (autoRefresh() && !paused) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (!needKey && radarFragment != null) radarFragment.refreshRadar();
                            scheduleRefresh();
                        }
                    });
                }
            }
        }, 1000 * 60 * 5);
    }
}
