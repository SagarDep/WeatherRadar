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

import android.app.Fragment;
import android.app.FragmentTransaction;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import com.danhasting.radar.fragments.ChooserFragment;
import com.danhasting.radar.fragments.NeedKeyFragment;
import com.danhasting.radar.fragments.SelectNWSFragment;
import com.danhasting.radar.fragments.SelectMosaicFragment;
import com.danhasting.radar.fragments.SelectWundergroundFragment;
import com.loopj.android.http.AsyncHttpClient;
import com.loopj.android.http.JsonHttpResponseHandler;
import com.loopj.android.http.RequestParams;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.TreeMap;

import cz.msebera.android.httpclient.Header;

public class SelectActivity extends MainActivity
        implements SelectNWSFragment.OnNWSSelectedListener,
            SelectMosaicFragment.OnMosaicSelectedListener,
            SelectWundergroundFragment.OnWundergroundSelectedListener,
            ChooserFragment.OnChooserSelectedListener {

    private String currentSelection = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LayoutInflater inflater = (LayoutInflater)this.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        if (inflater != null) {
            View contentView = inflater.inflate(R.layout.activity_select, drawerLayout, false);
            drawerLayout.addView(contentView, 0);
        }

        Intent intent = getIntent();
        String selection = intent.getStringExtra("selection");
        if (selection != null) launchSelectionFragment(selection);
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull final MenuItem menuItem) {
        int id = menuItem.getItemId();

        if (id == R.id.nav_nws)
            launchSelectionFragment("nws");
        else if (id == R.id.nav_mosaic)
            launchSelectionFragment("mosaic");
        else if (id == R.id.nav_wunderground)
            launchSelectionFragment("wunderground");

        super.onNavigationItemSelected(menuItem);
        return true;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (data.getBooleanExtra("from_settings", false)) {
            launchSelectionFragment(currentSelection, true);
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        Fragment fragment = getFragmentManager().findFragmentById(R.id.fragment_container);
        if (fragment instanceof ChooserFragment)
            launchSelectionFragment("wunderground");
    }

    private void launchSelectionFragment(String selection, Boolean force) {
        Fragment fragment = getFragmentManager().findFragmentById(R.id.fragment_container);

        switch (selection) {
            case "nws":
                if (!(fragment instanceof SelectNWSFragment) || force) {
                    setTitle(R.string.select_radar_image);
                    SelectNWSFragment nwsFragment = new SelectNWSFragment();
                    getFragmentManager().beginTransaction()
                            .replace(R.id.fragment_container, nwsFragment).commit();
                }
                break;

            case "mosaic":
                if (!(fragment instanceof SelectMosaicFragment) || force) {
                    setTitle(R.string.select_mosaic_image);
                    SelectMosaicFragment mosaicFragment = new SelectMosaicFragment();
                    getFragmentManager().beginTransaction()
                            .replace(R.id.fragment_container, mosaicFragment).commit();
                }
                break;

            case "wunderground":
                if (!settings.getBoolean("api_key_activated", false)) {
                    setTitle(R.string.select_wunderground_image);
                    NeedKeyFragment needKeyFragment = new NeedKeyFragment();
                    getFragmentManager().beginTransaction()
                            .replace(R.id.fragment_container, needKeyFragment).commit();
                } else {
                    if (!(fragment instanceof SelectWundergroundFragment) || force) {
                        setTitle(R.string.select_wunderground_image);
                        SelectWundergroundFragment wundergroundFragment = new SelectWundergroundFragment();
                        getFragmentManager().beginTransaction()
                                .replace(R.id.fragment_container, wundergroundFragment).commit();
                    }
                }
                break;
        }

        currentSelection = selection;
    }

    private void launchSelectionFragment(String selection) {
        launchSelectionFragment(selection, false);
    }

    private void launchChooser(TreeMap<String, String> options, Boolean loop, int distance) {
        setTitle(R.string.chooser_title);
        ChooserFragment chooserFragment = new ChooserFragment();
        getFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, chooserFragment).commit();
        getFragmentManager().executePendingTransactions();

        chooserFragment.populateList(options, loop, distance);
    }

    public void onSelected(String source, String name, String location, String type,
                           Boolean loop, Boolean enhanced, int distance) {
        Intent radarIntent = new Intent(SelectActivity.this, RadarActivity.class);

        if (name == null) name = location;

        radarIntent.putExtra("source", source);
        radarIntent.putExtra("location", location);
        radarIntent.putExtra("name", name);
        radarIntent.putExtra("type", type);
        radarIntent.putExtra("loop", loop);
        radarIntent.putExtra("enhanced", enhanced);
        radarIntent.putExtra("distance", distance);

        startActivity(radarIntent);
    }

    public void onNWSSelected(String location, String type, Boolean loop, Boolean enhanced) {
        SharedPreferences.Editor editor = settings.edit();
        editor.putString("last_nws_location", location);
        editor.putString("last_nws_type", type);
        editor.putBoolean("last_nws_loop", loop);
        editor.putBoolean("last_nws_enhanced", enhanced);
        editor.apply();

        onSelected("nws", null, location, type, loop, enhanced, 50);
    }

    public void onMosaicSelected(String location, Boolean loop) {
        SharedPreferences.Editor editor = settings.edit();
        editor.putString("last_mosaic", location);
        editor.putBoolean("last_mosaic_loop", loop);
        editor.apply();

        onSelected("mosaic", null, location, null, loop, false, 50);
    }

    public void onWundergroundSelected(final String location, final Boolean loop, final int distance) {
        final SelectWundergroundFragment fragment = (SelectWundergroundFragment)
                getFragmentManager().findFragmentById(R.id.fragment_container);

        RequestParams params = new RequestParams();
        params.put("query", location);

        AsyncHttpClient client = new AsyncHttpClient();
        String autocompleteURL = "https://autocomplete.wunderground.com/aq";

        client.get(autocompleteURL, params, new JsonHttpResponseHandler() {
            @Override
            public void onSuccess(int status, Header[] headers, JSONObject json) {
                TreeMap<String, String> options = new TreeMap<>();

                try {
                    String resultsString = json.getString("RESULTS");
                    JSONArray results = new JSONArray(resultsString);
                    for (int i = 0; i < results.length(); i++) {
                        JSONObject row = results.getJSONObject(i);
                        options.put(row.getString("name"), row.getString("zmw"));
                    }

                    if (options.size() > 1) {
                        launchChooser(options, loop, distance);

                        SharedPreferences.Editor editor = settings.edit();
                        editor.putString("last_wunderground", location);
                        editor.putBoolean("last_wunderground_loop", loop);
                        editor.putInt("last_wunderground_distance", distance);
                        editor.apply();
                    } else if (options.size() == 1) {
                        onSelected("wunderground", options.firstEntry().getKey(),
                                options.firstEntry().getValue(), "", loop, false, distance);

                        SharedPreferences.Editor editor = settings.edit();
                        editor.putString("last_wunderground", options.firstEntry().getKey());
                        editor.putBoolean("last_wunderground_loop", loop);
                        editor.putInt("last_wunderground_distance", distance);
                        editor.apply();
                    } else {
                        Toast.makeText(getApplicationContext(),
                                R.string.no_results_error, Toast.LENGTH_LONG).show();
                        fragment.enableButton();
                    }
                } catch (JSONException e) {
                    Toast.makeText(getApplicationContext(),
                            R.string.connection_error, Toast.LENGTH_LONG).show();
                    e.printStackTrace();
                    fragment.enableButton();
                }
            }

            @Override
            public void onFailure(int status, Header[] h, Throwable t, JSONObject e) {
                Toast.makeText(getApplicationContext(),
                        R.string.connection_error, Toast.LENGTH_LONG).show();
                fragment.enableButton();
            }

        });
    }

    public void onChooserSelected(String name, String location, Boolean loop, int distance) {
        SharedPreferences.Editor editor = settings.edit();
        editor.putString("last_wunderground", name);
        editor.putBoolean("last_wunderground_loop", loop);
        editor.apply();

        onSelected("wunderground", name, location, null, loop, false, distance);
    }
}