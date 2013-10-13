/*
 * FlightIntel for Pilots
 *
 * Copyright 2011-2013 Nadeem Hasan <nhasan@nadmm.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>. 
 */

package com.nadmm.airports.afd;

import java.util.ArrayList;
import java.util.Date;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.app.Fragment;
import android.support.v4.view.PagerTabStrip;
import android.support.v4.view.ViewPager;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.nadmm.airports.DatabaseManager.LocationColumns;
import com.nadmm.airports.PreferencesActivity;
import com.nadmm.airports.R;
import com.nadmm.airports.utils.GeoUtils;
import com.nadmm.airports.utils.TabsAdapter;
import com.nadmm.airports.wx.NearbyWxFragment;

public class NearbyFragment extends AfdFragmentBase {

    private LocationListener mLocationListener;
    private LocationManager mLocationManager;
    private Location mLastLocation;
    private TabsAdapter mTabsAdapter;
    private ArrayList<LocationListener> mLocationListeners;

    private final long TOO_OLD = 5*DateUtils.MINUTE_IN_MILLIS;

    @Override
    public void onCreate( Bundle savedInstanceState ) {
        super.onCreate( savedInstanceState );

        mLocationListener = new AirportsLocationListener();
        mLocationManager = (LocationManager) getActivity().getSystemService(
                Context.LOCATION_SERVICE );
    }

    @Override
    public View onCreateView( LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState ) {
        View view = inflater.inflate( R.layout.fragment_pager_layout, container, false );
        view.setKeepScreenOn( true );
        return view;
    }

    @Override
    public void onActivityCreated( Bundle savedInstanceState ) {
        super.onActivityCreated( savedInstanceState );

        Location location = getLastKnownGoodLocation();

        SharedPreferences prefs =
                PreferenceManager.getDefaultSharedPreferences( getActivity() );
        int radius = Integer.valueOf( prefs.getString(
                PreferencesActivity.KEY_LOCATION_NEARBY_RADIUS, "30" ) );

        Bundle args = new Bundle();
        args.putParcelable( LocationColumns.LOCATION, location );
        args.putInt( LocationColumns.RADIUS, radius );

        ViewPager pager = (ViewPager) findViewById( R.id.content_pager );
        mTabsAdapter = new TabsAdapter( getActivity(), getChildFragmentManager(), pager );
        mTabsAdapter.addTab( "AIRPORTS", NearbyAirportsFragment.class, args );
        mTabsAdapter.addTab( "WEATHER", NearbyWxFragment.class, args );

        mLocationListeners = new ArrayList<LocationListener>();
        int tabCount = mTabsAdapter.getCount();
        int i = 0;
        while ( i < tabCount ) {
            Fragment fragment = mTabsAdapter.getItem( i );
            if ( fragment instanceof LocationListener ) {
                mLocationListeners.add( (LocationListener)fragment );
            }
            ++i;
        }

        PagerTabStrip tabs = (PagerTabStrip) findViewById( R.id.pager_tabs );
        tabs.setTabIndicatorColor( getResources().getColor( R.color.tab_indicator ) );

        setActionBarSubtitle( String.format( "Within %d NM Radius", radius ) );

        if ( savedInstanceState != null ) {
            pager.setCurrentItem( savedInstanceState.getInt( "nearbytab" ) );
        }
    }

    @Override
    public void onResume() {
        if ( mLocationManager != null ) {
            mLocationManager.requestLocationUpdates( LocationManager.NETWORK_PROVIDER,
                    30*DateUtils.SECOND_IN_MILLIS, 0.5f*GeoUtils.METERS_PER_STATUTE_MILE,
                    mLocationListener );

            SharedPreferences prefs =
                    PreferenceManager.getDefaultSharedPreferences( getActivity() );
            boolean useGps = prefs.getBoolean( PreferencesActivity.KEY_LOCATION_USE_GPS, false );
            if ( useGps ) {
                mLocationManager.requestLocationUpdates( LocationManager.GPS_PROVIDER,
                        30*DateUtils.SECOND_IN_MILLIS, 0.5f*GeoUtils.METERS_PER_STATUTE_MILE,
                        mLocationListener );
            }
        }

        super.onResume();
    }

    @Override
    public void onPause() {
        LocationManager lm = (LocationManager) getActivity().getSystemService(
                Context.LOCATION_SERVICE );
        if ( lm != null ) {
            lm.removeUpdates( mLocationListener );
        }

        super.onPause();
    }

    @Override
    public void onSaveInstanceState( Bundle outState ) {
        super.onSaveInstanceState( outState );
        ViewPager pager = (ViewPager) findViewById( R.id.content_pager );
        if ( pager != null ) {
            outState.putInt( "nearbytab", pager.getCurrentItem() );
        }
    }

    @Override
    protected int getActivityId() {
        return ID_NEARBY;
    };

    protected Location getLastKnownGoodLocation() {
        // Get the last known location to use a starting point
        Location location = mLocationManager.getLastKnownLocation( LocationManager.GPS_PROVIDER );
        if ( location == null ) {
            // Try to get last location from network provider
            location = mLocationManager.getLastKnownLocation( LocationManager.NETWORK_PROVIDER );
        }

        if ( location != null ) {
            Date now = new Date();
            long age = now.getTime()-location.getTime();
            if ( age > TOO_OLD ) {
                // Discard too old
                location = null;
            }
        }

        return location;
    }

    private final class AirportsLocationListener implements LocationListener {

        @Override
        public void onLocationChanged( Location location ) {
            if ( mLocationListeners == null ) {
                return;
            }

            // Is this location an improvement from the last?
            if ( GeoUtils.isBetterLocation( location, mLastLocation ) ) {
                // Propagate the location update to all fragments
                for ( LocationListener l : mLocationListeners ) {
                    l.onLocationChanged( location );
                }
                mLastLocation = location;
            }
        }

        @Override
        public void onProviderDisabled( String provider ) {
        }

        @Override
        public void onProviderEnabled( String provider ) {
        }

        @Override
        public void onStatusChanged( String provider, int status, Bundle extras ) {
        }
        
    }

}
